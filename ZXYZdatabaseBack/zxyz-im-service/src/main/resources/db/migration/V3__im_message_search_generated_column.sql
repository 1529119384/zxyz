-- Add generated column for TEXT message content search to avoid per-row JSON_EXTRACT
ALTER TABLE im_message
ADD COLUMN content_text VARCHAR(5000) GENERATED ALWAYS AS (
    CASE WHEN message_type = 'TEXT'
         THEN JSON_UNQUOTE(JSON_EXTRACT(content, '$.content'))
         ELSE NULL
    END
) STORED;

-- Prefix index for LIKE 'keyword%' on content_text, scoped by conversation_id
CREATE INDEX idx_im_content_search ON im_message (conversation_id, status, content_text(100));
