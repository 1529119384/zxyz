-- V1__init_schema.sql — 初始表结构

-- ============================================================================
-- 1. 文件节点（从 zxyz_database 迁移）
-- ============================================================================

CREATE TABLE file_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_type TINYINT NOT NULL COMMENT '0-文件夹，1-文件',
    original_name VARCHAR(250) NOT NULL,
    uuid_name VARCHAR(250) NULL COMMENT 'OSS 唯一文件名，文件夹为 NULL',
    category TINYINT NULL COMMENT '文件分类，文件夹为 NULL',
    file_size BIGINT NULL COMMENT '文件大小（字节），文件夹为 NULL',
    file_url VARCHAR(1024) NULL COMMENT '访问 URL，文件夹为 NULL',
    store_path VARCHAR(1024) NOT NULL,
    upload_user_id BIGINT NOT NULL,
    shared_user_id BIGINT NULL COMMENT '分享来源用户',
    team_id BIGINT NULL COMMENT 'NULL 表示个人空间',
    space_type TINYINT NOT NULL COMMENT '1-个人空间，2-团队空间，3-项目空间',
    project_id BIGINT NULL COMMENT '所属项目，非项目空间为 NULL',
    parent_id BIGINT DEFAULT NULL COMMENT 'NULL 表示根目录',
    deleted_user_id BIGINT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-回收站，2-已彻底删除',
    create_time DATETIME(3) NOT NULL,
    modify_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_fn_team_parent_deleted (team_id, parent_id, deleted),
    INDEX idx_fn_user_parent_deleted (upload_user_id, parent_id, deleted),
    INDEX idx_fn_space_project_parent_deleted (space_type, project_id, parent_id, deleted),
    INDEX idx_fn_deleted_modify (deleted, modify_time),
    INDEX idx_fn_parent (parent_id),
    INDEX idx_fn_original_name (original_name),
    INDEX idx_fn_uuid_name (uuid_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 2. OSS 对象引用计数（从 zxyz_database 迁移）
-- ============================================================================

CREATE TABLE file_object_ref (
    object_key VARCHAR(250) NOT NULL,
    ref_count INT NOT NULL DEFAULT 0,
    delete_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE-可用，PENDING_DELETE-待物理删除，DELETING-删除中，DELETED-已物理删除',
    delete_retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NULL,
    last_delete_error VARCHAR(1024) NULL,
    create_time DATETIME(3) NOT NULL,
    modify_time DATETIME(3) NOT NULL,
    PRIMARY KEY (object_key),
    INDEX idx_for_delete_scan (delete_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

