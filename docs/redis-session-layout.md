# Redis 会话布局与演进路线（Sa-Token 统一 db9）

> **定位**：本文回答「这套 Redis 分库 + 会话独立布局走到哪会到头、到那时怎么走」。它不是操作手册（日常接线约束见 `CLAUDE.md`「关键坑位」与 `AloneRedisWiringContractTest`），而是**边界记档**：把两条会随时间逼近的硬边界（库号上限、Redis 数据的持久性语义）显式写下来，避免后来者把它们当成「永远成立的现状」。
>
> 形成：2026-10-08，Sa-Token 会话迁 db9（`sa-token-alone-redis`）改造验收后记档。文中生产实测数字均注明测量时间与命令。

## 1. Redis 库号 0–15 是硬上限：还剩 6 个空位

Redis 的逻辑库编号固定为 **0–15（共 16 个）**，这是服务端常量，不是配置项。

**当前占用**（`docker-compose.yml` 实测：`REDIS_DATABASE` 共 **9 处** environment 兜底 = 9 个消费服务各一（**gateway 是其中之一**，库号 0）；`application-common.yml:52` 主库为 `${REDIS_DATABASE:0}`、`:135` 会话库硬编码 `database: 9`）：

| 库号 | 用途 |
|---|---|
| db0 | gateway（业务键，`docker-compose.yml:1039`） |
| db1–db8 | project / im / email / user / share / file / team / audit（业务键，`:365,440,512,589,660,742,819,950` 兜底序） |
| **db9** | **Sa-Token 会话（全站统一）**，`application-common.yml:135` `database: 9` |
| db10–db15 | **空闲** |

**要点**：
- 「还剩 6 个空位」**不是本次改造引入的问题**——db9 在改造前本来就空闲。但本次把「每个服务一个库 + 会话独立库」这个方案**正式化**了（9 个业务库 + 1 个会话库成为既定布局），所以现在记档：**现有方案最多再容纳 6 个新服务，第 7 个就撞墙**。
- 撞墙的顺序（谁先没库用）：先耗尽 db10–db15 的业务库空位；会话库 db9 因「全站统一、不许跟随 `REDIS_DATABASE`」而不可挪。

**撞墙后的演进方向（知道有尽头，但现在不用动）**：
1. **键前缀命名空间**取代 `SELECT` 多库：所有键统一放 db0，用 `zxyz:team:*`、`zxyz:session:*` 这类前缀区分归属。这是 Redis 官方推荐的做法，且与现有键名兼容（Sa-Token 的键前缀本就是 `satoken:`；业务缓存前缀同理）。⚠️ 注意：`sa-token.redis.prefix` / `SA_TOKEN_REDIS_PREFIX` 曾被本项目当作可用的前缀配置，**实际是死键**，已于 2026-10-10 删除（`ISSUE/52-DAILY-AUDIT-2026-10-10.md` P5）——1.46.0 的 `SaTokenConfig` 无 `redisPrefix` 字段、`SaTokenDaoForRedisTemplate.wrapKey` 默认恒等返回（javap 实证）；届时前缀化需自定义 `SaTokenDao`（覆写 `wrapKey`）或定制 `StringRedisTemplate` 的 key serializer，而非配置项。
2. **必须先改前缀、才能上集群**：Redis Cluster **不支持多库**（`SELECT` 不可用，只有 db0）——所以「上集群」和「继续分库」互斥，前缀化是上集群的**前置条件**，不是可选项。
3. 迁移路径是「先加前缀（双写或灰度）→ 再切集群」，与本会话布局无耦合；但**凡是新写的 Redis 访问代码，键名应当已经带业务前缀**，不要新增裸键名（如 `user:42` 这种），减少将来前缀化的面积。

## 2. `maxmemory 0` + `noeviction`：Redis 数据按「持久数据」对待

