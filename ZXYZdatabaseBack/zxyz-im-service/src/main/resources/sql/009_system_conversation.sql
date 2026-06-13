USE zxyz_im;

-- SYSTEM 会话使用现有 im_conversation / im_message 表。
-- biz_key 采用 SYSTEM:{userId}，消息类型采用 SYSTEM_NOTIFICATION。
-- 如历史库尚未执行 003/008，请先确保 biz_key、project_id、read_only 已存在。
