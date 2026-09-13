-- 联系方式（手机）验证码：补齐「尝试次数」与「使用状态」。
--
-- 背景：此前 contact_verification_code 只有 expire_time 一个约束，
-- 且同一 (user_id, contact_type) 只有一行、重发即覆盖 —— 也就是说 6 位验证码
-- 可以被无限次猜测（10^6 空间，无任何递增成本）。
--
-- 本次与 zxyz-email-service 的 verify_code 表对齐语义（逐列对应）：
--   attempt_count 记录校验尝试次数；
--   used / used_time 标记该码已被消费（成功校验，或尝试次数超限时作废）；
--   两个约束叠加后，爆破者每 10 分钟只有固定次数的机会，且超限即作废需要重新获取。
ALTER TABLE contact_verification_code
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '校验尝试次数' AFTER code,
    ADD COLUMN used TINYINT NOT NULL DEFAULT 0 COMMENT '0-未使用，1-已使用（含超限作废）' AFTER attempt_count,
    ADD COLUMN used_time DATETIME(3) DEFAULT NULL COMMENT '使用/作废时间' AFTER used;
