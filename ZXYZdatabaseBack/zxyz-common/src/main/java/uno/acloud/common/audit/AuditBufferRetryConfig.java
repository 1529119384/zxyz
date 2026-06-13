package uno.acloud.common.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

@Configuration
public class AuditBufferRetryConfig {

    @Bean
    @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)
    public AuditBufferRetryScheduler auditBufferRetryScheduler(AuditEventPublisher auditEventPublisher) {
        return new AuditBufferRetryScheduler(auditEventPublisher);
    }
}
