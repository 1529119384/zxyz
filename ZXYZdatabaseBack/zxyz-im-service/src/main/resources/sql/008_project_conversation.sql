USE zxyz_im;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_project_conversation_column $$
CREATE PROCEDURE add_project_conversation_column(
    IN p_column_name VARCHAR(64),
    IN p_column_definition VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_conversation'
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE im_conversation ADD COLUMN ', p_column_name, ' ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

CALL add_project_conversation_column('project_id', 'BIGINT NULL AFTER team_id');
CALL add_project_conversation_column('read_only', 'TINYINT NOT NULL DEFAULT 0 AFTER status');

DROP PROCEDURE IF EXISTS add_project_conversation_column;
