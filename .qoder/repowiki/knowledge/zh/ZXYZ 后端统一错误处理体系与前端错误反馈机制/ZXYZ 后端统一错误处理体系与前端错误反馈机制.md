---
kind: error_handling
name: ZXYZ 后端统一错误处理体系与前端错误反馈机制
category: error_handling
scope:
    - '**'
source_files:
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/GlobalExceptionHandler.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/exception/BusinessException.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/ErrorCode.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/UserErrorCode.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/TeamErrorCode.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/ShareErrorCode.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/ProjectErrorCode.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/ErrorCodeMarker.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/common/Result.java
    - ZXYZdatabaseBack/zxyz-common/src/main/java/uno/acloud/client/AbstractServiceClient.java
    - ZXYZdatabaseFront/src/utils/errorModel.js
    - ZXYZdatabaseFront/src/utils/error.js
    - ZXYZdatabaseFront/src/utils/request.js
---

## 1. 系统/方案概述
ZXYZ 后端采用 Spring Boot 3 + Sa-Token 的微服务架构，通过统一的 `GlobalExceptionHandler`（`@RestControllerAdvice`）集中捕获并标准化所有异常；业务层使用自定义 `BusinessException` 携带稳定错误码；前后端通过一致的 `Result<T>` 响应体契约传递错误信息。前端基于 Vue 3 + Element Plus，在 `utils/errorModel.js` 中提供统一的错误解析、标记与提示工具，配合 `handleBusinessError` 在各业务逻辑中集中展示用户可见的错误消息。

## 2. 核心文件与包
- 后端公共模块 `zxyz-common`：
  - `uno.acloud.common.GlobalExceptionHandler` — 全局异常处理器，覆盖业务异常、Sa-Token 认证/授权异常、参数校验异常、JSON 反序列化异常等。
  - `uno.acloud.exception.BusinessException` — 统一业务异常基类，支持 `ErrorCodeMarker` 或 int 错误码构造，可附带 data。
  - `uno.acloud.common.ErrorCode` — 通用错误码常量及 HTTP 状态码映射中心（`resolveHttpStatus`）。
  - `uno.acloud.common.ErrorCodeMarker` 接口及领域枚举：`UserErrorCode`、`TeamErrorCode`、`ShareErrorCode`、`ProjectErrorCode` — 按领域拆分错误码，保留数值兼容旧 API。
  - `uno.acloud.common.Result<T>` — 统一响应体结构 `{ code, msg, data }`，提供 `success()` / `error(code, msg[, data])` 工厂方法。
  - `uno.acloud.client.AbstractServiceClient` — 微服务间调用封装，失败时抛出 `BusinessException`，重试策略忽略 `BusinessException`。
- 前端公共模块 `ZXYZdatabaseFront/src/utils`：
  - `errorModel.js` — 错误对象规范化、全局已处理标记、错误码/消息提取、上传错误日志记录。
  - `error.js` — 导出 `handleBusinessError` 供各 composable 调用，统一弹出 `ElMessage.error`。
  - `request.js` / `createApiClient` — HTTP 客户端配置，超时、token 过期重定向、blob 下载等。

## 3. 架构与约定
- 异常分类与捕获顺序：
  1) `BusinessException` → 按 `ErrorCode.resolveHttpStatus` 返回对应 HTTP 状态码与 `Result.error(...)`。
  2) Sa-Token 异常：`NotLoginException`（401）、`NotPermissionException`/`NotRoleException`（403）→ 统一翻译为中文消息。
  3) 参数校验：`MethodArgumentNotValidException`、`BindException`、`ConstraintViolationException`、`MissingServletRequestParameterException`、`HttpMessageNotReadableException` → 全部归一化为 `BAD_REQUEST(4000)` 并提取第一条校验消息。
  4) 兜底 `Exception` → 500 内部错误，固定消息“操作失败，请稍后重试”。
- 错误码演进：`ErrorCode` 中的整型常量逐步迁移到领域枚举（`UserErrorCode` 已完成），新代码必须使用对应枚举，禁止直接使用 `ErrorCode` 的 int 常量（见注释 TODO(P3-01)）。
- 跨服务调用：`AbstractServiceClient` 将网络异常、超时、非成功响应码统一包装为 `BusinessException`，并在重试配置中忽略 `BusinessException`，避免对业务错误进行无意义重试。
- 前端错误处理：
  - `handleBusinessError(error, fallbackMessage)` 会检查是否已被全局处理（`isHandledByGlobalError`），否则从 `error.response.data.msg/message` 或 `error.message` 提取消息并通过 `ElMessage.error` 展示。
  - `createBusinessError` 用于构造带 response 的业务错误对象，`markGlobalErrorHandled` 标记已由全局拦截器处理，防止重复提示。
  - `getErrorCode` 提取 `code` 字段，`isImConversationAccessError` 针对 IM 权限错误做特殊判断。

## 4. 约定与约束
- 业务层抛错必须使用 `BusinessException`，不得直接 throw 普通 Exception 或返回裸字符串错误。
- 错误码必须来自 `ErrorCode` 常量或对应领域枚举（`UserErrorCode`/`TeamErrorCode`/`ShareErrorCode`/`ProjectErrorCode`），禁止随意定义新整型码。
- 所有 Controller 无需再编写局部 `@ExceptionHandler`，统一由 `GlobalExceptionHandler` 处理。
- 前端各 composable 应通过 `handleBusinessError` 统一处理请求失败，避免分散的 try/catch 和重复提示。
- 跨服务调用失败不应触发重试（`ignoreExceptions(BusinessException.class)`），仅对网络/超时类异常重试。
- 响应体必须遵循 `Result<T>` 结构，错误场景使用 `Result.error(code, message[, data])`，保证前后端契约一致。
