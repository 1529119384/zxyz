-- ============================================================================
-- zxyz-project-service database schema v1.0
-- 指绣云章项目服务数据库
--
-- 时间戳命名约定（m44）：
--   本数据库所有表统一使用 'create_time' / 'update_time'。
--
-- 软删除约定（m45）：
--   本数据库不使用软删除，项目通过 'status TINYINT'（0-正常，1-归档，2-禁用）管理生命周期。
--   project_create_request 通过 'status TINYINT'（0-待审，1-通过，2-拒绝）管理审批状态。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS zxyz_project
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE zxyz_project;

SET NAMES utf8mb4;

-- ============================================================================
-- 项目域
-- ============================================================================

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(500) NULL,
    leader_user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL COMMENT '关联 IM 会话 ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-归档，2-禁用',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_project_team_status (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL COMMENT 'leader-负责人，member-成员',
    join_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_member (project_id, user_id),
    INDEX idx_pm_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_quota (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    storage_limit BIGINT NULL COMMENT '存储上限（字节），NULL 表示不限制',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_quota_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_create_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    project_name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NULL,
    leader_user_id BIGINT NOT NULL,
    storage_limit BIGINT NULL COMMENT '申请配额（字节），NULL 表示不限制',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审，1-通过，2-拒绝',
    reviewer_user_id BIGINT NULL,
    review_time DATETIME(3) NULL,
    review_reason VARCHAR(500) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_pcr_team_status (team_id, status),
    INDEX idx_pcr_requester (requester_user_id),
    INDEX idx_pcr_leader (leader_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- operate_log 已迁移至独立的 zxyz_audit 数据库，由 zxyz-audit-service 统一管理

-- ============================================================================
-- 外键约束（m46，同库关系）
-- ============================================================================

-- project_member.project_id → project.id
ALTER TABLE project_member
    ADD CONSTRAINT fk_project_member_project
    FOREIGN KEY (project_id) REFERENCES project (id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- project_quota.project_id → project.id
ALTER TABLE project_quota
    ADD CONSTRAINT fk_project_quota_project
    FOREIGN KEY (project_id) REFERENCES project (id)
    ON DELETE CASCADE ON UPDATE CASCADE;
