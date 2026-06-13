USE zxyz_im;

CREATE TABLE IF NOT EXISTS im_user_presence (
    user_id INT NOT NULL PRIMARY KEY,
    online TINYINT NOT NULL DEFAULT 0,
    connection_count INT NOT NULL DEFAULT 0,
    last_active_time DATETIME(3) NULL,
    update_time DATETIME(3) NOT NULL,
    INDEX idx_im_user_presence_online (online),
    INDEX idx_im_user_presence_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
