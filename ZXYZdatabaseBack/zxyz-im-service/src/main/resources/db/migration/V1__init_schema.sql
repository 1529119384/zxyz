-- V1__init_schema.sql — 初始表结构

-- ============================================================================
-- 1. 用户投影与在线状态
-- ============================================================================

CREATE TABLE im_user_profile (
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    name VARCHAR(64) NULL,
    avatar VARCHAR(512) NULL,
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (user_id),
    INDEX idx_iup_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE im_user_presence (
    user_id BIGINT NOT NULL,
    online TINYINT NOT NULL DEFAULT 0 COMMENT '0-离线，1-在线',
    connection_count INT NOT NULL DEFAULT 0,
    last_active_time DATETIME(3)NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (user_id),
    INDEX idx_iup_online (online),
    INDEX idx_iup_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 2. 团队投影与协作
-- ============================================================================

CREATE TABLE im_team (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    avatar VARCHAR(512) NULL,
    description VARCHAR(500) NULL,
    owner_user_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_it_owner (owner_user_id),
    INDEX idx_it_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL COMMENT '团队角色代码',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用，2-已移除',
    join_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tm_user (team_id, user_id),
    INDEX idx_tm_user (user_id, status),
    INDEX idx_tm_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_invitation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    invitee_user_id BIGINT NOT NULL,
    inviter_user_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理，1-已接受，2-已拒绝，3-已过期',
    expire_time DATETIME(3)NOT NULL,
    handle_time DATETIME(3)NULL,
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ti_invitee (invitee_user_id, status),
    INDEX idx_ti_team_invitee (team_id, invitee_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_invite_link (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    token VARCHAR(96) NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    expire_time DATETIME(3)NOT NULL,
    max_uses INT NOT NULL DEFAULT 0 COMMENT '0 表示不限次数',
    used_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-失效',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_til_token (token),
    INDEX idx_til_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_join_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    link_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审，1-通过，2-拒绝',
    audit_by_user_id BIGINT NULL,
    audit_time DATETIME(3)NULL,
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_tjr_team (team_id, status),
    INDEX idx_tjr_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_mute (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    muted_by_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NULL,
    expire_time DATETIME(3)NULL COMMENT 'NULL 表示永久禁言',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-生效中，1-已解除',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tmute_user (team_id, user_id),
    INDEX idx_tmute_team (team_id, status),
    INDEX idx_tmute_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 3. 会话与消息
-- ============================================================================

CREATE TABLE im_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type VARCHAR(32) NOT NULL COMMENT 'TEAM / DIRECT / SYSTEM / TEAM_NOTIFICATION / PROJECT',
    team_id BIGINT NULL,
    project_id BIGINT NULL,
    name VARCHAR(80) NULL,
    direct_user_a BIGINT NULL COMMENT '私聊用户 A（ID 较小者）',
    direct_user_b BIGINT NULL COMMENT '私聊用户 B',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-已解散',
    read_only TINYINT NOT NULL DEFAULT 0 COMMENT '0-可读写，1-只读',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务唯一键，如 TEAM:1、DIRECT:3:5',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ic_biz_key (biz_key),
    INDEX idx_ic_team (team_id, status),
    INDEX idx_ic_project (project_id, status),
    INDEX idx_ic_direct_users (direct_user_a, direct_user_b, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE im_conversation_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    unread_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-已退出',
    create_time DATETIME(3)NOT NULL,
    update_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_icm_member (conversation_id, user_id),
    INDEX idx_icm_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE im_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_user_id BIGINT NULL COMMENT 'NULL 表示系统消息',
    message_type VARCHAR(24) NOT NULL COMMENT 'TEXT / IMAGE / FILE / FILE_CARD / SYSTEM / ANNOUNCEMENT',
    content JSON NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-已撤回',
    recall_by_user_id BIGINT NULL,
    recall_time DATETIME(3)NULL,
    recall_reason VARCHAR(500) NULL,
    client_message_id VARCHAR(64) NULL COMMENT '客户端消息去重 ID',
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_client (conversation_id, sender_user_id, client_message_id),
    INDEX idx_im_conversation (conversation_id, id),
    INDEX idx_im_create_time (create_time),
    INDEX idx_im_type_status (message_type, status),
    INDEX idx_im_sender_user_id (sender_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 4. 系统通知
-- ============================================================================

CREATE TABLE system_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL COMMENT 'SYSTEM / TEAM / PROJECT / IM',
    title VARCHAR(120) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    business_type VARCHAR(64) NULL,
    business_id BIGINT NULL,
    team_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-未读，1-已读',
    read_time DATETIME(3)NULL,
    create_time DATETIME(3)NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_sn_user_status (user_id, status),
    INDEX idx_sn_user_time (user_id, create_time),
    INDEX idx_sn_business (business_type, business_id),
    INDEX idx_sn_type (type),
    INDEX idx_sn_user_team_status_time (user_id, team_id, status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 团队级权限（RBAC）已移至 zxyz_team 数据库，由 team-service 统一管理
-- ============================================================================
