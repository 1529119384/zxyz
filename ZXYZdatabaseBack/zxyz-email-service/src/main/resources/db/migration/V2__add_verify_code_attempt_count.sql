-- V2__add_verify_code_attempt_count.sql
-- 为 verify_code 表新增 attempt_count 列，记录验证码尝试次数

ALTER TABLE verify_code
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '验证码验证尝试次数';
