-- V2__hot_config_keys.sql — 热配置系统新增 ~25 个配置键
-- 对应 ISSUE #13 阶段一~三（阶段一：文件上传 + 限流 + 团队；阶段二：缓存 + REST + Resilience4j；阶段三：IM + 邮件 + 文件 + 审计 + 头像）

-- ============================================================================
-- 阶段一：文件上传 + 限流 + 团队默认值
-- ============================================================================

-- 文件上传扩展名配置
INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
('app.file.upload.allowed-extensions', '["pdf","doc","docx","xls","xlsx","ppt","pptx","jpg","jpeg","png","gif","svg","webp","mp4","mp3","zip","rar","7z","txt","md","json","csv"]', 'FEATURE', 'JSON', '允许上传的文件扩展名白名单（JSON 数组）', '["pdf","doc","docx","xls","xlsx","ppt","pptx","jpg","jpeg","png","gif","svg","webp","mp4","mp3","zip","rar","7z","txt","md","json","csv"]', 1),
('app.file.upload.blocked-extensions', '[".exe",".bat",".cmd",".sh",".js",".jsp",".php",".py",".rb",".msi",".dmg",".pkg",".deb",".rpm",".app",".scr",".vbs",".ps1"]', 'SECURITY', 'JSON', '危险文件扩展名黑名单（带点前缀，优先级高于白名单）', '[".exe",".bat",".cmd",".sh",".js",".jsp",".php",".py",".rb",".msi",".dmg",".pkg",".deb",".rpm",".app",".scr",".vbs",".ps1"]', 1),
('app.file.upload.max-size-bytes', '524288000', 'SYSTEM', 'NUMBER', '单文件最大上传大小（字节，默认 500MB）', '524288000', 1),

-- 速率限制阈值
('app.rate-limit.login.ip-per-minute', '20', 'SYSTEM', 'NUMBER', '每分钟每 IP 登录上限', '20', 1),
('app.rate-limit.login.username-per-minute', '5', 'SYSTEM', 'NUMBER', '每分钟每用户名登录上限', '5', 1),
('app.rate-limit.register.ip-per-hour', '3', 'SYSTEM', 'NUMBER', '每小时每 IP 注册上限', '3', 1),
('app.rate-limit.share.attempts-per-window', '10', 'SYSTEM', 'NUMBER', '分享验证最大尝试次数', '10', 1),
('app.rate-limit.share.window-minutes', '5', 'SYSTEM', 'NUMBER', '分享验证限流窗口（分钟）', '5', 1),

-- 团队默认配额
('app.team.default-max-members', '100', 'SYSTEM', 'NUMBER', '团队默认成员上限', '100', 1),
('app.team.default-storage-limit-bytes', '107374182400', 'SYSTEM', 'NUMBER', '团队默认存储上限（字节，默认 100GB）', '107374182400', 1),
('app.team.min-password-length', '6', 'SECURITY', 'NUMBER', '团队密码最小长度', '6', 1);

-- ============================================================================
-- 阶段二：缓存 TTL + REST 客户端超时 + Resilience4j 熔断/重试
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
-- 缓存 TTL
('app.cache.default-ttl-minutes', '30', 'FEATURE', 'NUMBER', '全局缓存默认 TTL（分钟）', '30', 1),
('app.cache.team-permission-ttl-minutes', '5', 'FEATURE', 'NUMBER', '团队权限缓存 TTL（分钟）', '5', 1),
('app.cache.project-access-ttl-minutes', '10', 'FEATURE', 'NUMBER', '项目访问缓存 TTL（分钟）', '10', 1),
('app.cache.storage-usage-ttl-seconds', '30', 'FEATURE', 'NUMBER', '存储用量缓存 TTL（秒）', '30', 1),

-- REST 客户端超时
('app.rest-client.connect-timeout-ms', '3000', 'SYSTEM', 'NUMBER', '服务间调用连接超时（毫秒）', '3000', 1),
('app.rest-client.read-timeout-ms', '10000', 'SYSTEM', 'NUMBER', '服务间调用读取超时（毫秒）', '10000', 1),

-- Resilience4j 熔断/重试
('app.resilience.retry.max-attempts', '3', 'FEATURE', 'NUMBER', '最大重试次数', '3', 1),
('app.resilience.retry.wait-duration-ms', '500', 'FEATURE', 'NUMBER', '重试间隔（毫秒）', '500', 1),
('app.resilience.circuit-breaker.sliding-window', '10', 'FEATURE', 'NUMBER', '熔断滑动窗口大小', '10', 1),
('app.resilience.circuit-breaker.failure-threshold', '50', 'FEATURE', 'NUMBER', '熔断失败率阈值（百分比）', '50', 1),
('app.resilience.circuit-breaker.wait-open-ms', '30000', 'FEATURE', 'NUMBER', '熔断半开等待时间（毫秒）', '30000', 1);

-- ============================================================================
-- 阶段三：IM + 邮件 + 文件 + 审计 + 头像
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`, `is_editable`) VALUES
-- IM 消息设置
('app.im.message.max-text-length', '5000', 'FEATURE', 'NUMBER', 'IM 消息最大文本长度（字符）', '5000', 1),
('app.im.message.recall-window-seconds', '120', 'FEATURE', 'NUMBER', '消息可撤回时间窗口（秒）', '120', 1),
('app.im.ws.ticket-ttl-seconds', '30', 'SYSTEM', 'NUMBER', 'WebSocket 票据有效期（秒）', '30', 1),
('app.im.ws.max-content-length', '65536', 'SYSTEM', 'NUMBER', 'WebSocket 最大消息长度（字节）', '65536', 1),

-- 邮件设置
('app.email.max-retry-count', '4', 'SYSTEM', 'NUMBER', '邮件最大重试次数', '4', 1),

-- 文件复制 + 审计 + 头像
('app.file.copy.max-nodes-per-tx', '500', 'FEATURE', 'NUMBER', '单次复制最大节点数', '500', 1),
('app.audit.retention-days', '90', 'SYSTEM', 'NUMBER', '审计日志保留天数', '90', 1),
('app.avatar.max-size-bytes', '5242880', 'SYSTEM', 'NUMBER', '头像最大文件大小（字节，默认 5MB）', '5242880', 1);
