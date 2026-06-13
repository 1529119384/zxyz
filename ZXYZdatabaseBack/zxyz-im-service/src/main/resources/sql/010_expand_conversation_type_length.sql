USE zxyz_im;

DELIMITER $$

DROP PROCEDURE IF EXISTS expand_im_conversation_type_length $$
CREATE PROCEDURE expand_im_conversation_type_length()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_conversation'
          AND COLUMN_NAME = 'type'
          AND CHARACTER_MAXIMUM_LENGTH < 32
    ) THEN
        -- TEAM_NOTIFICATION 长度为 17，旧 VARCHAR(16) 会导致团队消息会话写入失败。
        ALTER TABLE im_conversation MODIFY type VARCHAR(32) NOT NULL;
    END IF;
END $$

DELIMITER ;

CALL expand_im_conversation_type_length();

DROP PROCEDURE IF EXISTS expand_im_conversation_type_length;
