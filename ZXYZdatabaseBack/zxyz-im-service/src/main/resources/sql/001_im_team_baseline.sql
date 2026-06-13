CREATE DATABASE IF NOT EXISTS zxyz_im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE zxyz_im;

CREATE TABLE IF NOT EXISTS im_user_profile (
    user_id INT NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    name VARCHAR(64) NULL,
    email VARCHAR(128) NULL,
    avatar VARCHAR(512) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    INDEX idx_im_user_profile_username (username),
    INDEX idx_im_user_profile_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS im_team (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    avatar VARCHAR(512) NULL,
    owner_user_id INT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    INDEX idx_im_team_owner (owner_user_id),
    INDEX idx_im_team_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    join_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_team_member_user (team_id, user_id),
    INDEX idx_team_member_user (user_id, status),
    INDEX idx_team_member_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_invitation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    invitee_user_id INT NOT NULL,
    inviter_user_id INT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    expire_time DATETIME(3) NOT NULL,
    handle_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    INDEX idx_team_invitation_invitee (invitee_user_id, status),
    INDEX idx_team_invitation_team_invitee (team_id, invitee_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS im_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    team_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_im_conversation_team (type, team_id),
    INDEX idx_im_conversation_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS im_conversation_member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    unread_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_im_conversation_member (conversation_id, user_id),
    INDEX idx_im_conversation_member_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS im_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    sender_user_id INT NULL,
    message_type VARCHAR(24) NOT NULL,
    content JSON NULL,
    status TINYINT NOT NULL DEFAULT 0,
    client_message_id VARCHAR(64) NULL,
    create_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_im_message_client (conversation_id, sender_user_id, client_message_id),
    INDEX idx_im_message_conversation (conversation_id, id),
    INDEX idx_im_message_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS system_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    business_type VARCHAR(64) NULL,
    business_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    read_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    INDEX idx_system_notification_user_status (user_id, status),
    INDEX idx_system_notification_user_time (user_id, create_time),
    INDEX idx_system_notification_business (business_type, business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
