# Nacos 配置目录（Nacos 的运行真源）

> **契约（一句话）**：`nacos-config/**` 是 Nacos 配置的**唯一真源**。改这里的 `.yml` ⇒ **必须导入 Nacos 才对线上生效**。
>
> 这一步已由 CI 作业 **`nacos-import`** 自动化：只要 `nacos-config/**` 有变更并推到 `dev`/`main`，
> 就会自动导入线上 Nacos 并逐份回读校验。**正常情况下不需要人工执行任何命令。**

---

## 1. 配置怎么生效（改配置的标准流程）

```
改 nacos-config/*.yml  →  git push  →  CI 自动：
                                        ├─ nacos-config-check  等价性门禁（与代码非 dev 档逐键比对）
                                        └─ nacos-import        导入 Nacos + 逐份 md5 回读校验
                                     →  服务下次启动时接管（见 §4 生效时机）
```

* 手工修复/应急时也可以用 §3 的命令直接导入，脚本幂等，重跑安全。
* **严禁**绕过本目录直接改 Nacos 控制台：线上 Nacos 应当始终是仓库的镜像，
  否则下一次部署/导入会把控制台的手工改动覆盖掉，且无人知晓线上到底跑的是什么值。

---

## 2. 文件清单与消费方

每个服务的 `application.yml` 用 `spring.config.import` 显式声明要拉哪几份（Nacos 优先级**高于** jar 内 yml）：

| dataId | 消费方 | refreshEnabled | 用途 |
|---|---|---|---|
| `zxyz-static.yml` | 9 个业务服务 | `false` | 与代码强耦合的静态配置（分页、限流、上传限制等） |
| `zxyz-dynamic.yml` | **全部服务（含 gateway）** | **`true`** | 共享动态配置，**支持热更新** |
| `zxyz-<svc>.yml` | 各自服务（admin/audit/email/file/im/project/share/team/user 共 9 份） | `false` | 服务专属配置 |
| `zxyz-gateway.yml` | ⚠️ **无任何消费方** | — | **死配置**：gateway 只 import `dynamic`，不 import 本文件。改它**不会生效**（见 §6） |

> `import.sh` 会把目录下**所有** `*.yml` 全量导入（含 `zxyz-gateway.yml`）。
> 导入一份无人读取的配置本身无害，但**不要误以为改了它有用**。

分组固定为 **`group=ZXYZ`**，命名空间为 **public**（`namespaceId` 为空；`NACOS_NAMESPACE` 可用于多环境隔离）。

---

## 3. 手工导入（应急 / 本地）

```bash
# 在**服务器上**执行（8848/18081 只绑定 127.0.0.1，外部不可达）
cd /www/zxyz-repo/nacos-config
bash ./import.sh "" 127.0.0.1:8848 127.0.0.1:18081
#      ↑namespace  ↑admin/client 端口  ↑console 端口
```

* 端口分工（Nacos 3.x 与 2.x 不同，容易踩）：
  * **8848** —— 客户端/管理 API（`POST /nacos/v3/admin/cs/config` 写入）。
  * **18081**（容器内 8080）—— **控制台**，登录接口 `POST /v3/auth/user/login` **只在这里**，
    且路径**没有 `/nacos` 前缀**。
* 要在本机跑需先建隧道：
  `ssh -N -L 8848:127.0.0.1:8848 -L 18081:127.0.0.1:18081 root@<host>`

---

## 4. 生效时机（改完不是立刻全部生效）

| 配置 | 生效方式 |
|---|---|
| `zxyz-dynamic.yml` | **热更新**：导入后 Nacos 主动 push，运行中的服务立刻更新（日志：`Receive Nacos config change: dataId=zxyz-dynamic.yml`）**无需重启** |
| `zxyz-static.yml`、`zxyz-<svc>.yml` | **需重启对应服务**：`refreshEnabled=false` ⇒ 只在服务**启动时**拉取一次。改完导入后，不重启服务则线上仍跑旧值 |

> 由于等价性门禁（§5）保证「Nacos 里的值 == 代码非 dev 档的取值」，
> 导入本身**不会改变任何运行时行为** —— 它只是把配置源从 jar 内 yml 搬到 Nacos，从而拿到热更新能力。

---

## 5. 前置护栏（`import.sh` 自带，自动阻断）

1. **顶层重复 key**：SnakeYAML 视重复键为致命错误（服务直接起不来），检出即中止发布；无 `pyyaml` 时降级为文本层面扫描。
2. **字面量机密**：`password`/`secret`/`token` 类键的值必须以 `${`（env 引用）或 `ENC(`（Jasypt 密文）开头，否则视为明文入库并中止。
3. **导入后 md5 回读校验**：逐份对比 Nacos 返回值与本地文件字节的 md5，不一致即失败退出 ——
   用于消除「导入返回 200 但其实没生效」这类静默故障（实测写入后可读视图有短暂滞后，故脚本内置退避重试）。

