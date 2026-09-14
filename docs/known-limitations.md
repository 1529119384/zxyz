# 已知限制（Known Limitations）

> **这份文档记录「当前确实不可用 / 确实不做」的事**，避免以后有人把它当成新 bug 反复排查。
>
> 与 `ISSUE/*.md`（待办与待决策）的区别：这里记录的是**已决策接受**的现状，**不是待办**。
> 每条都写明「为什么接受」与「将来要改时的接入点」。
>
> 生成：2026-09-14 · 来源：`ISSUE/15-PENDING-DECISIONS-2026-09-14.md` 的 B2 / B6 决策

---

## 1. 手机验证码：生产环境不可用（未接入短信通道）

**现状**：`zxyz-user-service` 的 `ContactVerificationService.createPhoneVerificationCode()`
会生成一个 6 位验证码并**哈希落库**，但**没有任何发送动作** —— 本项目没有短信通道。

**按环境的表现**：

| 环境 | `VERIFICATION_RETURN_CODE` | 实际表现 |
|---|---|---|
| dev（`scripts/run-local.sh` 直跑） | `true` | 验证码**回显在接口响应里** ⇒ 手机验证流程可以走通 |
| 生产（compose 注入） | `false` | 接口返回成功，但码**既不返回也不发送** ⇒ 用户**永远拿不到手机验证码** ⇒ **手机验证功能不可用** |

⚠️ 这是**静默失败**：接口不报错，用户看到的是"验证码已发送"。

**决策（2026-09-14，`ISSUE/15` B2 选 B）**：**暂无短信通道，维持现状 + 预留接入位**（不实现发送）。

**预留的接入点**：

- **代码锚点**：`ContactVerificationService.createPhoneVerificationCode()` 内的 `TODO(短信通道)` 注释。
- **接入时应做**（三条，缺一不可）：
  1. 实现真实发送（自建 sms-service，或对接第三方 SMS API）；
  2. **发送成功后才返回**；失败要抛业务异常并**释放冷却键**（照抄 `createEmailVerificationCode` 的 `try/catch` 写法）；
  3. 通道就绪前若想让失败显式化，可让该方法在「通道未启用」时**抛"手机验证码暂未开放"**
     —— 即把**静默失败改成响亮失败**。
     ⚠️ 这会改变运行行为，**需单独确认后再做**（当前未做）。
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

**已决策接受**（2026-09-14，`ISSUE/15` B6 选 C）。

- 服务器规格：**4 核 / 7.94 GB / 无 swap**；容器 limits 之和 **8.125 GiB > 物理内存**
  ⇒ **物理上加不了 MySQL / RabbitMQ 从库**。
- 恢复演练脚本 `scripts/restore-drill.sh` **已存在**，但**未纳入定期执行**。

⇒ **风险接受**：主机故障 = 服务中断 + 可能的数据丢失。

> 若要改变这一决定，需要先**升级服务器规格**（否则从库会与业务容器抢内存，反而制造故障）。

---

## 4. 生产默认未启用 TLS

`docker-compose.tls.yml` 与 `deploy/nginx/default-ssl.conf` 都已就绪；
`scripts/validate-env.sh` 在 `TLS_ENABLED=true` 时会**硬校验** `AUTH_COOKIE_SECURE=true`（ERROR 级）。
但**未启用 TLS 时只 WARN、不阻断** ⇒ 默认部署形态仍是 HTTP。

> 这一条正在被 `ISSUE/15` D1 子批 2 收口（把 `TLS_ENABLED=false` 的 WARN 提升为显式确认项），
> 完成后本节会随之更新。

---

## 变更记录

- **2026-09-14** — 首次建立：手机验证码无通道（B2 决策）、中间件单实例（B6 决策）、TLS 未强制（待 D1 收口）。