**实测口径**（Lead 于 2026-10-08 在生产 `160.202.46.118` 测得，命令 `docker exec zxyz-redis redis-cli CONFIG GET maxmemory maxmemory-policy`，本任务未重跑生产命令，引用其读数并注明来源）：

```
maxmemory        → 0        （不限制 Redis 自身内存）
maxmemory-policy → noeviction（写满即报错/阻塞，不淘汰任何键）
```

容器侧事实（`docker-compose.yml` 实测）：redis 服务内存限额 **256 MiB**（`deploy.resources.limits.memory`），启动命令 `redis-server --appendonly yes --requirepass "$${REDIS_PASSWORD}"`（`docker-compose.yml:271`，AOF 已开）⇒ **内存涨到 256 MiB 不会淘汰键，而是 Redis 被 OOM kill**（AOF 保证重启后数据回来）。

**与会话布局的关系**：会话集中到 db9 后，Redis 里的数据分两类——
- **业务缓存/锁（db0–db8）**：丢了可重建（缓存回源、锁重抢），用户无感；
- **会话（db9）**：**丢了用户就要全部重新登录**，是 Redis 里唯一「有用户可见损失」的数据。

⇒ 「Redis 数据按持久数据对待」（挂 AOF、`noeviction` 不淘汰、入备份策略）与「会话放在 Redis 里」是**自洽**的：正因为 noeviction 不会把会话当缓存挤掉，会话放 Redis 才安全。反过来说，**若将来有人想改 `allkeys-lru` 之类的淘汰策略，必须先回答「会话被挤掉等于全站强制下线，你能接受吗」**——默认答案是不能，所以改淘汰策略前必须先把会话挪走（按库设 maxmemory 是 per-DB 粒度，Redis 不支持，等于否定该方向）。

运维含义：Redis 内存水位要当**容量指标**监控（256 MiB 顶到就 OOM），而不是当「反正会自动淘汰」的黑盒。当前内存口径见 `docs/claude-infra.md`《维护窗口》节。

## 3. 门禁的可扩展性口径：表达不变量，不表达快照

`zxyz-common` 的 `AloneRedisWiringContractTest` 是本次接线留下的常驻门禁。它有意采用了**不变量式**断言而非「枚举快照」式，这条设计原则适用于将来所有同类门禁：

| 门禁 | 表达的不变量（永不因正常演进而变红） |
|---|---|
| A | 「**禁入名单**：audit/common/starter 这类**不该自持会话 DAO** 的模块不得声明 `sa-token-redis-template`」——名单**不可扩张**（扩张=架构决策，需评审） |
| A′（=门禁 H） | 「**全集二选一**：每个**可部署服务**（判据 = 存在 `src/main/resources/application.yml`）**必须显式二选一**——要么三件套齐全，要么登记进显式豁免名单 `NO_SESSION_SERVICE_MODULES`（当前仅 `zxyz-audit-service`）」。三条判定：① 可部署 + 三件套不全 + 不在豁免名单 ⇒ 红（**堵住 A/B/C 的空档**：新服务忘引依赖会被抓）；② 可部署 + 在豁免名单 + 却三件套齐全 ⇒ 红（**陈旧豁免**，名单不清理会变成关于现状的谎言）；③ 豁免名单含不存在/非可部署的模块名 ⇒ 红（防拼写错误让规则静默失效）。与 A 的关系：**A 管「谁不该进来」（反向），A′ 管「谁都必须在场」（全集）**。**「三件套齐全」的精确定义**：= 该模块 pom **顶层 `<dependencies>`** 里同时含那三个 artifactId（解析器先剥 XML 注释、再剔除 `dependencyManagement`/`build`/`profiles`/`reporting` 段，只认顶层依赖块）——**pom 注释里的提及不算声明**（audit-service 的禁令注释里就写着 `sa-token-alone-redis` 等名字，按「全文包含」判会把豁免模块误判成已接线）。**执行顺序**：规则 ③（豁免名单有效性）**先于**规则 ①（三件套缺失）——样本传「部分可部署集合 + 完整真实豁免名单」时会先触发 ③ 报「无效条目」，与规则 ① 的报错信息不同，勿据此误判失败原因；自检样本 15 为此刻意传空豁免名单（G1 :935-943 有注释说明） |
| B/C | 「**凡**声明 `sa-token-redis-template` 的模块，**必须**同时声明 `sa-token-alone-redis` 与 `commons-pool2`（且版本由父 pom 管、模块内不写 version）」——对任意新服务自动生效 |
| D | alone-redis 版本统一走 `${sa-token.version}`，不新增版本属性 |
| E | 共享配置里 `sa-token.alone-redis.*` 存在、`database` 恒为 **9**（写 `9` 以外的值、或写成占位符跟随主库 ⇒ 红） |
| E2 | `nacos-config/*.yml` 里**不得**出现 `alone-redis`（落点回归防护：gateway 不 import static，写进 nacos = 写方 db9/读方 db0 分裂） |
| F | 全仓**不得**出现 redisson 版 Sa-Token DAO（双 DAO 冲突） |
| G | 门禁自检：把检查逻辑喂违规/合规样本，证明它**真的会失败**且不误报 |

