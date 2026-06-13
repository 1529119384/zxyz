USE zxyz_im;

CREATE TABLE IF NOT EXISTS team_mute (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    muted_by_user_id INT NOT NULL,
    reason VARCHAR(500) NULL,
    expire_time DATETIME(3) NULL,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_team_mute_user (team_id, user_id),
    INDEX idx_team_mute_team (team_id, status),
    INDEX idx_team_mute_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_invite_link (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    token VARCHAR(96) NOT NULL,
    created_by_user_id INT NOT NULL,
    expire_time DATETIME(3) NOT NULL,
    max_uses INT NOT NULL DEFAULT 0,
    used_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_team_invite_link_token (token),
    INDEX idx_team_invite_link_team (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_join_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    link_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    audit_by_user_id INT NULL,
    audit_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL,
    UNIQUE KEY uk_team_join_request_pending (team_id, user_id, status),
    INDEX idx_team_join_request_team (team_id, status),
    INDEX idx_team_join_request_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE im_message
    ADD COLUMN recall_by_user_id INT NULL AFTER status,
    ADD COLUMN recall_time DATETIME(3) NULL AFTER recall_by_user_id,
    ADD COLUMN recall_reason VARCHAR(500) NULL AFTER recall_time;
