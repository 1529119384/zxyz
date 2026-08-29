-- V5__unify_root_parent_sentinel.sql
-- 统一根目录哨兵：历史存量中 parent_id IS NULL 的根节点回填为 -1，
-- 与全应用写入侧（parentId=-1 原样落库）保持一致。
--
-- 背景：P0-8——写入侧（FileUploadService/FileFolderService）以 -1 表示根目录，
-- 但 FileMapper.getPersonalRootFileIds 曾用 parent_id IS NULL 查询，条件永不命中，
-- 导致用户注销个人文件清理静默失效。本迁移回填存量根节点 + 勘正列注释。
-- V1 已发布不可改动，故勘正注释亦放入本迁移。

UPDATE file_node
SET parent_id = -1
WHERE parent_id IS NULL;

ALTER TABLE file_node
    MODIFY COLUMN parent_id BIGINT DEFAULT -1 COMMENT '-1 表示根目录';