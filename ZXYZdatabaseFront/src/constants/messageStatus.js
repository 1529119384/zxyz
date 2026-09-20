/**
 * 消息状态取值表：与后端 `MessageStatus` 同名同值。
 *
 * 与 `constants/conversationTypes.js` 同构 —— 这里是**唯一声明处**，
 * 下面按名导出的 `SENDING` / `FAILED` / … 都取值自它。
 *
 * 消费点（`store/im/*`、`views/chat/*`）一律用具名导出。07-A-3 复核时全仓只剩一处
 * 裸串（`store/im/messageDomain.js` 的消息域缓存淘汰 `msg.status === 'SENDING'`），已接上。
 *
 * ⚠️ 别拿这张表去套**邮件记录**的状态：`views/setting/components/EmailRecordPanel.vue`
 * 用的是另一套词表 `PENDING / SENDING / SENT / FAILED`，只是恰好有两个词重名，
 * 语义（IM 消息投递 vs 邮件发送）并不相干。
 */
export const MESSAGE_STATUS = Object.freeze({
  SENDING: 'SENDING',
  FAILED: 'FAILED',
  STORED: 'STORED',
  RECALLED: 'RECALLED',
})

export const SENDING = MESSAGE_STATUS.SENDING
export const FAILED = MESSAGE_STATUS.FAILED
export const STORED = MESSAGE_STATUS.STORED
export const RECALLED = MESSAGE_STATUS.RECALLED
