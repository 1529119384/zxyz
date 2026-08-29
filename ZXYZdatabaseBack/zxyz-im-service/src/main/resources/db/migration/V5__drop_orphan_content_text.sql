-- V5__drop_orphan_content_text.sql
-- 清理 V3__im_message_search_generated_column.sql 遗留的死生成列与死索引。
--
-- 背景：
--   - V3 曾创建 content_text 生成列 + idx_im_content_search 索引，但代码从未消费该列
--     （ImMessageMapper 搜索仅用 V4 引入的 content_extracted / idx_im_content_extracted）。
--   - 该死列与索引对每条 TEXT 消息写入时做额外生成/写放大，浪费存储且增加维护面。
--   - 结论为 P0-6 / P2 双轨清理的一部分：删除非活跃方案，保留代码实际使用的 content_extracted。
--
-- 安全（expand-and-contract 的收缩阶段）：任何环境迁移到本版本前必已执行 V3（建 content_text），
-- 故此处 DROP COLUMN 与 DROP INDEX 不会因列/索引不存在而失败。
ALTER TABLE im_message
    DROP INDEX idx_im_content_search,
    DROP COLUMN content_text;