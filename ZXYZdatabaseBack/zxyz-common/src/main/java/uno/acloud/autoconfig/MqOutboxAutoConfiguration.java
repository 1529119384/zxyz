package uno.acloud.autoconfig;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import uno.acloud.common.mq.MqOutboxPublisher;
import uno.acloud.common.mq.MqOutboxRetryScheduler;

/**
 * 领域事件 Redis outbox 自动配置（60 号审计 🟠-2）。
 *
 * <p><b>开关</b>：{@code app.mq.outbox.enabled=true} 时才装配 —— outbox 只对真正发布
 * 领域事件的服务（team / user）有意义，其余服务零成本（不建 Bean、不建调度任务）。</p>
 *
 * <p><b>为什么放 {@code uno.acloud.autoconfig} 而不是 {@code uno.acloud.common.*}</b>：
 * 各服务启动类 {@code @ComponentScan} 覆盖 {@code uno.acloud.common}，扫到的配置类会
 * 先于自动配置注册，{@code @ConditionalOnBean} 恒定求值为 false、Bean 静默不创建
 * （C-10/C-12 实测，见 {@link AuditBufferRetryAutoConfiguration} 类注释的完整论证）。
 * 本类依赖 {@link RabbitTemplate} 与调度器
 * （{@code @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)}），
 * 两个条件都必须在确定时机求值，故迁入本包。RabbitTemplate 条件用
 * {@code @ConditionalOnBean(RabbitTemplate.class)}（类条件在 Boot 4 模块化下
 * 不再可靠引用 {@code RabbitAutoConfiguration}，见 48 号迁移记录；Bean 条件等价且只依赖
 * 上下文已装配的 Bean）。</p>
 *
 * <p><b> publishers 如何拿到实例</b>：注入 {@link MqOutboxPublisher} 即可（开关关闭时
 * 无该 Bean，发布方用 {@code ObjectProvider} 容错降级——outbox 缺席等价于旧行为：
 * 失败仅日志）。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "app.mq.outbox", name = "enabled", havingValue = "true")
public class MqOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MqOutboxPublisher.class)
    @ConditionalOnBean(RabbitTemplate.class)
    public MqOutboxPublisher mqOutboxPublisher(
            StringRedisTemplate stringRedisTemplate,
            RabbitTemplate rabbitTemplate,
            @Value("${spring.application.name:unknown}") String serviceName) {
        return new MqOutboxPublisher(stringRedisTemplate, serviceName) {
            @Override
            protected RabbitTemplate rabbitTemplate() {
                return rabbitTemplate;
            }
        };
    }

    @Bean
    @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)
    public MqOutboxRetryScheduler mqOutboxRetryScheduler(MqOutboxPublisher mqOutboxPublisher) {
        return new MqOutboxRetryScheduler(mqOutboxPublisher);
    }
}
