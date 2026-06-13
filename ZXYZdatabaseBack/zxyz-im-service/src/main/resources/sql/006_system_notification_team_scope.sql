USE zxyz_im;

ALTER TABLE system_notification
    ADD COLUMN team_id BIGINT NULL AFTER business_id,
    ADD INDEX idx_system_notification_user_team_status_time (user_id, team_id, status, create_time);
