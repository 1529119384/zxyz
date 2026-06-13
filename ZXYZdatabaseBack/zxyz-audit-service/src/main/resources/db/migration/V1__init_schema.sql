-- V1__init_schema.sql — 审计日志表
-- 建议保留周期：90 天。可通过 audit-service 的定时任务自动清理过期记录。
-- 大量数据场景建议按月分区（PARTITION BY RANGE），此处为简化初始版本未加分区。

CREATE TABLE operate_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_name VARCHAR(32) NOT NULL COMMENT '来源服务标识',
    operate_user BIGINT NULL,
    operate_time DATETIME(3) NOT NULL,
    class_name VARCHAR(100) NULL,
    method_name VARCHAR(100) NULL,
    method_params TEXT NULL,
    return_value TEXT NULL,
    cost_time BIGINT NULL,
    PRIMARY KEY (id),
    INDEX idx_ol_service_time (service_name, operate_time),
    INDEX idx_ol_user_time (operate_user, operate_time),
    INDEX idx_ol_time (operate_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
