// @ts-check
import request from '@/utils/request'

export const fetchAllConfigs = () => request.get('/api/admin/configs')

/**
 * @param {string} key - 配置键
 */
export const fetchConfig = (key) => request.get(`/api/admin/configs/${key}`)

/**
 * @param {Record<string, unknown>} payload - 配置创建请求体
 */
export const createConfig = (payload) => request.post('/api/admin/configs', payload)

/**
 * @param {string} key - 配置键
 * @param {unknown} value - 配置值
 */
export const updateConfig = (key, value) => request.put(`/api/admin/configs/${key}`, { value })

/**
 * 配置变更审计日志（分页）。
 *
 * 后端自 07-P0-2 起返回分页信封 `{ page, pageSize, total, list }`（此前是 `selectList`
 * 全表返回）。处理方式与 `fetchFileList` / `fetchRecycleList` 一致：把 `list` 拍平成 `data`、
 * 把 `total` 提升到信封同级，供 `el-pagination` 使用；同时兼容裸数组，
 * 避免后端版本回退时页面直接空白。
 *
 * @param {{ page?: number, pageSize?: number }} [options] - 分页参数
 * 注意这里**不写 `@returns`**：`request.get` 的静态返回类型是 axios 的 `AxiosResponse`，
 * 而拦截器实际产出的是 `{ code, msg, data }` 信封，两者不同源；硬写返回类型会让
 * 展开（spread）出来的对象被判为缺 `code/msg`。这也是 `api/share.js` 等文件的既有做法。
 */
export const fetchAuditLogs = async (options = {}) => {
  const { page, pageSize } = options
  const response = await request.get('/api/admin/configs/audit', {
    params: {
      ...(page ? { page } : {}),
      ...(pageSize ? { pageSize } : {}),
    },
  })

  const rawData = response?.data
  const list = Array.isArray(rawData) ? rawData : Array.isArray(rawData?.list) ? rawData.list : []
  const rawTotal = Array.isArray(rawData) ? null : (rawData?.total ?? null)

  return {
    ...response,
    data: list,
    ...(rawTotal == null ? {} : { total: Number(rawTotal) }),
  }
}
