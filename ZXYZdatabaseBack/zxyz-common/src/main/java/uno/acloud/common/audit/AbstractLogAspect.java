package uno.acloud.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.lang.Nullable;
import uno.acloud.satoken.AuthServicePort;

import java.time.LocalDateTime;

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
        // 结构层脱敏：先按【对象】扫描参数对象的字段名（SensitiveDataMasker.maskArguments，
        // 内部用 isSensitiveFieldName 判断），命中敏感字段名则逐字段打码；再跑文本层兜底。
        // 必须在对象层做 —— 像「toString() 只返回值、不含键名」的类，
        // 拼成文本后「哪个值是口令」这信息已丢失，任何文本正则都补不回来。
        String methodParams = truncate(SensitiveDataMasker.maskArguments(joinPoint.getArgs()));

        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long end = System.currentTimeMillis();

        String returnValue = truncate(maskReturnValue(result));
        long costTime = end - start;
        OperateLog operateLog = new OperateLog(null, getServiceName(), operateUserId, operateTime,
                className, methodName, methodParams, returnValue, null, null, costTime);
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

    /**
     * 返回值脱敏：<b>保持 JSON 形状</b>做结构层打码（遍历 Jackson 树，键名敏感的节点置为 {@code ***}），
     * 再跑文本层兜底。
     *
     * <p>{@code @Log} 端点可能返回含口令/token 字段的 VO（如注册接口回显），
     * 故返回值的口径与参数一致，不因为「它是出参」就放宽。</p>
     *
     * <p><b>为什么不复用 {@link SensitiveDataMasker#maskArguments}（回归修复）</b>：
     * {@code return_value} 列一直是 Jackson 序列化的 JSON 文本（字符串返回值存为 {@code "ok"}）。
     * 初版实现直接套用 {@code maskArguments}，把形状改成了 {@code [ok]} ——
     * 破坏既有列契约且会让按下游 JSON 解析该列的消费方失效。
     * 现改为「先序列化成树 → 按真实字段名打码 → 再写回 JSON 文本」，形状不变。</p>
     */
    @Nullable
    private String maskReturnValue(@Nullable Object result) {
        if (result == null) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.valueToTree(result);
            // 非对象/数组（标量）走文本层即可；容器类型按字段名逐节点打码
            String json = objectMapper.writeValueAsString(SensitiveDataMasker.maskJsonTree(tree));
            // 结构层未命中的形态（如 Map 里拼进文本的凭据）再由文本层兜底
            return SensitiveDataMasker.mask(json);
        } catch (Exception e) {
            // 结构层异常不得影响审计发布：降级为「序列化 + 文本层」
            log.debug("操作日志返回值结构层脱敏失败，降级为文本层", e);
            return SensitiveDataMasker.mask(serializeResult(result));
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_LOG_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_TEXT_LENGTH) + "...";
    }
}
