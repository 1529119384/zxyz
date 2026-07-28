# 服务间接口设计规范

## 1. 窄端点优先

内部端点优先为调用方设计窄接口。
- 新增 ServiceClient 方法时，先确认对方是否有或应新增窄端点
- 避免调用胖接口后丢弃大部分字段（超过 30% 字段被丢弃即应改窄端点）

## 2. 调用方投影

ServiceClient 公开方法返回调用方自己的 POJO 或基本类型：
- ❌ 禁止：`public FileInfoDTO getFileInfoById(Long id)`（返回 zxyz-common 公共 DTO）
- ✅ 允许：`public ShareFileProjection getShareFileProjection(Long id)`（本服务投影）
- ✅ 允许：`public List<Long> listUserTeamIds(Long userId)`（标量集合）
- 投影 POJO 放在 `{service}/infrastructure/client/model/`（非 DDD）或 `domain/model/`（DDD）
- 投影字段必须经过"字段消费方对照"全调用点核实，公用字段保留、零使用字段剔除

## 3. 手动字段映射

JsonNode → Projection 使用手动字段提取，不使用 treeToValue：
- ❌ 禁止：`objectMapper().treeToValue(data, FileInfoDTO.class)`
- ✅ 允许：`data.path("id").asLong()` + `data.path("name").asText(null)`

## 4. 继承不传递 DTO

- ServiceClient 优先 `extends AbstractServiceClient`，只在确有共享场景才继承中间基类
- 子类内**禁止**新增返回上游公共 DTO 的方法
- 中间基类的 public 方法不应返回上游 DTO

## 5. 窄端点命名

- `/{资源}/{消费者}-projection`：为特定消费者设计的投影
- `/{资源}/ids/...`：返回 ID 列表
- `/{资源}/.../{单一量}`：返回标量值

## 6. ACL 双类不可去重

- 提供方 `XxxProjectionVO` 与调用方 `XxxProjection` 是两个独立类型，字段集故意相同
- 通过 JSON wire 解耦，版本独立演进，不合并、不去重、不放入 zxyz-common

## 7. 投影扩张约束

新增消费者投影前，字段差异 ≥ 3 才新建；否则复用最接近的现有 Projection VO。
超过 5 个并列 `*-projection` 方法时，引入 `InternalXxxQueryService` 内部 service 类按场景分发。

## 8. 判断职责污染要做全调用点核查

方法名表面"瘦"不代表职责不污染；手动 grep 全部调用方，确认字段消费情况后再下判断。

## 9. 内部端点参考

内部端点前缀 `/api/internal/**`，被 gateway 的 SaToken filter 拒绝公网访问，仅 Docker 内网服务间直连。

im-service 用 **`/api/im/internal/**`** 前缀（故意避开 `/api/internal/**`，以绕开 SaToken filter 的 internal 拒绝规则，仍受登录态校验保护）。

Gateway 还有两条 admin→业务的"桥接路由"：`/api/admin/email/**` → email-service `/api/email/internal/**`、`/api/admin/database/**` → project-service `/api/database/internal/**`，配合 `RewritePath` + `AddRequestHeader=X-Internal-Service-Token` 注入内部 token。

## 10. 已落地窄端点清单

| 端点 | 提供方 | 返回类型 | 调用方 |
|---|---|---|---|
| `GET /api/internal/teams/ids/by-user/{userId}` | team-service | `List<Long>` | project-service |
| `GET /api/internal/files/{fileId}/share-projection` | file-service | `ShareFileProjectionVO` | share-service |
| `POST /api/internal/files/batch-share-projection` | file-service | `List<ShareFileProjectionVO>` | share-service |
| `GET /api/internal/files/{parentId}/share-children-projection` | file-service | `List<ShareFileProjectionVO>` | share-service |
| `POST /api/internal/files/batch-share-children-projection` | file-service | `Map<Long, List<ShareFileProjectionVO>>` | share-service |
| `GET /api/internal/files/{fileId}/share-download-url` | file-service | `String` | share-service |
