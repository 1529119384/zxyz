-- ============================================================================
-- zxyz_share 数据库 Schema
-- 分享服务独立数据库
--
-- 时间戳命名约定（m44）：
--   本数据库所有表统一使用 'create_time' / 'update_time'。
--
-- 软删除约定（m45）：
--   share 使用 'status INT'（0-正常，1-已取消，2-已过期，3-达访问上限）管理生命周期。
--   新表如需软删除，建议统一使用 'deleted TINYINT DEFAULT 0'。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS zxyz_share
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE zxyz_share;

-- ============================================================================
-- 分享域
-- ============================================================================

CREATE TABLE share (
    id BIGINT NOT NULL AUTO_INCREMENT,
    share_key VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NULL,
    expire_time DATETIME(3) NULL COMMENT 'NULL 表示永久有效',
    max_access_count INT NULL COMMENT 'NULL 表示不限次数',
    current_access_count INT NOT NULL DEFAULT 0,
    status INT NOT NULL DEFAULT 0 COMMENT '0-正常，1-已取消，2-已过期，3-达访问上限',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_key (share_key),
    INDEX idx_share_user_id (user_id),
    INDEX idx_share_status (status),
    INDEX idx_share_create_time (create_time),
    INDEX idx_share_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE share_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    share_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    file_type TINYINT NOT NULL COMMENT '0-文件夹，1-文件',
    create_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_item_share_file (share_id, file_id),
    INDEX idx_share_item_share_id (share_id),
    INDEX idx_share_item_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
