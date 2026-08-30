package uno.acloud.common;

/**
 * 服务间通信共用请求头常量。
 */
public final class InternalServiceHeaders {

    /** 内部服务调用鉴权令牌请求头 */
    public static final String TOKEN_HEADER = "X-Internal-Service-Token";

    /** 来源服务名请求头（接收方据此查白名单矩阵） */
    public static final String CALLER_SERVICE_HEADER = "X-Internal-Caller-Service";

    /** 请求链路追踪 ID 请求头 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private InternalServiceHeaders() {}
}
