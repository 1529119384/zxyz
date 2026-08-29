-- V6__add_scope_key_and_active_unique_index_file_node.sql
-- ============================================================================
-- P1-C1：为 file_node 补正确的唯一键，兜底并发 check-then-act 重名。
-- 替换 V3 的唯一索引 uk_file_node_parent_type_name_space（其不含 upload_user_id，
-- 个人空间跨用户 NULL 竞态不生效，且不含 deleted 导致回收站墓碑会阻塞同名重建）。
--
-- 方案（取舍）：
--   生成的 scope_key 把"空间维度"收敛为一个非空前缀（项目 Pxx / 团队 Txx / 个人 Uxxx），
--   使个人空间中不同 upload_user_id 的文件也能被唯一约束区分。
--
--   deleted 的唯一性取舍：
--     不能把 deleted 原样含进唯一键 —— 同一(空间,父,名,类型)一旦软删，
--     墓碑记录会占用名字，根目录同名新增与回收站同名恢复都将触发唯一冲突。
--     也不能用 IF(deleted=0, name, CONCAT(id,'#',name)) 作消歧 —— MySQL 生成列禁止引用
--     AUTO_INCREMENT 主键，无法给每条删除行一个确定唯一的嵌入值。
--     因此采用生成列 active_name = IF(deleted=0, original_name, NULL)：
--       - 活跃记录( deleted=0 )：值 = original_name，进入唯一键，保证唯一。
--       - 软删记录( deleted IN (1,2) )：值为 NULL，MySQL 唯一索引允许多个 NULL，
--         墓碑不再阻塞同名重建/恢复。
--     即唯一键只约束"活跃记录"，符合业务：同名重新上传、回收站同名恢复均可正常进行。
-- ============================================================================

-- 1. 生成列：空间维度（项目/团队/个人 三元取仅一个非空前缀）
ALTER TABLE file_node
    ADD COLUMN scope_key VARCHAR(64)
        GENERATED ALWAYS AS (
            CASE
                WHEN space_type = 3 AND project_id IS NOT NULL THEN CONCAT('P', project_id)
                WHEN space_type = 2 AND team_id IS NOT NULL THEN CONCAT('T', team_id)
                ELSE CONCAT('U', upload_user_id)
            END
        ) STORED COMMENT '空间唯一维度：P项目 / T团队 / U个人';

-- 2. 生成列：仅活跃记录保留原名；软删记录为 NULL 以释放唯一约束
ALTER TABLE file_node
    ADD COLUMN active_name VARCHAR(250)
        GENERATED ALWAYS AS (IF(deleted = 0, original_name, NULL)) STORED
        COMMENT '唯一键名：活跃=原名，软删=NULL（释放同名）';

-- 3. 替换旧的、有缺陷的唯一索引
DROP INDEX uk_file_node_parent_type_name_space ON file_node;

-- 4. 新的唯一索引：范围 + 父目录 + 类型 + 活跃名
--    active_name 为 NULL 的行（软删）不受唯一约束；活跃行内保证唯一。
CREATE UNIQUE INDEX uk_file_node_scope_parent_type_active
    ON file_node (scope_key, parent_id, file_type, active_name)
    COMMENT '同一空间同一父目录下活跃文件/文件夹名称唯一（软删不占名）';