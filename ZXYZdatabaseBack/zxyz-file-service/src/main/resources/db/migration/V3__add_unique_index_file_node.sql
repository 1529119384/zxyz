-- V3__add_unique_index_file_node.sql — 防止并发场景下同一文件夹内重名文件
-- ============================================================================
-- 唯一索引覆盖范围：
--   (parent_id, file_type, original_name) + 空间维度 (team_id, space_type, project_id)
-- 空间维度说明：
--   - 个人空间：team_id=NULL, space_type=1, project_id=NULL （每个用户的个人根目录不同，天然隔离）
--   - 团队空间：team_id=团队ID, space_type=2, project_id=NULL
--   - 项目空间：team_id=团队ID, space_type=3, project_id=项目ID
--
-- 注意：
--   - 不含 deleted 列：软删除的文件不应阻止同名新文件的创建
--   - team_id / project_id 可为 NULL（个人空间），SQL 标准行为 NULL != NULL，
--     所以两个个人空间同名文件可以共存 — 这是可接受的，个人空间由用户自行管理
--   - 全新库（无历史索引）直接执行 CREATE，升级库由 DDL 版本管理 Flyway 自动跳过
-- ============================================================================

-- 创建唯一索引；original_name 使用前缀 200 chars（utf8mb4 约 800 字节）
-- 总索引大小约 830 字节，远低于 InnoDB 3072 字节上限
-- 200 字符前缀覆盖几乎所有实际文件名冲突，剩余极端 case 由应用层 resolveAvailableName 兜底
CREATE UNIQUE INDEX uk_file_node_parent_type_name_space
    ON file_node (parent_id, file_type, original_name(200), team_id, space_type, project_id)
    COMMENT '防止并发场景下同一空间文件夹内重名文件';
