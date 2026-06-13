-- ============================================================================
-- zxyz-audit-service database schema v1.0
-- 指绣云章审计服务数据库 — 统一操作日志
--
-- 时间戳命名约定（m44）：
--   operate_log 使用 'operate_time' 而非 'create_time'，因审计日志强调操作时间语义。
--
-- TEXT 列说明（m47）：
--   method_params 和 return_value 使用 TEXT（64KB），足以存储大多数方法参数序列化结果。
--   如果出现超长参数截断，可改为 MEDIUMTEXT（16MB）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS zxyz_audit
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE zxyz_audit;

SET NAMES utf8mb4;

-- ============================================================================
-- 操作审计日志（从 project/file/team 三库合并，新增 service_name 标识来源服务）
-- ============================================================================

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
