USE zxyz_im;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_project_conversation_name_column $$
CREATE PROCEDURE add_project_conversation_name_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_conversation'
          AND COLUMN_NAME = 'name'
    ) THEN
        ALTER TABLE im_conversation ADD COLUMN name VARCHAR(80) NULL AFTER project_id;
    END IF;
END $$

DROP PROCEDURE IF EXISTS add_project_conversation_project_index $$
CREATE PROCEDURE add_project_conversation_project_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_conversation'
          AND INDEX_NAME = 'idx_im_conversation_project'
    ) THEN
        ALTER TABLE im_conversation ADD INDEX idx_im_conversation_project (project_id, status);
    END IF;
END $$

DROP PROCEDURE IF EXISTS backfill_project_conversation_name $$
CREATE PROCEDURE backfill_project_conversation_name()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = 'zxyz_database'
          AND TABLE_NAME = 'project'
    ) THEN
        UPDATE im_conversation c
        JOIN zxyz_database.project p ON p.id = c.project_id
        SET c.name = p.name,
            c.update_time = NOW()
        WHERE c.type = 'PROJECT'
          AND c.project_id IS NOT NULL
          AND (c.name IS NULL OR c.name = '' OR c.name <> p.name);
    END IF;
END $$

DELIMITER ;

CALL add_project_conversation_name_column();
CALL add_project_conversation_project_index();
CALL backfill_project_conversation_name();

DROP PROCEDURE IF EXISTS add_project_conversation_name_column;
DROP PROCEDURE IF EXISTS add_project_conversation_project_index;
DROP PROCEDURE IF EXISTS backfill_project_conversation_name;
