-- ============================================================================
-- zxyz-email-service 邮件服务数据库结构 v1.0
-- 指绣云章邮件服务数据库
--
-- 时间戳命名约定（m44）：
--   本数据库所有表统一使用 'create_time' / 'update_time'。
--
-- 软删除约定（m45）：
--   本数据库不使用软删除。邮件记录通过 'status VARCHAR(24)' 管理发送状态。
--   email_template 通过 'status TINYINT'（0-启用，1-停用）控制启停。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS zxyz_email
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE zxyz_email;

SET NAMES utf8mb4;

CREATE TABLE email_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient VARCHAR(255) NOT NULL COMMENT '收件人邮箱',
    subject VARCHAR(255) NOT NULL COMMENT '邮件主题',
    content_html TEXT NOT NULL COMMENT 'HTML 内容（m47: 使用 TEXT，64KB 足够存储验证邮件和系统通知；如需发送超大 HTML 邮件，可改回 MEDIUMTEXT）',
    status VARCHAR(24) NOT NULL COMMENT 'PENDING-待发送，SENDING-发送中，SENT-已发送，FAILED-发送失败',
    failure_reason VARCHAR(1024) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 4,
    next_retry_time DATETIME(3) NULL,
    scheduled_time DATETIME(3) NULL,
    sent_time DATETIME(3) NULL,
    server_config_id BIGINT NULL,
    server_config_name VARCHAR(64) NULL,
    sender_username VARCHAR(255) NULL,
    business_type VARCHAR(64) NULL,
    business_id VARCHAR(64) NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_email_record_status_retry (status, next_retry_time),
    INDEX idx_email_record_scheduled (status, scheduled_time),
    INDEX idx_email_record_recipient_time (recipient, create_time),
    INDEX idx_email_record_sender (server_config_id),
    INDEX idx_email_record_business (business_type, business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_code VARCHAR(64) NOT NULL,
    subject_template VARCHAR(255) NOT NULL,
    content_html TEXT NOT NULL COMMENT 'm47: 模板内容使用 TEXT 即可，64KB 足够',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-启用，1-停用',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE verify_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    scene VARCHAR(32) NOT NULL,
    code VARCHAR(16) NOT NULL,
    expire_time DATETIME(3) NOT NULL,
    used TINYINT(1) NOT NULL DEFAULT 0,
    used_time DATETIME(3) NULL,
    request_ip VARCHAR(64) NULL,
    email_record_id BIGINT NULL,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verify_code_email_scene (email, scene),
    INDEX idx_verify_code_expire (expire_time),
    INDEX idx_verify_code_ip_time (request_ip, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_server_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_name VARCHAR(64) NOT NULL COMMENT '配置名称',
    host VARCHAR(255) NOT NULL COMMENT 'SMTP 主机',
    port INT NOT NULL COMMENT 'SMTP 端口',
    username VARCHAR(255) NOT NULL COMMENT 'SMTP 账号',
    password_cipher TEXT NOT NULL COMMENT '加密后的 SMTP 授权码',
    from_address VARCHAR(255) NULL COMMENT '发件人地址',
    transport_strategy VARCHAR(32) NOT NULL DEFAULT 'SMTP_TLS' COMMENT 'Simple Java Mail 传输策略',
    active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否当前启用',
    last_test_status VARCHAR(24) NULL COMMENT '最近一次连通性测试状态',
    last_test_time DATETIME(3) NULL COMMENT '最近一次连通性测试时间',
    last_test_message VARCHAR(1024) NULL COMMENT '最近一次连通性测试结果',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_server_config_name (config_name),
    INDEX idx_email_server_config_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO email_template(template_code, subject_template, content_html, status, create_time, update_time)
VALUES
    ('EMAIL_BIND_CODE', '邮箱验证码', '<h2>邮箱验证</h2><p>你的验证码是：<strong>{{code}}</strong></p><p>验证码 {{expireMinutes}} 分钟内有效，请勿转发给他人。</p>', 0, NOW(3), NOW(3)),
    ('SYSTEM_MESSAGE', '{{title}}', '<h2>{{title}}</h2><p>{{content}}</p>', 0, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
    subject_template = VALUES(subject_template),
    content_html = VALUES(content_html),
    status = VALUES(status),
    update_time = VALUES(update_time);
