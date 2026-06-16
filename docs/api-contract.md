# API 契约规范

## Result 包装结构

所有后端 API 响应统一使用 `Result<T>` 包装：

```json
{
  "code": 1,
  "msg": "success",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | `int` | 业务状态码，`1` = 成功，其他为错误码 |
| `msg` | `String` | 描述信息，成功时为 `"success"` |
| `data` | `T` | 响应数据，错误时为 `null` |

## 成功码

**`code: 1`**（`ErrorCode.SUCCESS = 1`）表示操作成功。前端 `createApiClient.js` 中统一判断 `payload?.code === 1` 为成功。

## 错误码分类

| 范围 | 类别 | 示例 |
|------|------|------|
| 1 | 成功 | `SUCCESS` |
| 4000 | 参数错误 | `BAD_REQUEST` |
| 4001 | 用户 | `USER_NOT_FOUND` |
| 4010 | 认证 | `NO_LOGIN` |
| 4100-4101 | 登录 | `LOGIN_FAILED`, `USERNAME_EXISTS` |
| 4030 | 权限 | `NO_PERMISSION` |
| 4300-4304 | 分享 | `SHARE_NOT_FOUND`, `SHARE_EXPIRED` |
| 4040 | 资源不存在 | `NOT_FOUND` |
| 4400-4404 | 团队 | `TEAM_NOT_FOUND`, `TEAM_PERMISSION_DENIED` |
| 4410-4411 | 项目 | `PROJECT_NOT_FOUND` |
| 4090-4091 | 状态冲突 | `FILE_STATE_INVALID`, `CONCURRENT_OPERATION` |
| 5000 | 系统错误 | `SYSTEM_ERROR` |

## 内部服务调用

服务间 HTTP 调用通过以下 Header 鉴权：

| Header | 值 | 说明 |
|--------|-----|------|
| `X-Internal-Service-Token` | 环境变量 `INTERNAL_SERVICE_TOKEN` | 内部服务鉴权 Token |
| `X-Request-Id` | MDC 中的 `requestId` | 链路追踪 ID |

## 前端 Axios 实例

| 实例 | 文件 | 用途 | withCredentials |
|------|------|------|----------------|
| `request` | `utils/request.js` | 已认证接口 | `true` |
| `publicRequest` | `utils/publicRequest.js` | 公开/分享接口 | `true`（默认） |
| `imRequest` | `utils/imRequest.js` | IM 接口 | `true` |
