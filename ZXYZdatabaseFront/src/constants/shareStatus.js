// 分享记录状态码常量。取值与后端 share_record.status 一致。
// 本文件是分享状态码的唯一权威来源；状态码 -> 文案的映射见 ../models/share.js 的 SHARE_STATUS_MAP。
export const SHARE_STATUS = Object.freeze({
  ACTIVE: 0, // 生效中
  CANCELLED: 1, // 已取消
  EXPIRED: 2, // 已过期
  EXHAUSTED: 3, // 次数用尽
})
