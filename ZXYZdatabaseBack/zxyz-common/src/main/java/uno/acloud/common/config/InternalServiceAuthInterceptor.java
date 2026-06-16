package uno.acloud.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内部服务调用鉴权拦截器。
 * 验证请求头中的 X-Internal-Service-Token 是否与配置值匹配。
 *
 * <p>使用 {@code @Value} 读取 {@code app.internal-service-token} 是故意设计：
 * application-common.yml 定义了共享默认值，各服务可覆盖。
 * 所有微服务通过 {@code spring.config.import} 引入公共配置后即可生效。</p>
 */
@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthInterceptor.class);

    private final String internalServiceToken;

    public InternalServiceAuthInterceptor(
            @Value("${app.internal-service-token:}") String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (StringUtils.isBlank(internalServiceToken)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "内部服务令牌未配置");
        }
        String token = request.getHeader(InternalServiceHeaders.TOKEN_HEADER);
        // 使用常量时间比较，防止时序攻击
        if (StringUtils.isBlank(token)
                || !MessageDigest.isEqual(internalServiceToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            log.warn("内部服务鉴权失败: expectedLen={}, providedLen={}",
                    internalServiceToken.length(),
                    token == null ? "null" : token.length());
            throw new BusinessException(ErrorCode.NO_PERMISSION, "内部服务鉴权失败");
        }
        return true;
    }
}
