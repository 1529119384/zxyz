USE zxyz_im;

-- -----------------------------------------------------------------------------
-- 团队权限系统初始化脚本（IM 库）
-- -----------------------------------------------------------------------------
-- 执行顺序：
-- 1. 先执行 001_im_team_baseline.sql，确保 team / team_member 等基础业务表存在
-- 2. 再执行本脚本，补齐团队权限子系统表和默认权限种子
--
-- 处理策略：
-- IM 库保留现有团队业务表，不整库清空。
-- 本脚本仅对团队权限子系统表执行幂等建表与种子补齐，支持重复执行。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS team_permission (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    permission_name VARCHAR(64) NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    builtin TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_role_code (team_id, role_code),
    INDEX idx_team_role_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_member_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    team_role_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_member_role_user (team_id, user_id),
    INDEX idx_team_member_role_role (team_role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_role_permission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    team_role_id BIGINT NOT NULL,
    permission_id INT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_team_role_permission (team_id, team_role_id, permission_id),
    INDEX idx_team_role_permission_role (team_role_id),
    INDEX idx_team_role_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_permission_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    operator_id INT NULL,
    operation_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    before_value TEXT NULL,
    after_value TEXT NULL,
    operation_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_team_permission_audit_team_time (team_id, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO team_permission(permission_name, permission_code, description)
VALUES
    ('团队查看', 'team:view', '允许查看团队资料'),
    ('团队更新', 'team:update', '允许更新团队资料'),
    ('成员查看', 'team:member:view', '允许查看成员列表'),
    ('成员创建', 'team:member:create', '允许创建成员账号'),
    ('邀请成员', 'team:member:invite', '允许邀请成员'),
    ('成员分配角色', 'team:member:assign-role', '允许为成员分配角色'),
    ('成员移除', 'team:member:remove', '允许移除成员'),
    ('公告发布', 'team:announcement:publish', '允许发布公告'),
    ('禁言管理', 'team:mute:manage', '允许禁言和解除禁言'),
    ('邀请链接管理', 'team:invite-link:manage', '允许管理邀请链接'),
    ('加入申请审核', 'team:join-request:review', '允许审核加入申请'),
    ('团队角色管理', 'team:role:manage', '允许管理团队角色'),
    ('团队权限查看', 'team:permission:read', '允许查看团队权限'),
    ('团队审计查看', 'team:audit:read', '允许查看团队权限审计'),
    ('项目管理', 'team:project:manage', '允许管理项目组'),
    ('团队文件读取', 'team:file:read', '允许读取团队文件'),
    ('团队文件写入', 'team:file:write', '允许修改团队文件'),
    ('团队文件删除', 'team:file:delete', '允许删除团队文件')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    description = VALUES(description),
    update_time = CURRENT_TIMESTAMP;

-- 历史团队可能在 team:member:view 权限加入前已创建，内置角色不会自动补齐。
-- 这里幂等补齐 owner/admin/member 的成员查看权限，避免进入团队空间后成员列表报权限不足。
INSERT INTO team_role_permission(team_id, team_role_id, permission_id, create_time)
SELECT tr.team_id, tr.id, tp.id, NOW()
FROM team_role tr
JOIN team_permission tp ON tp.permission_code = 'team:member:view'
WHERE tr.builtin = 1
  AND tr.role_code IN ('team_owner', 'team_admin', 'team_member')
ON DUPLICATE KEY UPDATE team_role_permission.create_time = team_role_permission.create_time;
