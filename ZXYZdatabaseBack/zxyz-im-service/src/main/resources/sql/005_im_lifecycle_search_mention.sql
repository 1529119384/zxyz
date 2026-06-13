USE zxyz_im;

ALTER TABLE im_message
    ADD INDEX idx_im_message_type_status (message_type, status);

ALTER TABLE system_notification
    ADD INDEX idx_system_notification_type (type);
