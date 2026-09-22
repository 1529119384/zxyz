-- V2__add_project_leader_and_pm_user_indexes.sql
--
-- 背景（根因）：
--   V1__init_schema.sql 建表时只为「正查」路径建了索引（按 team_id 查项目、按 project_id 查成员），
--   但代码里存在两条「反查」路径，它们的过滤列都不是既有索引的前导列，导致全表扫描：
--
--   1) ProjectMapper.java:55  countActiveProjectsLedBy
--        SELECT COUNT(*) FROM project WHERE leader_user_id = ? AND status = 0
--      既有索引 idx_project_team_status (team_id, status) 前导列是 team_id，无法用于 leader_user_id 过滤。
--
--   2) ProjectMapper.java:67  deleteByUserId
--        DELETE FROM project_member WHERE user_id = ?
--      既有 uk_project_member (project_id, user_id) 与 idx_pm_project (project_id) 前导列都是 project_id，
--      无法用于 user_id 单列过滤（user_id 是联合索引的第二列，不满足最左前缀）。
--      该路径在「团队成员被移出」时触发，属写路径，全表扫还会持锁更久。
--
-- 索引设计说明：
--   * project 侧用「联合索引 (leader_user_id, status)」而非单列索引：
--     :55 的谓词同时命中 leader_user_id 与 status 两列，联合索引可一次性定位且对 COUNT(*) 构成覆盖索引，
--     单列索引仍需回表再过滤 status。
--   * project_member 侧用单列 (user_id)：:67 只按 user_id 过滤，无第二谓词，单列即为最优。
--
-- 刻意「不」做的事（避免后人误改）：
--   * 不重新创建 idx_pcr_leader：project_create_request 在 V1 中已有
--     `INDEX idx_pcr_leader (leader_user_id)`，重复添加会使本迁移失败。
--   * 不删除 idx_pm_project (project_id)：其与 uk_project_member 前导列重复、理论上冗余，
--     但删除索引属独立决策，不在本迁移意图范围内，避免「一个迁移做两件事」。
--   * 不使用 IF NOT EXISTS 兜底（MySQL 的 ADD INDEX 本就不支持该语法）：
--     若索引已存在，本迁移就应当失败并大声报错，而不是静默跳过 —— 静默跳过会掩盖 schema 漂移。
--
-- 锁与耗时：MySQL 8 添加二级索引默认走 INPLACE 算法、允许并发 DML，基本无锁；
--           仅需重建二级索引 B+ 树，数据量级下耗时可忽略。

ALTER TABLE project
    ADD INDEX idx_project_leader_status (leader_user_id, status);

ALTER TABLE project_member
    ADD INDEX idx_pm_user (user_id);
