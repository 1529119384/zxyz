-- V4__add_delete_time_to_file_object_ref.sql
-- 添加 delete_time 列用于精确追踪进入 DELETED 状态的时间
-- deleteExpiredDeleted 保留 30 天宽限期，避免误删已上传未 confirm 的对象
-- delete_time IS NULL 视为已过期（迁移时设 = modify_time）

ALTER TABLE file_object_ref
    ADD COLUMN delete_time DATETIME(3) NULL COMMENT '进入 DELETED 状态的时间，NULL 表示尚未删除'
    AFTER modify_time;

-- 为已有的 DELETED 记录设置 delete_time = modify_time（迁移时视为已完成宽限期）
-- 这样旧 DELETED 记录在下次 cleanup 时会被正常清理，不会因 delete_time 为 NULL 而被跳过
UPDATE file_object_ref
SET delete_time = modify_time
WHERE delete_status = 'DELETED'
  AND delete_time IS NULL;
