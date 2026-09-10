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
--   数据库名、账户名、账户密码。
--
-- 幂等：CREATE USER IF NOT EXISTS 重复执行安全；重复 GRANT 相同权限安全；
--   FLUSH PRIVILEGES 无害。本模板不强制改密，避免重跑破坏已部署账户密码；
--   如需轮换密码请另行 ALTER USER。
-- =============================================================================

CREATE USER IF NOT EXISTS '__USER__'@'%' IDENTIFIED BY '__PASSWORD__';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON `__DB__`.* TO '__USER__'@'%';
FLUSH PRIVILEGES;
