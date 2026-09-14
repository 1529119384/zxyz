-- =============================================================================
-- ZXYZ 最小权限数据库账户授权模板（TEMPLATE —— 由脚本渲染后执行，勿手工直接跑）
-- =============================================================================
-- 背景（缺陷 U1）：现网所有服务与 Nacos 都用 MySQL root 直连，任一服务被攻破即
-- 等于拿到全部库的 DBA 权限。本模板为「单个库」创建仅限本库的专属账户：
--   权限 = 单库 DML + 单库 DDL（Flyway 迁移需要 CREATE/ALTER/DROP/INDEX）。
--
-- 渲染方式：scripts/grant-least-privilege.sh 读取 .env 里每个库的
--   <PREFIX>_DB_USERNAME / <PREFIX>_DB_PASSWORD，对本模板中的三个占位符做
--   全局替换后，逐库执行（共渲染 10 次：9 个业务库 + nacos）。
--
-- 占位符（脚本按库替换；为避免被误替换，本注释不书写字面占位符）：
--   数据库名、账户名、账户密码、账户来源网段。
--
-- 🔒【为什么必须限定网段】账户 host 曾写死成通配，等于网络上任意来源都能用该
--   账户连库。2026-09-15 维护窗口已把 10 个账户收窄到 docker-compose.yml 钉死
--   的子网（172.19.0.0/16 ⇒ host '172.19.%'），可用 DB_ACCOUNT_HOST 覆盖。
--   ⚠️ 本模板**每次部署都会重跑**（见 deploy-on-server.sh 的 GRANT_SCRIPT 步骤），
--     若这里仍写通配，收窄会被下次部署静默撤销 ⇒ 模板内显式带一条 DROP 通配
--     行的语句，保证幂等收敛到网段。
--
-- 幂等：CREATE USER IF NOT EXISTS 重复执行安全；重复 GRANT 相同权限安全；
--   DROP USER IF EXISTS 无匹配时无害；FLUSH PRIVILEGES 无害。
--   本模板不强制改密，避免重跑破坏已部署账户密码；如需轮换密码请另行 ALTER USER。
-- =============================================================================

-- 先清掉历史遗留的通配符账户，否则收窄会被下次部署撤销
DROP USER IF EXISTS '__USER__'@'%';
CREATE USER IF NOT EXISTS '__USER__'@'__HOST__' IDENTIFIED BY '__PASSWORD__';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON `__DB__`.* TO '__USER__'@'__HOST__';
FLUSH PRIVILEGES;
