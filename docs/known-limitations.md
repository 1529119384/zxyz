# 已知限制（Known Limitations）

> **这份文档记录「当前确实不可用 / 确实不做」的事**，避免以后有人把它当成新 bug 反复排查。
>
> 与 `ISSUE/*.md`（待办与待决策）的区别：这里记录的是**已决策接受**的现状，**不是待办**。
> 每条都写明「为什么接受」与「将来要改时的接入点」。
>
> 生成：2026-09-14 · 来源：`ISSUE/18-DECISION-SHEET-2026-09-14.md` 的 B2 / B6 决策
>
> 📌 **引用说明**：本仓 `ISSUE/` 目录**不入库**（见 `.gitignore`）⇒ 文中所有 `ISSUE/*` 引用在全新 clone 里**无法解析**，**仅供本地台账对照**。`ISSUE/15`、`ISSUE/16`、`ISSUE/17` 均已不在仓内。

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

- 服务器规格：**4 核 / 7.94 GB / 无 swap**；容器 limits 之和 **6.750 GiB**（2026-10-03 B-1 起；此前 7.875 GiB，更早审计口径 8.125 GiB），占物理内存 **85.0%**，余量 **1.190 GiB（15.0%）**。
- **虽已不再超卖，但余量不足以再容纳一个中间件从库** —— 按本仓 limits，一个 MySQL 从库至少还需 896 MiB，加装后余量将从 1218.56 MiB 压至约 **322 MiB**，等于回到 B-1 要消灭的境地。
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

## 变更记录

- **2026-09-14** — 首次建立：手机验证码无通道（B2 决策）、中间件单实例（B6 决策）、TLS 未强制（待 D1 收口）。
- **2026-09-22** — 回代码复核后订正：① §4 改写为 `validate-env.sh:172-199` 的**真实**行为（`ALLOW_INSECURE_HTTP` 显式豁免 + 未设置时 fail-closed ERROR），删除"正在被 `ISSUE/15` D1 子批 2 收口 / 完成后更新"这句**已失效的承诺**；② §3 恢复演练表述改为"定期执行能力已提供但默认关闭"，不再说"未纳入定期执行"；③ 悬空引用统一：`ISSUE/15` → `ISSUE/18-DECISION-SHEET-2026-09-14.md`（文件头、§1、§3），并在文件头声明 `ISSUE/` 不入库、引用仅供本地台账对照。
- **2026-10-03** — §1 按用户拍板（`ISSUE/39-UNFINISHED-2026-10-02.md` B-5）改写：手机验证码由「静默失败」改为「响亮失败」（无通道时 `HTTP 400 / code=4000 / 手机验证码暂未开放`）；**同日代码已落地**（WS4），本节同步为落地后口径并写明恢复锚点。
