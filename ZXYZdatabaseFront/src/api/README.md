# API 分域规范

前端 API 模块按业务能力和服务边界分域。同一个 API 文件只允许使用一个 HTTP 客户端，调用方从文件名即可判断请求落到主服务还是 IM 服务。

- `auth.js`：登录、注册、当前用户等身份认证能力。
- `user.js` / `account.js`：用户资料、账号绑定、账号切换等个人账号能力。
- `team.js`：主服务团队资料、团队成员、团队生命周期能力。
- `teamIm.js`：IM 服务团队协作投影能力，如公告、禁言、邀请、加入申请。
- `project.js`：项目目录、项目申请、项目成员、项目配额、项目生命周期能力。
- `permission.js`：系统权限与团队权限中心能力。
- `im.js`：IM 会话、消息、已读、在线状态、系统通知等即时通信能力。

同一业务语义只能在一个 API 模块导出。例如 `fetchMyTeams`、`fetchTeamMembers` 只能从 `team.js` 导出；团队协作投影接口只能从 `teamIm.js` 导出，不能在 `team.js` 或 `im.js` 里重复导出。

## 响应契约

所有后端 API 返回统一 `Result<T>` 包装：

```json
{
  "code": 1,
  "msg": "success",
  "data": { ... }
}
```

- **`code === 1`**：操作成功（`ErrorCode.SUCCESS = 1`，定义在 `zxyz-common/ErrorCode.java`）
- **`code !== 1`**：业务错误，`msg` 包含错误描述
- **HTTP 4xx/5xx**：网络或服务器错误，不返回 `Result` 包装

`createApiClient.js` 的响应拦截器统一判断 `payload?.code === 1`，成功时返回整个 payload（含 `code`/`msg`/`data`），调用方按需取 `response.data`。错误时抛出 `BusinessError`，由 `handleBusinessError` 处理。

## HTTP 客户端

| 客户端 | 文件 | 用途 | withCredentials |
|--------|------|------|----------------|
| `request` | `utils/request.js` | 已认证接口（默认） | `true` |
| `publicRequest` | `utils/publicRequest.js` | 公开/分享接口 | `true` |
| `imRequest` | `utils/imRequest.js` | IM 接口 | `true` |
