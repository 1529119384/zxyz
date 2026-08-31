# 服务间鉴权白名单矩阵 — 启用操作手稿

> 面向部署/运维。本手稿把 `docker-compose.yml` 顶部与 `.env.example` 的矩阵启用契约展开为**可执行步骤**。
> 前提：已部署 `docs`/`docker-compose.yml`（`/www/zxyz/`）、服务器 `.env` 独立维护、`scripts/validate-env.sh` 可用。

## 1. 背景与两种模式

| 模式 | 条件 | 服务间校验 | 风险 |
|---|---|---|---|
| **过渡（默认）** | 所有收方 `app.internal.allowed-sources` 为空 map | `INTERNAL_SERVICE_TOKEN` 单 token 常量时间比对 | 持 token 服务可伪造任意来源标识（横向移动窗口） |
| **矩阵** | 任意收方配置了非空 `allowed-sources` | 按 `X-Internal-Caller-Service` 查该来源独立密钥比对，未列来源**拒绝**（fail-closed） | 漏配某调用方 → 该调用 401/500 |

启动矩阵 = **逐收方**切换。建议：**先只开 email → 验证 → 再逐个服务铺开**。

## 2. 启用前置（必读，避免踩雷）

1. **当前过渡模式已可安全运行**：先确认 `INTERNAL_SERVICE_TOKEN` 已是非占位强随机串（`scripts/validate-env.sh .env` 通过）。
2. **写死 ghost 的含义**：`allowed-sources` 一旦非空，**任何未列来源将被拒绝**。开启前务必核对「该收方会被哪些服务调用」，全列出，否则某服务 401。
3. **fail-closed 语义**：`allowed-sources` 内的密钥若引用了**未定义的环境变量**（如 `${SVC_TEAM_KEY}` 不存在），该服务会**拒绝启动**（Spring `@Value` 解析失败）——这是一道强制配齐的安全门。
4. **禁止 YAML 默认值**：任何密钥都不许写 `:dev-xxx` 默认。密钥一律来自 `.env`。

## 3. 完整步骤

### 3.1 生成每服务独立密钥

为**每个后端服务**（admin/project/im/email/user/share/file/team）与 **gateway** 各生成一个高强度随机串：

```bash
# 每服务一条；全部互不相同、且 ≠ INTERNAL_SERVICE_TOKEN
openssl rand -hex 32   # 生成 64 hex 字符，逐服务执行保存
```

### 3.2 写入服务器 .env（/www/zxyz/.env）

取消 `SVC_*_KEY` 注释并填入刚生成的密钥：

```
SVC_PROJECT_KEY=...
SVC_IM_KEY=...
SVC_EMAIL_KEY=...
SVC_USER_KEY=...
SVC_SHARE_KEY=...
SVC_FILE_KEY=...
SVC_TEAM_KEY=...
SVC_ADMIN_KEY=...
SVC_GATEWAY_KEY=...
# audit 不参与服务间 HTTP 鉴权（纯 MQ），可不配；如需统一保留亦可
```

关键：`SVC_GATEWAY_KEY` 必须**与网关在 admin-email 桥接注入的 token 一致**（见 3.4）。临时可用 `INTERNAL_SERVICE_TOKEN`，但那样该管理路径的隔离形同虚设——生产请务必用独立值。

### 3.3 每容器映射本服务签发密钥（**矩阵启用必做，否则全员 401/500**）

当前所有 sent 方发调用都用 `selfServiceKey`（读 `app.internal-service-key`）。若容器没设该变量，`selfServiceKey` 为空、回退 legacy token → 收方一旦开矩阵就拒。故**在为收件「开矩阵」前，先给所有调用方容器补齐签发密钥**。在 `docker-compose.yml` 每个应用服务 + gateway 的 `environment` 增加：

```yaml
      APP_INTERNAL_SERVICE_KEY: ${SVC_<SERVICE>_KEY}   # 例：team-service 用 ${SVC_TEAM_KEY}
```

（`app.internal-service-key` = 本服务向收方签发的密钥。Spring relaxed binding 把 `APP_INTERNAL_SERVICE_KEY` 映射到 `app.internal-service-key`。）

### 3.4 网关 admin 桥接身份

网关 `application.yml` 的两条 admin 桥接路由现状（已按矩阵启用要求整改）：

| 路由 | 目标 | token 头 | caller 头 | 说明 |
|---|---|---|---|---|
| `admin-email` | email-service `/api/email/internal/**` | `${SVC_GATEWAY_KEY:${INTERNAL_SERVICE_TOKEN}}` | `zxyz-gateway` | **真正走内部鉴权的桥接**，email 的 `EmailInternalAuthInterceptor` 覆盖该前缀 |
| `admin-database` | project-service `/api/admin/database/**` | 无（已移除） | 无 | 该路径由 Sa-Token 登录态 + `@SaCheckRole(SYSTEM_ADMIN)` 把关，**不消费内部 token** |

- `StripInternalHeadersFilter` 会先剥外部伪造内部头，再由 route filters 补回受信身份（时序：GlobalFilter 先剥、route filter 后补，成立）。
- **`admin-email` 的密钥是嵌套默认值**：优先取 `SVC_GATEWAY_KEY`，过渡期未设时回退 `INTERNAL_SERVICE_TOKEN`。
  已实测 Spring Boot 3.5.7 的 `PropertySourcesPlaceholdersResolver` 支持这种嵌套（`${A:${B}}` 会被递归解析），
  过渡态无需改网关即可启动。禁止改写字面量默认值。
