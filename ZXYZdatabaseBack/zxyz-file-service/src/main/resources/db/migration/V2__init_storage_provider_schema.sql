-- V2__init_storage_provider_schema.sql — 存储提供者抽象层

-- ============================================================================
-- 1. file_node 表新增 storage_provider 列
-- ============================================================================

ALTER TABLE file_node
    ADD COLUMN storage_provider VARCHAR(32) NULL DEFAULT 'oss'
    COMMENT '存储提供者标识: oss, local, ...'
    AFTER uuid_name;

-- 回填现有数据
UPDATE file_node SET storage_provider = 'oss' WHERE storage_provider IS NULL;

-- ============================================================================
-- 2. file_object_ref 表新增 storage_provider 列
-- ============================================================================

ALTER TABLE file_object_ref
    ADD COLUMN storage_provider VARCHAR(32) NULL DEFAULT 'oss'
    COMMENT '存储提供者标识'
    AFTER object_key;

-- 回填现有数据
UPDATE file_object_ref SET storage_provider = 'oss' WHERE storage_provider IS NULL;

-- ============================================================================
-- 3. 存储提供者配置表
-- ============================================================================

CREATE TABLE IF NOT EXISTS storage_provider_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_id VARCHAR(32) NOT NULL COMMENT '提供者标识: oss, local',
    display_name VARCHAR(64) NOT NULL COMMENT '显示名称',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认存储',
    config_json TEXT NULL COMMENT '提供者配置（JSON）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    modify_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE INDEX uk_provider_id (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储提供者配置';

-- 种子数据：现有 OSS
INSERT INTO storage_provider_config (provider_id, display_name, enabled, is_default, config_json)
VALUES ('oss', '阿里云 OSS', 1, 1, '{}')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    enabled = VALUES(enabled),
    is_default = VALUES(is_default);
