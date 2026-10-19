-- 66 号二.5：孤儿行一次性清理 —— user_quota / account_switch_trust 的行
-- 在 user 删除后残留（此前 deleteUser 不清理 quota；trust 已在 62g/65 修复中
-- 加了运行时清理，本迁移处理历史存量）。孤儿判定 = user 表无对应 id。
-- DELETE 前先 SELECT 确认量级（迁移日志可见）。

DELETE uq FROM user_quota uq
LEFT JOIN user u ON u.id = uq.user_id
WHERE u.id IS NULL;

DELETE t FROM account_switch_trust t
LEFT JOIN user u ON u.id = t.source_user_id
WHERE u.id IS NULL;

DELETE t FROM account_switch_trust t
LEFT JOIN user u ON u.id = t.target_user_id
WHERE u.id IS NULL;
