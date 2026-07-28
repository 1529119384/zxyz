-- V3__fix_config_keys.sql
-- 修复 V2 checksum mismatch 后，确保 sys_config 中所有配置键值与当前 V2 定义一致。
--
-- 背景：V2 在 commit 9883823 中被修改（新增 app.email.verify-code-cooldown-seconds），
-- 导致已运行原始 V2 的数据库出现 checksum mismatch。修复方式为：
--   1. 对受影响环境执行 flyway repair 更新 V2 校验
--   2. 本迁移使用 INSERT ... ON DUPLICATE KEY UPDATE，确保：
--      - 缺失的行被创建（如 verify-code-cooldown-seconds）
--      - 已有的行被修正到目标值
--
-- 适用于已应用原始 V2 的环境，也适用于全新初始化（V1→V2→V3 均正常执行）。

-- ============================================================================
-- 阶段一：文件上传 + 限流 + 团队默认值
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.file.upload.allowed-extensions', '["pdf","doc","docx","xls","xlsx","ppt","pptx","jpg","jpeg","png","gif","svg","webp","mp4","mp3","zip","rar","7z","txt","md","json","csv"]', 'FEATURE', 'JSON', '允许上传的文件扩展名白名单（JSON 数组）', '["pdf","doc","docx","xls","xlsx","ppt","pptx","jpg","jpeg","png","gif","svg","webp","mp4","mp3","zip","rar","7z","txt","md","json","csv"]', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.file.upload.blocked-extensions', '[".exe",".bat",".cmd",".sh",".js",".jsp",".php",".py",".rb",".msi",".dmg",".pkg",".deb",".rpm",".app",".scr",".vbs",".ps1"]', 'SECURITY', 'JSON', '危险文件扩展名黑名单（带点前缀，优先级高于白名单）', '[".exe",".bat",".cmd",".sh",".js",".jsp",".php",".py",".rb",".msi",".dmg",".pkg",".deb",".rpm",".app",".scr",".vbs",".ps1"]', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.file.upload.max-size-bytes', '524288000', 'SYSTEM', 'NUMBER', '单文件最大上传大小（字节，默认 500MB）', '524288000', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.rate-limit.login.ip-per-minute', '20', 'SYSTEM', 'NUMBER', '每分钟每 IP 登录上限', '20', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.rate-limit.login.username-per-minute', '5', 'SYSTEM', 'NUMBER', '每分钟每用户名登录上限', '5', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.rate-limit.register.ip-per-hour', '3', 'SYSTEM', 'NUMBER', '每小时每 IP 注册上限', '3', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.rate-limit.share.attempts-per-window', '10', 'SYSTEM', 'NUMBER', '分享验证最大尝试次数', '10', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.rate-limit.share.window-minutes', '5', 'SYSTEM', 'NUMBER', '分享验证限流窗口（分钟）', '5', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.team.default-max-members', '100', 'SYSTEM', 'NUMBER', '团队默认成员上限', '100', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.team.default-storage-limit-bytes', '107374182400', 'SYSTEM', 'NUMBER', '团队默认存储上限（字节，默认 100GB）', '107374182400', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.team.min-password-length', '6', 'SECURITY', 'NUMBER', '团队密码最小长度', '6', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

-- ============================================================================
-- 阶段二：缓存 TTL + REST 客户端超时 + Resilience4j 熔断/重试
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.cache.default-ttl-minutes', '30', 'FEATURE', 'NUMBER', '全局缓存默认 TTL（分钟）', '30', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.cache.team-permission-ttl-minutes', '5', 'FEATURE', 'NUMBER', '团队权限缓存 TTL（分钟）', '5', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.cache.project-access-ttl-minutes', '10', 'FEATURE', 'NUMBER', '项目访问缓存 TTL（分钟）', '10', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.cache.storage-usage-ttl-seconds', '30', 'FEATURE', 'NUMBER', '存储用量缓存 TTL（秒）', '30', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

-- ============================================================================
-- 阶段三：IM + 邮件 + 文件 + 审计 + 头像
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.im.message.max-text-length', '5000', 'FEATURE', 'NUMBER', 'IM 消息最大文本长度（字符）', '5000', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.im.message.recall-window-seconds', '120', 'FEATURE', 'NUMBER', '消息可撤回时间窗口（秒）', '120', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.im.ws.ticket-ttl-seconds', '30', 'SYSTEM', 'NUMBER', 'WebSocket 票据有效期（秒）', '30', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.im.ws.max-content-length', '65536', 'SYSTEM', 'NUMBER', 'WebSocket 最大消息长度（字节）', '65536', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.email.max-retry-count', '4', 'SYSTEM', 'NUMBER', '邮件最大重试次数', '4', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.email.verify-code.cooldown-seconds', '60', 'SYSTEM', 'NUMBER', '邮箱验证码冷却时间（秒）', '60', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.file.copy.max-nodes-per-tx', '500', 'FEATURE', 'NUMBER', '单次复制最大节点数', '500', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.audit.retention-days', '90', 'SYSTEM', 'NUMBER', '审计日志保留天数', '90', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.avatar.max-size-bytes', '5242880', 'SYSTEM', 'NUMBER', '头像最大文件大小（字节，默认 5MB）', '5242880', 1)
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `config_type` = VALUES(`config_type`),
    `value_type` = VALUES(`value_type`),
    `description` = VALUES(`description`),
    `default_value` = VALUES(`default_value`),
    `is_editable` = VALUES(`is_editable`);
