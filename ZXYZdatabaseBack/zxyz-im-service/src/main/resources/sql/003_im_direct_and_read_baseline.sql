USE zxyz_im;

ALTER TABLE im_conversation
    ADD COLUMN biz_key VARCHAR(128) NULL AFTER status,
    ADD COLUMN direct_user_a INT NULL AFTER team_id,
    ADD COLUMN direct_user_b INT NULL AFTER direct_user_a;

UPDATE im_conversation
SET biz_key = CONCAT('TEAM:', team_id)
WHERE type = 'TEAM'
  AND team_id IS NOT NULL
  AND (biz_key IS NULL OR biz_key = '');

ALTER TABLE im_conversation
    DROP INDEX uk_im_conversation_team;

ALTER TABLE im_conversation
    MODIFY COLUMN biz_key VARCHAR(128) NOT NULL,
    ADD UNIQUE KEY uk_im_conversation_biz_key (biz_key),
    ADD INDEX idx_im_conversation_direct_users (direct_user_a, direct_user_b, status);