- **`admin-database` 的头已移除而非补齐**：project-service 的 `InternalServiceAuthInterceptor` 只注册在
  `/api/internal/**`（`SaTokenConfigure` 硬编码），`/api/admin/database/**` 不参与服务间鉴权，
  原先注入的 token 既无 `X-Internal-Caller-Service` 配对、也无消费方，属冗余遗留。
  保留它只会在「将来有人把该路径纳入内部鉴权」时变成一次隐蔽的 401。
  若将来确需内部鉴权，必须同时补 token **和** caller 两个头。

### 3.4.1 网关容器必须收到 SVC_GATEWAY_KEY

`SVC_*_KEY` 默认不在 compose 的 gateway `environment` 里，容器内看不到该变量，
`${SVC_GATEWAY_KEY:${INTERNAL_SERVICE_TOKEN}}` 就会一直走回退分支。启用矩阵时在 gateway 服务补：

```yaml
      # 用 :? 硬失败，避免「变量未设置 → 空串 → 桥接带空 token → 邮件管理后台 401」这一静默陷阱
      SVC_GATEWAY_KEY: ${SVC_GATEWAY_KEY:?启用矩阵必须设置 SVC_GATEWAY_KEY}
```

写普通 `${SVC_GATEWAY_KEY}` 也可以但**不推荐**：变量缺失时 compose 只告警并替换为空串，
Spring 会把它当作「已解析为空串」而不会走回退，结果是邮件管理后台全线 401 且报错信息毫无指向性。

### 3.5 打开收方矩阵（建议先 email 试点）

在收方服务 `application.yml` 的 `app.internal.allowed-sources` 声明「允许来源 → 该来源密钥」。示例（email）：
```yaml
app:
  internal:
    allowed-sources:
      zxyz-user-service: ${SVC_USER_KEY}
      zxyz-admin-service: ${SVC_ADMIN_KEY}
      zxyz-project-service: ${SVC_PROJECT_KEY}
      zxyz-im-service: ${SVC_IM_KEY}
      zxyz-file-service: ${SVC_FILE_KEY}
      zxyz-team-service: ${SVC_TEAM_KEY}
      zxyz-gateway: ${SVC_GATEWAY_KEY}
```
⚠️ `email` 是**唯一经网关桥接**的收方（`/api/email/internal/**`），因此必须列 `zxyz-gateway`。其余收方是否列 gateway 取决于它们是否也接受 HTTP gateway 转发（目前除 email 外没有）。

禁止直接改服务 `application.yml`？可放 Nacos `zxyz-dynamic.yml`（热更新）——但 `@Value` 热更新不生效，本次不走热更，须重启且密钥禁进 Nacos 动态明文时用源码 yml + `.env` 乘法更稳。**推荐：在 `docker-compose.yml` 直接挂 `SPRING_APPLICATION_JSON` 注入 allowed-sources 明单（静态、随容器、进 git），而非改源码。**

### 3.6 生效与验证

```bash
# 改完 .env 后必须重建（restart 不重载 env）
./scripts/validate-env.sh /www/zxyz/.env
cd /www/zxyz && docker compose up -d -- <收方服务> <其调用方服务>

# 健康检查
./scripts/health-check.sh

# 正向：合法调用仍通（观察收方日志不再有 401/500）
# 反向：伪造来源应被 401 拒，日志见「内部服务鉴权失败」且 token 掩码
curl -s 收方内部端点 -H "X-Internal-Caller-Service: zxyz-im-service" \
     -H "X-Internal-Service-Token: 破坏的key" -o /dev/null -w "%{http_code}\n"   # 期望 401/403
```

## 4. 回滚

```bash
cd /www/zxyz
# 回滚一处矩阵：把对应 allowed-sources 态清空/注释，重建容器
# 备：scripts/rollback.sh   回滚到上一个构建版本
```

改不回时，**恢复过渡模式** = 所有收方 `allowed-sources` 为空 → 每个容器重启，回退 `INTERNAL_SERVICE_TOKEN`。store telemetry。

## 5. 常见事故（防踩坑清单）

| # | 症状 | 根因 | 处理 |
|---|---|---|---|
| 1 | 开矩阵后某服务全线 500/401 | 调用方容器未设 `APP_INTERNAL_SERVICE_KEY`（sent 仍发 legacy token） | 按 3.3 补齐所有调用方容器签发密钥 |
| 2 | admin 邮件管理后台全 401 | email 开了矩阵，但 `SVC_GATEWAY_KEY` ≠ 网关桥接 token，或 email 名单未列 `zxyz-gateway`，或网关容器根本没收到 `SVC_GATEWAY_KEY` | 对齐 3.4 / 3.4.1 / 3.5 |
| 3 | 服务启动瞬间失败（`@Value` 未定义变量） | `allowed-sources` 引用了未定义的 `${SVC_X}` | 在 `.env` 补上 SVC_X 再重建 |
| 4 | 某服务调用偶发 401 | 收件人名单漏了该调用方 | 把调用方加进 `allowed-sources` |
| 5 | 改了配置仍旧旧行为 | 改了 `.env` 没 `docker compose up -d`（`restart` 不重载 env） | 重建容器 |
| 6 | 网关起来了、`.env` 也配了，桥接仍走共享 token | compose gateway 服务没把 `SVC_GATEWAY_KEY` 传进容器（3.4.1） | 补 3.4.1 的 environment 映射并 `up -d`，用 `docker compose config` 复核 |
| 7 | 邮件管理后台 401 且网关日志里 token 是空串 | 用了普通 `${SVC_GATEWAY_KEY}` 且变量缺失 → 空串被当作「已解析」，**不会**回退到共享 token | 改用 3.4.1 的 `:?` 硬失败写法 |

> 先小范围灰度（email + 其调用方），稳定后再对 team/project/share 批量铺开。矩阵启用是安全从**共享单 key** 到**服务级隔离**的跃迁，请预留回滚窗口。