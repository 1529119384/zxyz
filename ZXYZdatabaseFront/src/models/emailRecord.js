// @ts-check

/**
 * 邮件记录分页信封。
 *
 * 字段一律写成 `unknown` 而不是 `any[]` / `number`：本模块的职责就是**对任意后端回包降级**，
 * spec 里刻意喂了 `{ list: 'not-an-array' }` / `{ records: null }` / `{ total: 'abc' }`
 * 这类畸形数据。把字段收窄会逼着调用点加断言 —— 那是把防御性代码变成类型谎言。
 *
 * @typedef {Object} EmailRecordPageEnvelope
 * @property {unknown} [list] - 新字段名（07-P2-4 起，与全库 PageResult 一致）。
 * @property {unknown} [records] - 旧字段名，仅作降级保护。
 * @property {unknown} [total]
 * @property {unknown} [page]
 * @property {unknown} [pageSize]
 */

/**
 * 邮件记录分页信封的归一化。
 *
 * 后端 `EmailRecordPageVO{total, page, pageSize, records}` 自 07-P2-4 起并入
 * `zxyz-common` 的 `PageResult{page, pageSize, total, list}` —— 列表字段名从 `records`
 * 变成 `list`，与文件列表 / 回收站 / 分享 / 审计日志四处对齐。
 *
 * **同时认 `records` 是刻意的降级保护，不是遗漏**：push 到 `dev` 即部署生产，前端
 * 无法单独灰度，部署窗口期内浏览器可能拿到「新后端 + 旧前端产物」或反之；只认一个字段
 * 会让整页记录空白。手法与 `models/share.js::mapMyShareRecords` 的 `rows` 分支一致，
 * 线上稳定后可删掉 `records` 分支。
 *
 * @param {EmailRecordPageEnvelope|null} [data] - 后端 `Result.data`（分页信封）。
 * @returns {{ list: any[], total: number, page: number, pageSize: number }} 归一化后的分页结果。
 */
export function mapEmailRecordPage(data = {}) {
  /** @type {EmailRecordPageEnvelope} */
  const source = data || {}
  const list = Array.isArray(source.list)
    ? source.list
    : Array.isArray(source.records)
      ? source.records
      : []

  return {
    list,
    total: Number(source.total) || 0,
    // 0 表示「后端没回传」—— usePagedList 只在 pageSize >= 1 时才用回传值校准本地页长，
    // 所以 0 会被安全忽略，不会把页长改成 0。
    page: Number(source.page) || 0,
    pageSize: Number(source.pageSize) || 0,
  }
}
