-- V1__init_config_schema.sql — zxyz_config 初始表结构

-- ============================================================================
-- 运行时配置域
-- ============================================================================

CREATE TABLE `sys_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `config_key` VARCHAR(128) NOT NULL COMMENT '配置键',
    `config_value` TEXT COMMENT '配置值',
    `config_type` VARCHAR(32) NOT NULL COMMENT '类型：SYSTEM/FEATURE/SECURITY',
    `value_type` VARCHAR(16) NOT NULL DEFAULT 'STRING' COMMENT '值类型：STRING/NUMBER/BOOLEAN/JSON',
    `description` VARCHAR(256) COMMENT '说明',
    `is_encrypted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否加密存储',
    `is_editable` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否可在 Admin UI 编辑',
    `default_value` TEXT COMMENT '默认值',
    `validation_rule` VARCHAR(256) COMMENT '校验规则（正则或枚举）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统运行时配置';

-- 配置变更审计表
CREATE TABLE `sys_config_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `config_key` VARCHAR(128) NOT NULL,
    `old_value` TEXT,
    `new_value` TEXT,
    `changed_by` BIGINT NOT NULL COMMENT '操作人 ID',
    `changed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置变更审计';

-- ============================================================================
-- 初始配置数据
-- ============================================================================

INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `value_type`, `description`, `default_value`) VALUES
-- 系统配置
('app.share.frontend-base-url', 'http://localhost:5173', 'SYSTEM', 'STRING', '分享链接前端地址（ShareProperties 消费）', 'http://localhost:5173'),
('app.cors.allowed-origins', 'http://localhost:5173,http://localhost:4173', 'SYSTEM', 'STRING', 'CORS 允许来源（逗号分隔）', 'http://localhost:5173'),
('app.auth.max-age', '2592000', 'SYSTEM', 'NUMBER', 'Cookie 最大存活时间（秒）', '2592000'),
('app.auth.secure', 'true', 'SYSTEM', 'BOOLEAN', 'Cookie 是否仅 HTTPS', 'true'),
('app.auth.domain', '', 'SYSTEM', 'STRING', 'Cookie 域名', ''),

-- 功能开关（Phase 2 运行时配置，通过 ConfigServiceClient.get() 读取）
('app.performance.time-aspect-enabled', 'true', 'FEATURE', 'BOOLEAN', '是否启用性能切面日志（默认启用，matchIfMissing=true，需显式设为 false 才禁用）', 'true'),
('app.database-maintenance.enabled', 'false', 'FEATURE', 'BOOLEAN', '是否启用数据库维护功能（此功能尚未实现，仅为预留）', 'false'),

-- 业务参数
('app.email.verify-code.cooldown-seconds', '60', 'SYSTEM', 'NUMBER', '验证码冷却时间（秒）', '60');