另有一个**独立的等价性门禁**（CI 作业 `nacos-config-check`）：

```bash
python3 scripts/check-nacos-config-sync.py     # 本地也能跑，需 pyyaml
```

它把 `nacos-config/*.yml` 与**各消费方在非 dev 档的实际取值**逐键比对，输出 `[DIFF]`（阻断）/`[DEV-DRIFT]`/`[EXTRA]`/`[MISS]`/`[UNCONSUMED]`。当前基线：**PASS，0 阻断**，1 处 `[UNCONSUMED]`（即 `zxyz-gateway.yml`）。

---

## 6. 鉴权

`import.sh` 支持**双通道**，按序尝试：

| 顺序 | 通道 | 凭据 | 说明 |
|---|---|---|---|
| 1 | **accessToken** | `.env` 的 `NACOS_USERNAME` / `NACOS_PASSWORD` | 正统路径。走 console 端口 `POST /v3/auth/user/login` |
| 2 | **server-identity** 请求头 | `.env` 的 `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` | 兜底。Nacos 的"可信 server"白名单机制，实测对管理 API 同样免 token |

**为什么要双通道**：本项目 `nacos.core.auth.enabled=false`，而 Nacos 3.x 在鉴权关闭时**不会**自动创建内置 `nacos` 用户
（`nacos.users` 表为空）⇒ 登录永远返回 `User nacos not found`，token 通道从第一天起就是死的。
现由 `scripts/init-nacos-auth.sh` 补上这一步（幂等，可 `--dry-run`）：

```bash
# 服务器上执行；用户已存在时不改密码，只补 enabled/ROLE_ADMIN/权限
./scripts/init-nacos-auth.sh
./scripts/init-nacos-auth.sh --reset-password    # 用 .env 的 NACOS_PASSWORD 强制重置
```

副作用（正面）：Nacos 控制台（`https://<host>/next/`）从此可以用 `${NACOS_USERNAME}` / `${NACOS_PASSWORD}` 登录。

> ⚠️ 重建 Nacos 数据卷 / 换库后，用户会消失，需重跑一次 `init-nacos-auth.sh`。
> 兜底通道（server-identity）不依赖用户表，所以即使忘了跑，`import.sh` 也能工作。

---

## 7. 故障排查

| 症状 | 原因 / 处理 |
|---|---|
| `导入失败 403 access denied` | 走的是 console 端口或不带鉴权头。管理 API 在 **8848**，且必须带 token 或 identity 头 |
| `No endpoint POST /nacos/v1/auth/login` / 403 | **Nacos 3.x 已下线 v1 鉴权端点**。登录请用 console 端口（18081）的 `/v3/auth/user/login` |
| 导入返回 200 但回读 `20004 resource not found` | ① 命名空间/分组口径不一致（本目录固定 `group=ZXYZ` + public）；② **写入后可读视图短暂滞后**，脚本已内置重试 |
| `User nacos not found` | 用户表为空，跑 `scripts/init-nacos-auth.sh` |
| 改完配置线上没变化 | 很可能是**没重启服务**（§4），或改的是 `zxyz-gateway.yml`（无人消费，§2） |

---

## 8. 历史坑（不要再踩）

* **`/nacos/v1/cs/configs` 与 `/nacos/v1/auth/login` 在 Nacos 3.x 已被移除**（实测 404/403）。
  正确写法：读 `GET /nacos/v3/client/cs/config?dataId=..&groupName=..&namespaceId=..`（无需鉴权），
  写 `POST /nacos/v3/admin/cs/config`。参数名是 **`groupName`**，传 `group` 会 400。
* **`scripts/import_nacos_configs.py` 已删除**。它直连 MySQL 写 `config_info`，存在三个致命问题：
  ① 首行 `DELETE FROM config_info` 会**无备份清空整库配置**；
  ② 不写 `tenant_id`（NULL ≠ `''`）⇒ 服务按 public 读**根本读不到**，写进去也静默不生效；
  ③ 绕过 Nacos 服务端 ⇒ 缓存与变更通知都不刷新，需重启 Nacos 才可能生效。
  **结论：一切导入都走 `import.sh`（HTTP API），不要直写数据库。**
* 曾长期认为「Nacos 配置不自动生效，必须手动 `import.sh`」，而事实是**从未导入过**
  （线上 `nacos.config_info` 一度为 0 行）⇒ 服务实际只读 jar 内 yml。现已导入并接入 CI（§1）。

---

## 变更记录

* **2026-09-13** —— 首次全量入库（12 份，逐份 md5 与仓库一致）；`import.sh` 修复 v3 端点与双通道鉴权、
  新增回读校验与退避重试、`pyyaml` 缺失降级；新增 `scripts/init-nacos-auth.sh`；新增 CI 作业 `nacos-import`
  把「改配置 ⇒ 导入」自动化；删除直写数据库的 `scripts/import_nacos_configs.py`。