对照：**快照式**断言（「声明者恰好是这 9 个」）会把「新增第 10 个服务」这种正常演进判成违规，逼人「为了让测试变绿而改测试」——而改完的测试守卫已失效。这是本仓反复踩过的模式，故门禁只钉「谁不该出现」与「凡 X 必须 Y」。

**新增一个需要鉴权（登录态）的服务，必须做三件事**（少一件都是静默退化，不是编译错误）：

1. **pom 引三件套**：`sa-token-redis-template` + `sa-token-alone-redis` + `commons-pool2`，三者缺一不可——缺 pool2 是**启动失败**（`NoClassDefFoundError`，插件 `catch(Exception)` 罩不住，反编译实证见 `ALONE-REDIS-MECHANISM-2026-10-08.md`）；引 alone-redis 而不引 redis-template 则 `Class.forName` 探测失败同样静默不生效。版本一律不写在模块 pom（B/D 项钉住）。
2. **若该服务不需要会话**（纯 MQ 消费者、无 HTTP 端点），**「什么都不引」不再是可以默默完成的默认状态**——它现在是一个**必须显式声明、且会被门禁复核的决定**：A′（门禁 H）的全集不变量要求每个可部署服务显式二选一，你什么都不引且不登记 ⇒ 直接红；登记进豁免名单 `NO_SESSION_SERVICE_MODULES` 后，门禁还会持续盯两类退化——**陈旧豁免**（哪天你接了三件套却忘了从名单移除 ⇒ 红）与**无效条目**（拼错名字让规则静默失效 ⇒ 红）。为什么这条禁令存在：只引 `sa-token-core` 而不接 DAO 是「半截 Sa-Token」形态——一旦将来有人加了带 `@Log` 的 Controller（切面会调 `isLogin()`），`SaManager` 会**静默**落到 JVM 内存 DAO（`SaTokenDaoDefaultImpl`），无报错、重启即失效、与 db9 不通。`zxyz-audit-service/pom.xml:72-84` 的禁令注释是完整说明（它是当前**唯一**合法豁免：纯 MQ 消费者、无 HTTP 端点、main 代码 0 会话调用点）。
3. **会话库永远是 db9**：写死在 `application-common.yml`（E 项钉住），**不得**改成跟随 `REDIS_DATABASE`——那会让「写会话的服务」与「校验会话的 gateway」分家（gateway 不读 nacos static，见 application-common.yml:105-111 的完整推演），表现为登录成功、下一请求 401。

**库号上限与门禁的关系**：门禁 A 不限制新增服务数量（新服务按规矩接线即自动通过），但**库号上限会**——所以「新增服务」在门禁之外还有一道**人工检查**：db10–db15 还剩几个空位？用完了怎么办？答案见本文第 1 节（前缀化），提前规划，不要等启动时报「DB index is out of range」。

