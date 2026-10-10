# 已知限制（Known Limitations）

> **这份文档记录「当前确实不可用 / 确实不做」的事**，避免以后有人把它当成新 bug 反复排查。
>
> 与 `ISSUE/*.md`（待办与待决策）的区别：这里记录的是**已决策接受**的现状，**不是待办**。
> 每条都写明「为什么接受」与「将来要改时的接入点」。
>
> 生成：2026-09-14 · 来源：`ISSUE/18-DECISION-SHEET-2026-09-14.md` 的 B2 / B6 决策
>
> 📌 **引用说明**：本仓 `ISSUE/` 目录**不入库**（见 `.gitignore`）⇒ 文中所有 `ISSUE/*` 引用在全新 clone 里**无法解析**，**仅供本地台账对照**。其中 **`ISSUE/15`、`ISSUE/16`、`ISSUE/17`、`ISSUE/18`（含 `ISSUE/18-DECISION-SHEET-2026-09-14.md`）、`ISSUE/39`（含 `ISSUE/39-UNFINISHED-2026-10-02.md`）属「已归档」**范畴，等价原件在仓库外 `D:\code\databaseZXYZ\oldmd\`（如 `oldmd/18-DECISION-SHEET-2026-09-14.md`、`oldmd/39-UNFINISHED-2026-10-02.md`）。⚠️ **`ISSUE/47`、`ISSUE/48` 等 44 号及以后的引用不同**：它们**在库**（`ISSUE/` 目录下真实存在），可直接解析，标注此点以免两类引用被混为一谈。

---

## 1. 手机验证码：生产环境不可用（未接入短信通道）

**现状**：`zxyz-user-service` 的 `ContactVerificationService.createPhoneVerificationCode()`
**没有短信通道可发**——本项目未接入任何 SMS 服务。

**行为（2026-10-03 用户拍板 B-5，代码已落地）**：

```
POST /api/users/phone/verification-code
→ 无短信通道时返回 HTTP 400 / code=4000 / msg=手机验证码暂未开放（响亮失败）
```

不再有「提示已发送但收不到」的静默行为；用户明确得知功能不可用。

> ⚠️ **口径变更记录**：2026-09-14 B2 决策时为「静默失败」（生成 + 哈希落库、不发送、接口仍返回成功）；
> 2026-10-03 用户拍板（`ISSUE/39-UNFINISHED-2026-10-02.md` B-5）改为**响亮失败**并已落地
> （静默失败会让用户误以为验证码已发出并反复白等，属误导性 UX）。

**将来接入短信通道时的恢复锚点**：

- **代码位置**：`ContactVerificationService.createPhoneVerificationCode()`——代码里的 `TODO(短信通道)`
  注释已写明**三条恢复条件**：
  1. 实现真实发送（自建 sms-service，或对接第三方 SMS API）；
  2. **发送成功才返回**；失败要抛业务异常并**释放冷却键**；
  3. 删掉 throw，恢复 `acquire → createContactVerificationCode` 的原流程。
- **不需要**新增 `.env` 键：`app.verification.phone-code.*`（冷却时长、最大尝试次数）已在
  `ServiceProperties` 中；擅自加键会让 `scripts/check-nacos-config-sync.py` 报 `[EXTRA]`。

---

## 2. 邮箱验证码：可用（但依赖 SMTP 配置）

邮箱验证码由 `zxyz-email-service` 生成 + **哈希落库** + 经邮件模板发送，链路完整（`VerifyCodeService.sendCode`）。
生产能否**真正投递**取决于 `EMAIL_*`（SMTP）配置是否有效；若未配置，
`EmailSendingAvailabilityService.requireSendingAvailable()` 会**主动拦截**（这是**响亮失败**，不会静默）。

> 附：两个服务的验证码**都是哈希落库**（`VerifyCodeHasher.hash(code)`），不是明文
> —— `V3__widen_verify_code_code.sql` / `V4__widen_contact_verification_code_code.sql` 就是为此加宽字段的。

---

## 3. 中间件全部单实例（无主从、无异地备份）

**已决策接受**（2026-09-14，`ISSUE/18` B6 选 C）。

- 服务器规格：**4 核 / 8 GB（生产 `free -m` 实测 7941 MiB）/ 无 swap**；容器 limits **声明值**之和 **7552 MiB = 7.375 GiB**（2026-10-10 ISSUE/51 P1 起：redis 256M→896M 会话防线 + rabbitmq 水位线；此前 6912 MiB = 2026-10-03 B-1，更早 7.875 GiB、审计口径 8.125 GiB），占物理内存 **7552 / 7941 ≈ 95.1%**，余量 **389 MiB（4.9%）**——低于 B-1 时的 13.0%，属有意取舍（用宿主余量换「Redis OOM = 全站会话丢失」失败面的消失）。
  ⚠️ **两套口径必须分开看**（2026-10-09 订正：旧文分母 8130.56 MiB 是把 7.94 GB 当 MiB 的混算值，实际物理内存以生产 `free -m` 实测 7941 MiB 为准；2026-10-10 已同步订正 docker-compose.yml 注释里的同型残留）：
  - **声明值**（compose `limits:` 层级机械解析）= 7552 MiB / **95.1%** / 余量 389 MiB；
  - **运行值**（infra 容器 mysql/nacos/rabbitmq 未按新限额重建时，`docker inspect HostConfig.Memory` 实测）= **7424 MiB / 93.5% / 余量 517 MiB** —— 生产已真实发生 RabbitMQ 被 cgroup OOM 击杀（2026-10-08），**重建 infra 容器前**应按运行值做容量决策。
  - 计算式与实测命令详见 `docs/claude-infra.md`《维护窗口》节。
- **虽已不再超卖，但余量不足以再容纳一个中间件从库** —— 按本仓 limits（2026-10-10 P1 后声明值 7552 MiB），一个 MySQL 从库至少还需 896 MiB，加装后余量将从 389 MiB 压至约 **−507 MiB（负余量，重回超卖）**，比 B-1 要消灭的境地更糟（B-1 时点旧口径：1218.56 MiB → 322 MiB，分母 8130.56 已废弃）。
  ⇒ **物理上加不了 MySQL / RabbitMQ 从库**。
- 恢复演练脚本 `scripts/restore-drill.sh` **已存在**；**定期执行能力已提供但默认关闭** —— 需**显式开启**才会按周期执行（当前不随日常流程自动跑）。

⇒ **风险接受**：主机故障 = 服务中断 + 可能的数据丢失。

> 若要改变这一决定，需要先**升级服务器规格**（否则从库会与业务容器抢内存，反而制造故障）。

---

## 4. 生产默认未启用 TLS

`docker-compose.tls.yml` 与 `deploy/nginx/default-ssl.conf` 都已就绪。
`scripts/validate-env.sh:172-199` 对「Cookie Secure / TLS 一致性」做 **fail-closed** 校验，共四个分支：

| `TLS_ENABLED` | `AUTH_COOKIE_SECURE` | `ALLOW_INSECURE_HTTP` | 结果 |
|---|---|---|---|
| `true` | `true` | — | OK（`:180`） |
| `true` | 非 `true` | — | **ERROR**：HTTPS 已启用却仍签发非 Secure Cookie（`:177`） |
| 非 `true` | `true` | — | OK（`:183`） |
| 非 `true` | 非 `true` | `true` | **WARN**：明文 HTTP 已被豁免键显式放行（`:187`，提醒而非阻断） |
| 非 `true` | 非 `true` | 非 `true` | **ERROR**：站点走 HTTP 且未显式豁免（`:193`，**默认拒绝**） |

⇒ **当前默认部署形态仍是 HTTP**：未启用 TLS 时，必须在 `.env` 写入 `ALLOW_INSECURE_HTTP=true`
才能通过校验，否则 `validate-env.sh` **直接报 ERROR、阻断部署**。明文 HTTP **不再"默认发生"** ——
它必须被**显式写下来**才放行，且每次部署仍会打一条 WARN 提醒残留风险。

**为什么接受这个现状**：当前生产为 **IP 直连 HTTP**（无 HTTPS 域名），置 `AUTH_COOKIE_SECURE=true`
会因浏览器不回传 Secure Cookie **直接导致登录失效**（见 `validate-env.sh:173` 的原话）。
「默认拒绝 + 显式豁免键」把"无意中裸奔 HTTP"变成**一次有意决策**，而不依赖有人记得。

**将来要改时的接入点**：

- 启用容器内 TLS：`TLS_ENABLED=true` + `AUTH_COOKIE_SECURE=true`（见 `docker-compose.tls.yml`）；
  此时若 `AUTH_COOKIE_SECURE` 仍非 `true`，校验会立即 **ERROR**。
- 启用 TLS 后即可**删掉** `.env` 里的 `ALLOW_INSECURE_HTTP`（豁免键只在接受明文时才有意义）。

---

## 5. CI security-scan 阻断部署 + 主线冻结 → ✅ 已解除（2026-10-09）

> **状态：已解除（2026-10-09）**。冻结决策于同日随 Boot 4 迁移落地而自然到期，按本节自己写明的解除条件逐条核对后销账；以下保留原冻结期的历史脉络，供追溯「当时为什么这样决策」。

### 5.1 原决策（2026-10-09 拍板，现已成历史）

**当时现状（已决策接受，非待办）**：自 2026-10-08 起，CI 的 `security-scan`（Trivy）持续红灯，
`deploy` 因硬前置条件（`ci-cd.yml:1236-1238`：`needs.security-scan.result == 'success'`）**被拦**，
⇒ **dev 分支的一切自动部署被阻断**（含与本漏洞无关的后续修复）。用户 2026-10-09 拍板：
**主线冻结** —— 迁移期间不引入临时豁免，冻结期内生产应急一律用 `scripts/rollback.sh` 回滚镜像。

**两个 CVE**（2026-08-27 披露，NVD 状态 Analyzed，CVSS 3.1 均 **9.8 CRITICAL**）：

| CVE | 触发前提（AND 关系） | 影响组件 |
|---|---|---|
| `CVE-2026-47890` | 应用使用 **SSE**（SseEmitter 等）**且** 使用 view fragments 视图渲染 | spring-webmvc / spring-webflux **6.2.0–6.2.19**（本仓恰在区间上界） |
| `CVE-2026-47892` | **WebFlux functional endpoints**（RouterFunction）**且** 部署在 **DispatcherServlet** 下 | 同上（另含 6.1/6.0/5.3/5.2 线） |

**可达性实测（两条路径均不可达）**：全仓 main 源码排除 `target/` 后 —— SSE 相关（`SseEmitter`/
`ResponseBodyEmitter`/`StreamingResponseBody`/`text/event-stream`）= **0**；视图层（`ViewResolver`/
`ModelAndView`/`setViewName`）= **0**；functional endpoint（`RouterFunction` 等）= **0**；XSLT
（`XsltView`/`TransformerFactory`）= **0**；gateway 是纯 reactive 栈且 pom **两处主动排除**
`spring-boot-starter-web`（`zxyz-gateway/pom.xml` nacos-config 与 nacos-discovery 的 `<exclusions>`）
⇒ 无 DispatcherServlet。两个 CVE 的触发前提一个都不成立。

**为什么不能"升个版本"修**：Spring Framework **6.2.x 线止于 6.2.19**（开源支持 2026-06 已结束，
无 6.2.20 回补版）；唯一修复版 **7.0.9** 只能通过升级 Spring Boot 4 拿到。Boot 4 迁移的可行性
评估与规划见 `ISSUE/48-BOOT4-MIGRATION-PLAN-2026-10-09.md`（初判的三条硬阻塞已由其 §三
勘误下调为可执行路线，约 6–9.5 人日）。

**解除条件**：Boot 4 迁移合并到 dev 后**首次扫描**，镜像内 framework = 7.0.9 ⇒ 三条豁免全部
失效，须同步删除 `.trivyignore.yaml` 里的 `CVE-2026-47884`（既有条目，其"重新评估条件 2"即
Boot 4）与本节对应的两个 CVE 豁免（若冻结期间曾补登）⇒ CI 转绿、部署恢复。

**应急**：冻结期如需恢复生产版本，用 `scripts/rollback.sh` 回滚镜像（不引入新变更）。

> 背景：Trivy 漏洞库更新（2026-10-04 版）新报出这两个 CVE —— 同一 audit-service 依赖集在
> 三次连续 CI run 中由绿转红证明这是"新报出"而非"新引入"。完整判定材料见
> `ISSUE/47-CVE-SSE-HEADER-BYPASS-2026-10-09.md`（**在库**，可直接解析）。

### 5.2 销账核对（2026-10-09，按 5.1 解除条件逐条验证）

| 解除条件（§5.1 原文） | 实测证据（Lead 实测确认） | 判定 |
|---|---|---|
| Boot 4 迁移**合并到 dev** | 根仓库提交链 `f8b5dbf/3e8e4af/18e26c8/65988a7/56b049f/d6b26c4` 已合入 dev；生产 `/www/zxyz` 实跑 `d6b26c4` | ✅ |
| 合并后**首次扫描** framework = 7.0.9 | 分支流水线 security-scan **11/11 全绿**（run `37916073893`），`.trivyignore.yaml` 已置 `vulnerabilities: []`（空豁免，非放行），三 CVE **47884/47890/47892 消失** | ✅ |
| **CI 转绿** | run `37912603462`（fail → flyway 修复）→ `37914492630`（fail → tomcat 11.0.25 + jackson3 3.1.7 钉版）→ `37916073893`（全绿 11/11 + deploy 成功）三轮实战闭环 | ✅ |
| **部署恢复** | dev 部署 run `37923854901` 全绿（43 个 Testcontainers 集成测试首次真跑通过）→ 生产 15/16 healthy（`nacos-log-cleanup` 无 healthcheck 属设计），登录/路由正常 | ✅ |

⇒ **解除条件全部满足，本节冻结状态于 2026-10-09 解除**。空豁免 11/11 是真实通过（不是放行）：
镜像内 framework = 7.0.9 后两个 CRITICAL 的受影响版本区间（≤ 7.0.8 / ≤ 6.2.19）不再命中，
47884 的豁免条目也已随 `3e8e4af` 按其自身写明的「Boot 4 ⇒ 删除」承诺摘除。

### 5.3 遗留悬置（销账后仍开放的一项）

- **alone-redis db9 会话隔离：待首次成功登录实证（初判 FAIL 已于复核中推翻）** ——
  初判（20:55 实测 db0 有遗留会话 + db9=0）被三重证据推翻：① 20:09 两次登录实为密码错误
  （GlobalExceptionHandler WARN），未产生会话；② db0 的 satoken 键是旧镜像时代（e5c97d7，
  其 jar 内根本无 alone-redis 插件）遗留的会话，Redis 未重启故 TTL 自然存活，已于 22:01 前后
  自然过期清零；③ 本地最小复现（`ISSUE/boot4-migration/db9-repro/`，SSH 隧道直连生产 Redis）
  实证插件在 Boot 4.0.8 下**完全正常**——登录三键精确落 db9，四个失效假设全部证伪。
  当前 db9=0 仅意味着部署后尚无一次成功登录；服务器侧已挂被动观察器（`/tmp/db9-watch.log`），
  **首次真实成功登录落 db9 即最终闭环**。若届时仍未落 db9，再按增量方向排查
  （Redisson / zxyz-common 自动配置 / nacos 动态配置）。
  「服务起来了」≠「会话已迁 db9」的坑位警示（见 `CLAUDE.md`）维持有效——本次正是它推动
  了实测与复核。此项**不阻塞本节销账**（5.2 四项条件均与 db9 无关），但它是 Boot 4 部署后
  唯一未闭环的验证点。
  连带发现的独立配置漂移：服务器 `/www/zxyz/.env` 仍保留 `REDIS_DATABASE=0`（compose 注释
  明确要求删除——I-4 整改未执行），属服务器侧配置（不入库），待用户顺手处理。

---

## 变更记录

- **2026-09-14** — 首次建立：手机验证码无通道（B2 决策）、中间件单实例（B6 决策）、TLS 未强制（待 D1 收口）。
- **2026-09-22** — 回代码复核后订正：① §4 改写为 `validate-env.sh:172-199` 的**真实**行为（`ALLOW_INSECURE_HTTP` 显式豁免 + 未设置时 fail-closed ERROR），删除"正在被 `ISSUE/15` D1 子批 2 收口 / 完成后更新"这句**已失效的承诺**；② §3 恢复演练表述改为"定期执行能力已提供但默认关闭"，不再说"未纳入定期执行"；③ 悬空引用统一：`ISSUE/15` → `ISSUE/18-DECISION-SHEET-2026-09-14.md`（文件头、§1、§3），并在文件头声明 `ISSUE/` 不入库、引用仅供本地台账对照。
- **2026-10-03** — §1 按用户拍板（`ISSUE/39-UNFINISHED-2026-10-02.md` B-5）改写：手机验证码由「静默失败」改为「响亮失败」（无通道时 `HTTP 400 / code=4000 / 手机验证码暂未开放`）；**同日代码已落地**（WS4），本节同步为落地后口径并写明恢复锚点。
- **2026-10-09** — ① 新增 §5「CI security-scan 阻断部署 + 主线冻结」（两个 CRITICAL CVE 可达性实测为 0、唯一修复路径是 Boot 4、用户拍板不引入临时豁免、冻结期应急用 rollback.sh）；② §3 内存分母订正：物理内存以生产 `free -m` 实测 **7941 MiB** 为准（旧文 8130.56 MiB 是 GB/MiB 混算），并区分**声明值 87.0%** 与**运行值 93.5%** 两套口径（运行值含 2026-10-08 RabbitMQ 被 cgroup OOM 击杀的实证）。
- **2026-10-09（晚）** — §5 主线冻结**销账**：Boot 4 迁移合并进 dev（`d6b26c4`）、CI 三轮实战转绿（run `37916073893` 全绿 11/11 + deploy 成功）、dev 部署 run `37923854901` 全绿、生产 15/16 healthy，§5.1 写明的解除条件逐条满足 ⇒ 状态改「已解除（2026-10-09）」，原冻结决策与解除条件保留为 5.1 历史脉络，5.2 留销账核对表，5.3 记录唯一遗留悬置（alone-redis db9 会话隔离生产实测未生效——会话写 db0、登录正常无功能损失，插件 `init()` 静默退出，根因排查中）。
