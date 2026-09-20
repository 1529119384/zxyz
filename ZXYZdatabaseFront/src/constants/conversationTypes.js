/**
 * 会话类型取值表：与后端 `ConversationType` 同名同值。
 *
 * 这里是**唯一声明处** —— 下面按名导出的 `SYSTEM` / `DIRECT` / … 都取值自它，
 * 所以两种形态不可能各自漂移。全仓 13 个消费点（`store/im/*`、`views/chat/*`）
 * import 的都是那些具名导出：比较场景写 `type === TEAM` 比 `CONVERSATION_TYPE.TEAM` 短，
 * 也更容易被静态检查发现拼错。
 *
 * `CONVERSATION_TYPE` 本身供**枚举**场景使用 —— 见 `NOTIFICATION_CONVERSATION_TYPES`。
 */
export const CONVERSATION_TYPE = Object.freeze({
  SYSTEM: 'SYSTEM',
  DIRECT: 'DIRECT',
  TEAM: 'TEAM',
  PROJECT: 'PROJECT',
  TEAM_NOTIFICATION: 'TEAM_NOTIFICATION',
})

export const SYSTEM = CONVERSATION_TYPE.SYSTEM
export const DIRECT = CONVERSATION_TYPE.DIRECT
export const TEAM = CONVERSATION_TYPE.TEAM
export const PROJECT = CONVERSATION_TYPE.PROJECT
export const TEAM_NOTIFICATION = CONVERSATION_TYPE.TEAM_NOTIFICATION

/**
 * 「只读的通知类会话」：系统消息 + 团队消息。
 *
 * 这两类会话都不允许发言、未读数也单独累加。`store/im/realtimeDomain.js` 里此前
 * 各写了一遍内联数组 `[SYSTEM, TEAM_NOTIFICATION]`，07-A-3 起收敛到这里 ——
 * 这也是 `CONVERSATION_TYPE` 的第一个真实枚举消费方（否则它就只是一张没人用的副本）。
 */
export const NOTIFICATION_CONVERSATION_TYPES = Object.freeze([
  CONVERSATION_TYPE.SYSTEM,
  CONVERSATION_TYPE.TEAM_NOTIFICATION,
])
