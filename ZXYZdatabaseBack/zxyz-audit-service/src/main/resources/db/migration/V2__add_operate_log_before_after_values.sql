-- 添加 operate_log 表的 before_value 和 after_value 字段，用于记录操作前后的值
ALTER TABLE operate_log ADD COLUMN before_value TEXT AFTER return_value;
ALTER TABLE operate_log ADD COLUMN after_value TEXT AFTER before_value;
