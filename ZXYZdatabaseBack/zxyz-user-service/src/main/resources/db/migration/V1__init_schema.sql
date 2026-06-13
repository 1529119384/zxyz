-- V1__init_schema.sql — 初始表结构

-- ============================================================================
-- 用户与认证域
-- ============================================================================

CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(64) NULL,
    email VARCHAR(255) NULL,
    phone VARCHAR(50) NULL,
    avatar VARCHAR(512) NULL,
    default_team_id BIGINT NULL,
    email_verified TINYINT(1) NOT NULL DEFAULT 0,
    phone_verified TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    last_login_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    INDEX idx_user_email (email),
    INDEX idx_user_phone (phone),
    INDEX idx_user_default_team (default_team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_quota (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    storage_limit BIGINT NULL COMMENT '存储上限（字节），NULL 表示不限制',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_quota_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE account_switch_trust (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_user_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ast_pair (source_user_id, target_user_id),
    INDEX idx_ast_target (target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE contact_verification_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    contact_type VARCHAR(16) NOT NULL COMMENT 'email / phone',
    code VARCHAR(16) NOT NULL,
    expire_time DATETIME(3)NOT NULL,
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cvc_user_type (user_id, contact_type),
    INDEX idx_cvc_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
