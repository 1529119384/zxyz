-- V4__min_password_length_align_8.sql
-- 🟢 P2-4 数据层（ISSUE/54 §2.4）：sys_config 种子与 Nacos dynamic 的 min-password-length 漂移收口
--
-- 两处实证（54 号 Lead 复核）：
--   1) admin-service V2/V3 种子值 = 6
--   2) nacos-config/zxyz-dynamic.yml app.team.min-password-length = 8（运行时实际生效值）
-- 统一基准 = 8，与全仓唯一口令策略 PasswordPolicy.MIN_LENGTH = 8
-- （zxyz-common PasswordPolicy.java:29，user-service 注册/改密的 @Size(min=8) 同源）对齐。
--
-- 为什么值取 8 而不是 6：动态侧（Nacos）一直是运行时的实际生效值 —— EnterpriseTeamService
-- 的 @Value("${app.team.min-password-length:8}") 读的是 Nacos 配置；种子 6 从未在生产生效。
-- 把种子提到 8 是「让声明的事实与运行的事实一致」，不是行为变更。
--
-- ⚠️ 历史包袱（V2 文件头有 CHECKSUM MISMATCH NOTICE）：本环境若曾应用旧版 V2，
--    Flyway 校验和只记录 V2/V3 当时的内容 —— 本次修改 V2/V3 会让「已应用旧版」的库
--    出现 checksum mismatch，需要一次 flyway repair（deploy-fast.sh --repair-flyway）。
--    本迁移则保证：即使不做 repair，已运行库的该键值也会被对齐到 8。
--
-- 全新库不受影响：V1→V2→V3→V4 顺序执行，V2/V3 已种 8，本迁移幂等。

UPDATE `sys_config`
SET `config_value` = '8',
    `default_value` = '8'
WHERE `config_key` = 'app.team.min-password-length'
  AND `config_value` <> '8';
