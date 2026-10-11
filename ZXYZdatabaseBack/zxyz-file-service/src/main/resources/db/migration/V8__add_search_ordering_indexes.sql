-- 54 号（2026-10-11）🟡：file 搜索深分页 filesort
--
-- 症状：countByKeyword/searchByKeyword 走「team_id/user_id 等值过滤 + original_name
--   LIKE 'kw%' 前缀匹配 + ORDER BY modify_time DESC LIMIT/OFFSET」。
--   既有索引里 idx_fn_user_parent_deleted(upload_user_id, parent_id, deleted)、
--   idx_fn_team_parent_deleted(team_id, parent_id, deleted) 可供等值过滤，
--   idx_fn_deleted_modify(deleted, modify_time) 可供排序，但没有任何一条索引
--   同时覆盖「过滤列 + modify_time 排序」⇒ 排序阶段 filesort，行数大时深分页代价陡增。
--
-- 本迁移补两条「过滤 + 排序」复合索引，让 COUNT 与分页 SELECT 都走索引序免 filesort：
--   个人空间分支：team_id IS NULL AND upload_user_id = ? ORDER BY modify_time DESC
--   团队空间分支：team_id = ?                     ORDER BY modify_time DESC
-- （original_name 的 LIKE 'kw%' 在索引过滤后的行集上回表过滤，前缀选择性本就高，
--   不并入索引——避免为低基数前缀列加宽索引的写放大。）
--
-- 命名沿用 idx_fn_ 前缀惯例。IF NOT EXISTS 语义由应用侧保证迁移只执行一次（Flyway 版本化）。

ALTER TABLE file_node
    ADD INDEX idx_fn_user_modify (upload_user_id, team_id, modify_time),
    ADD INDEX idx_fn_team_modify (team_id, modify_time);
