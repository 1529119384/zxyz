-- V7__add_usage_ledger.sql
-- ============================================================================
-- P2-C1 / P2-C2 配额重设计：
--   1) 新建 usage_ledger 台账：每作用域一行，作为配额"检查+扣减"原子化的唯一权威。
--      - scope_key 与 file_node.scope_key 生成列语义对齐：P{projectId} / T{teamId} / U{uploadUserId}，
--        这样后台对账可以直接用 file_node 的 SUM(file_size) 按 scope_key 分组校正台账。
--      - storage_limit 为作用域存储上限实现（project_quota/user_quota/团队成员个人上限的实体化）。
--        可为 NULL（无限）。confirm 时的原子扣减用该列做守卫：
--          UPDATE usage_ledger SET used_bytes = used_bytes + ? WHERE scope_key=? AND (storage_limit IS NULL OR used_bytes + ? <= storage_limit)
--   2) 回收站计入配额：配额口径由 deleted=0 改为 deleted IN (0,1)，与 OSS 对象实际存活周期一致。
--      （引用计数也在 reallyDelete 时才释放，口径天然自洽。）
--
-- 说明：recycle 30 天 TTL / 墓碑 7 天物理清理不需要额外列，复用 file_node.modify_time 即可。
-- ============================================================================

CREATE TABLE usage_ledger (
    scope_key     VARCHAR(64)  NOT NULL COMMENT '作用域键：P{项目id} / T{团队id} / U{用户id}，与 file_node.scope_key 对齐',
    used_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '当前占用字节（回收站计入，deleted IN (0,1)）',
    storage_limit BIGINT       NULL COMMENT '存储上限字节；NULL 表示不限制',
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新/核对时间',
    PRIMARY KEY (scope_key)
) COMMENT = '配额台账：每作用域一行，配额检查与扣减的原子权威源';