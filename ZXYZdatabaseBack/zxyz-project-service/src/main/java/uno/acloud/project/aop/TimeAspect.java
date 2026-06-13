package uno.acloud.project.aop;


import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect//aop 切面
@Component
@ConditionalOnProperty(prefix = "app.performance", name = "time-aspect-enabled", havingValue = "true", matchIfMissing = true)
public class TimeAspect {
    // TimeAspect 仅用于开发期性能观察，只输出 service 层方法耗时，不承担审计落库职责。
    @Around("execution(* uno.acloud.project.service.impl..*.*(..))")//切入点表达式
    public Object recordTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long end = System.currentTimeMillis();
            log.info("性能观察 - 方法 {} 耗时: {} ms", joinPoint.getSignature(), end - start);
        }
    }
}