## 4. 部署验证的不可省略项

`sa-token-alone-redis` 1.46.0 对**配置异常是静默吞**（`setEnvironment` 的 `catch(Exception)` → `printStackTrace()` → `return`；反编译实证：异常表 `18..783 → 829 Class java/lang/Exception`，处理器偏移 829-834，见 `ALONE-REDIS-MECHANISM-2026-10-08.md` §3）。

⇒ **「服务起来了」≠「会话已迁 db9」**。配置写错（host/port/密码错、pattern 拼错等）时服务照常 healthy，只是会话仍留在各服务旧库——表现会延迟到「用户登录后下一跳 401」才暴露，且两侧日志各自看都"正常"。

⇒ 部署后**必须**实测（一次就够，见非空 `satoken*` key 即为生效）：

```bash
docker exec zxyz-redis redis-cli -n 9 --scan --pattern 'satoken*'
# 期望：登录一次后能看到键；空输出 = 会话没进 db9，按第 3 节排查落点/依赖
```

这条已写进 `CLAUDE.md`「关键坑位」与 `docs/infrastructure.md` §Redis，部署 runbook 引用其一即可。

## 相关文档

- 日常接线约束（三件套、落点、坑位）：`CLAUDE.md`「Backend Conventions / 关键坑位」
- 部署形态与内存口径：`docs/claude-infra.md`（Redis database 隔离 + 会话独立段）
- 插件机制反编译实证：`ISSUE/review-2026-10-02/ALONE-REDIS-MECHANISM-2026-10-08.md`
- 门禁本体：`ZXYZdatabaseBack/zxyz-common/src/test/java/uno/acloud/satoken/AloneRedisWiringContractTest.java`

---

## 诚实边界

- **生产 CONFIG 读数与 db10–db15 空置状态**：引用 Lead 的 2026-10-08 生产实测（`docker exec zxyz-redis redis-cli CONFIG GET ...` / `INFO keyspace`），本任务未重跑生产命令；本地无 Docker、无生产 SSH，无法自行复测。
- **「db10–db15 实测全空」**：同上，采信 Lead 读数；本任务用 `docker-compose.yml`（9 处 `REDIS_DATABASE` 兜底 = 9 个消费服务各一，gateway 是其中之一）与 `application-common.yml:135`（`database: 9`）的**静态核对**交叉印证了「已用 0–9」这一面。
- **Cluster 行为**（`SELECT` 不可用）为 Redis 官方文档口径，未在本仓实测（无集群环境）。
- **门禁引用以锚点文本为准，不写行号**：`AloneRedisWiringContractTest.java` 在持续演进（行号会漂移），本文只引用其**原则口径**（不变量/禁入名单/豁免名单）。定位方式 = 在该文件内搜索锚点标识符：设计原则与演进史 → 搜 `设计原则：表达不变量`；禁入名单 → 搜 `FORBIDDEN_REDIS_TEMPLATE_MODULES`；豁免名单 → 搜 `NO_SESSION_SERVICE_MODULES`；门禁 H 判定 → 搜 `checkDeployableServicesDeclareSessionDeps`；nacos 落点断言 → 搜 `E2_aloneRedis`；自检样本 → 搜 `G1_门禁自检`。
- **A′ 的覆盖边界（Lead 定级并要求如实记录）**：「可部署服务」的判据是 `src/main/resources/application.yml` 的**存在性** ⇒ 若新服务**忘了建该文件**、或把配置放到**非标准路径**（如 `config/` 子目录、`application-<env>.yml`），门禁 H 就**看不见它**（会被当成库模块，三件套缺失不红）。反空扫只能兜住「判据整体失效」（可部署集合为 0 ⇒ 红），**兜不住个别模块漏判**。当前 12 个模块均在标准路径、无实际风险——但这条边界要记下来，**别把 A′ 当成万能门禁**：新增服务时，「有没有 `application.yml`」要作为 checklist 的一项人工确认。
