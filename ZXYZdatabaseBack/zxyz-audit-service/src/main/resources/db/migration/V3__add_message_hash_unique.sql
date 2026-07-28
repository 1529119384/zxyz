-- V3__add_message_hash_unique.sql
-- 为 operate_log 表新增 message_hash 列，用于消息去重
-- 注意：仅添加列，不加 UNIQUE 约束（Java 代码尚未填充 message_hash，加 UNIQUE 会导致插入失败）

ALTER TABLE operate_log
    ADD COLUMN message_hash VARCHAR(64) NOT NULL DEFAULT '' COMMENT '消息内容哈希，用于去重',
    ADD INDEX idx_message_hash (message_hash);
