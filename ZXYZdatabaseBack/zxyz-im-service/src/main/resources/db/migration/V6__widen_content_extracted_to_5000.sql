-- V6__widen_content_extracted_to_5000.sql
--
-- 修复：content_extracted 生成列宽（2000）小于应用允许的单条消息文本上限（5000）。
--
-- 根因（迁移次序错配，不是笔误）：
--   V3 建的 content_text      是 VARCHAR(5000)，与 app.im.message.max-text-length 一致；
--   V4 又建了一个用途重复的 content_extracted VARCHAR(2000)；
--   V5 的收缩阶段删掉了 content_text（理由：代码只消费 content_extracted）。
--   ⇒ 结果「唯一存活的生成列」宽度是 2000，比应用上限少 3000 个字符。
--
-- 故障表现（修复前）：
--   2001..5000 字符的 TEXT 消息能通过 ImMessageService 的校验（校验读的是 5000），
--   但写入生成列时撑爆列宽：
--     - MySQL 严格模式（8.x 默认）⇒ ERROR 1406，消息发送失败（用户可见报错）；
--     - 非严格模式              ⇒ 静默截断，该消息在搜索中永久查不到（无任何日志）。
--
-- 本迁移把列宽对齐到应用上限。语义上是「只增不减」的放宽，不改生成表达式。
-- ⚠️ STORED 生成列改类型/长度需要重建该表（MySQL 不提供纯 INPLACE 路径），
--    故请在维护窗口执行；im_message 表规模有限，锁窗很短。
--
-- ⚠️ 不变量（由 ImMessageContentColumnWidthTest 守护）：
--    本列宽 >= app.im.message.max-text-length。
--    该键在仓库中有三处取值，任何一处被调大而列宽未同步，测试即变红：
--      1) nacos-config/zxyz-dynamic.yml        （运行时生效值，测试直接读它）
--      2) ImMessageService 的 @Value 默认值 5000
--      3) admin-service V2/V3__fix_config_keys.sql 的 sys_config 种子值
ALTER TABLE im_message
    MODIFY COLUMN content_extracted VARCHAR(5000)
        GENERATED ALWAYS AS (
            CASE
                WHEN message_type = 'TEXT' THEN JSON_UNQUOTE(JSON_EXTRACT(content, '$.content'))
                ELSE NULL
            END
        ) STORED;
