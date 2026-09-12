package uno.acloud.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 下游业务服务解析「真实客户端 IP」。
 *
 * <h3>为什么不能直接用 {@code request.getRemoteAddr()}</h3>
 * <p>部署拓扑为 {@code frontend-nginx → gateway → service}，业务服务的直接对端恒为网关容器，
 * 因此 {@code getRemoteAddr()} 拿到的是网关/nginx 的容器 IP——<b>所有真实客户端共用一个值</b>。
 * 拿它当限流 key 会让「每 IP 限流」退化成<b>全局单桶</b>：攻击者只要按阈值节奏打接口，
 * 就能让全平台用户一起失败（登录、注册、分享提取码验证、邮箱验证码均受此影响）。</p>
 *
 * <h3>为什么可以信任 {@code X-Real-IP}</h3>
 * <p>网关 {@code ClientIpFilter}（{@code Ordered.HIGHEST_PRECEDENCE}）会<b>无条件删除</b>客户端
 * 自传的 {@code X-Forwarded-For} 与 {@code X-Real-IP}，再<b>覆盖式</b>写入自己解析出的结果：
 * 配置了 {@code app.gateway.trusted-proxies} 时是真实客户端 IP，未配置时回落为远程地址。
 * 也就是说，本工具读到的该头<b>要么是真实客户端 IP，要么与 {@code getRemoteAddr()} 等价</b>，
 * 相比原实现只会更好、不会更差。</p>
 *
 * <h3>信任前提（改了部署形态就要重新确认）</h3>
 * <p>该头仅在「业务服务不可被外部直连」时可信。本仓库 {@code docker-compose.yml} 中各业务服务
 * <b>均未声明 {@code ports:}</b>，对外只暴露 nginx，故外部无法绕过网关伪造该头。
 * 若日后为某个业务服务开放了宿主端口，必须同时为其补上可信代理校验，否则该头可被伪造。</p>
 */
public final class ClientIpUtil {

    /** 网关注入的真实客户端 IP 头名，须与网关 {@code ClientIpResolver.X_REAL_IP_HEADER} 保持一致。 */
    public static final String X_REAL_IP_HEADER = "X-Real-IP";

    /** 无法确定来源时的占位值；各限流器会把空值与它归入同一个兜底桶。 */
    public static final String UNKNOWN = "unknown";

    private ClientIpUtil() {
    }

    /**
     * 解析真实客户端 IP：优先取网关注入的 {@code X-Real-IP}，不可用时回落 {@code getRemoteAddr()}。
     *
     * @param request 当前请求，允许为 {@code null}
     * @return 真实客户端 IP；两侧都取不到时返回 {@link #UNKNOWN}，<b>永不返回 null</b>
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String realIp = request.getHeader(X_REAL_IP_HEADER);
        if (realIp != null) {
            String trimmed = realIp.trim();
            // 网关注入的一定是单个已归一化的 IP；含逗号/空格说明不是网关写入的形态 ⇒ 不采信（fail-closed）
            if (!trimmed.isEmpty() && trimmed.indexOf(',') < 0 && trimmed.indexOf(' ') < 0) {
                return trimmed;
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? UNKNOWN : remote;
    }
}
