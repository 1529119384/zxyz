-- V4__add_message_content_index.sql
-- Add generated column + prefix index for message content search (P2-04)
-- 已从 V3 重编号为 V4（原 V3__add_message_content_index.sql，避免与 V3__im_message_search_generated_column.sql 版本冲突）

-- Generated column: extracts text content from JSON for TEXT messages, NULL for others
ALTER TABLE im_message
    ADD COLUMN content_extracted VARCHAR(2000)
        GENERATED ALWAYS AS (
            CASE
                WHEN message_type = 'TEXT' THEN JSON_UNQUOTE(JSON_EXTRACT(content, '$.content'))
                ELSE NULL
            END
        ) STORED;

-- Prefix index for LIKE 'keyword%' queries on TEXT message content
ALTER TABLE im_message
    ADD INDEX idx_im_content_extracted (content_extracted(100));
