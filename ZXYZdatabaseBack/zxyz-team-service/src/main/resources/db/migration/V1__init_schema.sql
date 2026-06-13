-- V1__init_schema.sql — 初始表结构

-- ============================================================================
-- 1. 团队域
-- ============================================================================

CREATE TABLE team (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    avatar VARCHAR(512) NULL,
    description VARCHAR(500) NULL,
    owner_user_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_team_owner (owner_user_id),
    INDEX idx_team_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL COMMENT '团队角色代码',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用，2-已移除',
    join_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    personal_storage_limit BIGINT NULL COMMENT '个人存储上限（字节），NULL 表示不限制',
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_member_user (team_id, user_id),
    INDEX idx_tm_user_status (user_id, status),
    INDEX idx_tm_team_status (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_quota (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    member_limit INT NULL COMMENT '成员上限，NULL 表示不限制',
    storage_limit BIGINT NULL COMMENT '存储上限（字节），NULL 表示不限制',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_quota_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 2. 系统权限与审计域
-- ============================================================================

CREATE TABLE permission (
    id INT NOT NULL AUTO_INCREMENT,
    permission_name VARCHAR(64) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role (
    id INT NOT NULL AUTO_INCREMENT,
    role_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_role (
    id INT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id INT NOT NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    INDEX idx_user_role_user (user_id),
    INDEX idx_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role_permission (
    id INT NOT NULL AUTO_INCREMENT,
    role_id INT NOT NULL,
    permission_id INT NOT NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    INDEX idx_role_permission_role (role_id),
    INDEX idx_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE permission_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operator_id BIGINT NULL,
    scope_type VARCHAR(32) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    before_value TEXT NULL,
    after_value TEXT NULL,
    operation_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(64) NULL,
    PRIMARY KEY (id),
    INDEX idx_pa_scope_time (scope_type, operation_time),
    INDEX idx_pa_operator (operator_id, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 3. 团队级权限
-- ============================================================================

CREATE TABLE team_permission (
    id INT NOT NULL AUTO_INCREMENT,
    permission_name VARCHAR(64) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tp_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    builtin TINYINT NOT NULL DEFAULT 0 COMMENT '0-自定义，1-内置角色（owner/admin/member）',
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tr_code (team_id, role_code),
    INDEX idx_tr_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_member_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    team_role_id BIGINT NOT NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tmr_user (team_id, user_id),
    INDEX idx_tmr_role (team_role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_role_permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    team_role_id BIGINT NOT NULL,
    permission_id INT NOT NULL,
    create_time DATETIME(3)NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trp (team_id, team_role_id, permission_id),
    INDEX idx_trp_role (team_role_id),
    INDEX idx_trp_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
