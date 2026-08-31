package uno.acloud.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内部服务调用鉴权拦截器。
 * 读取请求头 X-Internal-Caller-Service 与 X-Internal-Service-Token，交由 {@link InternalAllowList}
 * 按「允许来源 → 来源密钥」白名单矩阵校验（矩阵未配置时回退单 token）。
 */
@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthInterceptor.class);

    private final InternalAllowList internalAllowList;

    public InternalServiceAuthInterceptor(InternalAllowList internalAllowList) {
        this.internalAllowList = internalAllowList;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String caller = request.getHeader(InternalServiceHeaders.CALLER_SERVICE_HEADER);
        String token = request.getHeader(InternalServiceHeaders.TOKEN_HEADER);
        if (!internalAllowList.verify(caller, token)) {
            // 鉴权失败日志不暴露令牌长度，避免辅助枚举（安全最小暴露）
            log.warn("内部服务鉴权失败: caller={}, tokenProvided={}",
                    caller == null ? "null" : caller,
                    token == null ? "null" : "***");
            throw new BusinessException(ErrorCode.NO_PERMISSION, "内部服务鉴权失败");
        }
        return true;
    }
}
