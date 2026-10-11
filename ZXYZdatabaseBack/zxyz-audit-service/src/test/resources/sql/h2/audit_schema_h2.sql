-- H2（MySQL 兼容模式）版 operate_log 表 —— 供 AuditContextSmokeTest 的持久化路径使用。
--
-- 列集合与 Flyway 迁移链终态对齐：V1__init_schema（基础列）→ V2（before/after）
--   → V3（message_hash 列）→ V4（CHAR(64) + 唯一键 uk_operate_log_message_hash）。
-- 注意：真实迁移链与实体字段的双向对齐由 OperateLogEntityDdlGuardTest 守护（它解析
--   真实 V*__*.sql），本脚本不承担 DDL 真源职责，只服务 H2 持久化路径。
--
-- IF NOT EXISTS 是必须的：H2 的 INIT=RUNSCRIPT 对连接池中每条新连接都会执行一次，
-- 非幂等脚本会在第二条连接上抛「表已存在」。
-- 索引刻意用独立 CREATE INDEX 语句（而非 CREATE TABLE 内联 INDEX）：跨 H2 版本兼容性最稳。
CREATE TABLE IF NOT EXISTS operate_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_name VARCHAR(32) NOT NULL,
    operate_user BIGINT NULL,
    operate_time TIMESTAMP(3) NOT NULL,
    class_name VARCHAR(100) NULL,
    method_name VARCHAR(100) NULL,
    method_params TEXT NULL,
    return_value TEXT NULL,
    before_value TEXT NULL,
    after_value TEXT NULL,
    cost_time BIGINT NULL,
    message_hash CHAR(64) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_operate_log_message_hash UNIQUE (message_hash)
);

CREATE INDEX IF NOT EXISTS idx_ol_service_time ON operate_log (service_name, operate_time);
CREATE INDEX IF NOT EXISTS idx_ol_user_time ON operate_log (operate_user, operate_time);
CREATE INDEX IF NOT EXISTS idx_ol_time ON operate_log (operate_time);
