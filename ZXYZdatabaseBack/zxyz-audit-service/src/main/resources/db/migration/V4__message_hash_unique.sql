-- V4__message_hash_unique.sql
-- P1-C2: 将审计幂等下沉到数据库唯一约束。
-- 背景：V3 已新增 message_hash 列（VARCHAR(64) + 普通索引 idx_message_hash），但 Java 未填充且非唯一，
--       消费者仍靠 Redis setIfAbsent 先占位后插入，insert 失败时占位键已存在，重投被判重复而跳过 → 审计日志永久丢失。
-- 本迁移：历史空值置 NULL → 列改为 CHAR(64) → 普通索引替换为同一列上的唯一索引。

-- 1) 历史遗留 '' 空行无幂等意义，置 NULL（MySQL 唯一索引允许多个 NULL，不会互相冲突），否则建唯一键会失败
UPDATE operate_log SET message_hash = NULL WHERE message_hash = '';

-- 2) CHAR(64) + 唯一索引（用于核对 SHA-256 的 64 位十六进制哈希，保证长度一致）
ALTER TABLE operate_log
    DROP INDEX idx_message_hash,
    MODIFY COLUMN message_hash CHAR(64) NULL COMMENT '消息内容SHA-256十六进制哈希，用于唯一去重',
    ADD UNIQUE KEY uk_operate_log_message_hash (message_hash);