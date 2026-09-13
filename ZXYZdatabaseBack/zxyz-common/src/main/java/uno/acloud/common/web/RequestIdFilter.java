package uno.acloud.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uno.acloud.common.InternalServiceHeaders;

import java.io.IOException;
import java.util.UUID;

/**
 * 从 {@code X-Request-Id} 恢复 MDC 的 {@code requestId}，让下游服务的日志行能带上链路 ID。
 *
 * <p><b>为什么需要它（此前链路追踪其实只有一半）：</b>网关的
 * {@code uno.acloud.gateway.filter.RequestIdFilter} 只负责「发号 + 透传」，
 * 而 Servlet 服务这一侧此前<b>没有任何地方</b>把请求头写回 MDC ——
 * 全仓库只有网关与 {@link uno.acloud.client.AbstractServiceClient} 两处引用 MDC。
 * 后果是：{@code AbstractServiceClient} 想透传 requestId 时 {@code MDC.get(...)} 恒为 null，
 * 跨服务调用链在第一跳就断了，日志里也永远查不到这个 ID。</p>
 *
 * <p><b>为什么顺序设为最高优先级：</b>本过滤器之后运行的任何过滤器/拦截器
 * （鉴权、限流、访问日志）打的日志都应带上 requestId，否则排查时恰恰缺了最关键的那几条。</p>
 *
 * <p><b>为什么必须在 finally 里清理：</b>Servlet 容器的工作线程是复用的。
 * 若只 {@code put} 不 {@code remove}，下一个复用该线程的请求会继承上一个请求的 ID ——
 * <b>一个错误的 ID 比没有 ID 更有误导性</b>（会把人引向完全无关的日志）。</p>
 *
 * <p><b>为什么只在 Servlet 上下文注册：</b>网关是 WebFlux（响应式），没有 Servlet
 * 过滤器链；{@link ConditionalOnWebApplication} 显式排除，与「网关的
 * {@code @ComponentScan} 不覆盖 {@code uno.acloud.common}」形成双保险。</p>
 *
 * @see uno.acloud.gateway.filter.RequestIdFilter 负责发号与透传的上游
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdFilter extends OncePerRequestFilter {

    /**
     * MDC 键名。必须与网关、{@link uno.acloud.client.AbstractServiceClient}
     * 以及 {@code application-common.yml} 里日志 pattern 的 {@code %X{requestId}} 三处保持一致。
     */
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(InternalServiceHeaders.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            // 直连服务（绕过网关，例如内部探针与本机调试）时没有人发号，这里兜底生成。
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        // 回写响应头：让调用方能拿"自己收到的响应"与"服务端日志"对上号。
        response.setHeader(InternalServiceHeaders.REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
