-- 65/66 号二.4：邀请/申请并发唯一键 —— check-then-insert 在并发下可重复入队。
-- 唯一约束定义在「未终态」语义上：MySQL 无部分索引，用「待处理态生成列」实现——
-- pending_token 对已终态（接受/拒绝/过期）行置 NULL（NULL 不参与唯一），待处理行取固定值。
ALTER TABLE team_invitation
    ADD COLUMN pending_flag TINYINT GENERATED ALWAYS AS (IF(status = 0, 1, NULL)) STORED,
    ADD CONSTRAINT uk_ti_team_invitee_pending UNIQUE (team_id, invitee_user_id, pending_flag);

ALTER TABLE team_join_request
    ADD COLUMN pending_flag TINYINT GENERATED ALWAYS AS (IF(status = 0, 1, NULL)) STORED,
    ADD CONSTRAINT uk_tjr_team_user_pending UNIQUE (team_id, user_id, pending_flag);

-- used_count 超卖守卫：TeamManagementMapper 的 `SET used_count = used_count + 1`
-- 加 WHERE 上限守卫（mapper 同步改），重复使用链接在 DB 层直接 0 行更新。
-- 注：max_uses=0 表示不限次数，mapper 侧 WHERE 需保留该语义（见 mapper 变更）。
