package uno.acloud.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.lang.Nullable;
import uno.acloud.satoken.AuthServicePort;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 操作日志切面基类。
 * <p>
 * 子类只需提供 {@code @Component} 注解和 {@link #getServiceName()} 即可。
 */
@Slf4j
@RequiredArgsConstructor
@Aspect
public abstract class AbstractLogAspect {

    private static final int MAX_LOG_TEXT_LENGTH = 1000;
    private static final Pattern SENSITIVE_JSON_PATTERN = Pattern.compile(
            "(?i)(\"(?:password|token|secret|authorization|credential|apikey|api_key)\"\\s*:\\s*\")([^\"]*)(\")"
    );

    private final AuditEventPublisher auditEventPublisher;
    private final ObjectMapper objectMapper;
    private final AuthServicePort authServicePort;

    /**
     * 子类提供当前服务名称，用于审计日志中的 serviceName 字段。
     */
    protected abstract String getServiceName();

    @Around("@annotation(uno.acloud.common.audit.Log)")
    public Object recordLog(ProceedingJoinPoint joinPoint) throws Throwable {
        Long operateUserId = resolveOperateUserId();
        LocalDateTime operateTime = LocalDateTime.now();
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = joinPoint.getSignature().getName();
        String methodParams = truncate(redactSensitive(Arrays.toString(joinPoint.getArgs())));

        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long end = System.currentTimeMillis();

        String returnValue = truncate(serializeResult(result));
        long costTime = end - start;
        OperateLog operateLog = new OperateLog(null, getServiceName(), operateUserId, operateTime,
                className, methodName, methodParams, returnValue, costTime);
        auditEventPublisher.publish(operateLog);
        return result;
    }

    @Nullable
    private Long resolveOperateUserId() {
        try {
            if (authServicePort.isLogin()) {
                return authServicePort.getCurrentUserId();
            }
        } catch (Exception e) {
            log.debug("获取当前登录用户失败", e);
        }
        return null;
    }

    @Nullable
    private String serializeResult(@Nullable Object result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.debug("操作日志返回值序列化失败，使用字符串兜底", e);
            return String.valueOf(result);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_LOG_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_TEXT_LENGTH) + "...";
    }

    private String redactSensitive(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SENSITIVE_JSON_PATTERN.matcher(value).replaceAll("$1***$3");
    }
}
