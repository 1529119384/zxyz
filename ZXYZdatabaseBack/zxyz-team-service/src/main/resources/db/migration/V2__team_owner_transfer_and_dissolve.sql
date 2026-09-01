-- V2__team_owner_transfer_and_dissolve.sql — 团队所有者注销后的终态支持

-- 背景：用户注销时，team.owner_user_id（NOT NULL，无外键）仍指向已注销的用户，
--       而 team.status 依旧为 0（正常），团队会变成「没有任何活人能管理」的无主僵尸团队
--       —— 转让、解散、改配额都要求 owner 权限，而 owner 已经不存在。
-- 方案：所有者注销时，若有继任者则把 owner 转让给继任者；若无继任者则将团队置为「已解散」。
-- 说明：这里不动 owner_user_id 的 NOT NULL 约束 —— 允许为空会破坏大量既有代码对
--       「owner 必定非空」的假设，收益不抵风险，因此改用 status 表达终态。

ALTER TABLE team
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用，2-已解散(所有者注销且无继任者)';
