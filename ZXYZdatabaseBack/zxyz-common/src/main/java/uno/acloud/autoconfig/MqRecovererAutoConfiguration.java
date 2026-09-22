package uno.acloud.autoconfig;

import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import uno.acloud.common.mq.LoggingMessageRecoverer;

/**
 * 把 {@link LoggingMessageRecoverer} 注册为全局唯一的 {@link MessageRecoverer}（审计 L7 修复）。
 *
 * <p><b>装配原理（已对 Spring Boot 3.5.14 源码核实，不是推测）</b>：</p>
 * <ol>
 *   <li>{@code RabbitAnnotationDrivenConfiguration} 通过
 *       {@code ObjectProvider<MessageRecoverer>} + {@code getIfUnique()} 取 recoverer，
 *       再交给 {@code SimpleRabbitListenerContainerFactoryConfigurer}。</li>
 *   <li>{@code AbstractRabbitListenerContainerFactoryConfigurer#configure} 中是
 *       {@code MessageRecoverer recoverer = (this.messageRecoverer != null) ? this.messageRecoverer
 *       : new RejectAndDontRequeueRecoverer(); builder.recoverer(recoverer);}，
 *       且这段只在 {@code retryConfig.isEnabled()} 时执行。</li>
 * </ol>
 * <p>本项目 {@code application-common.yml} 的 {@code spring.rabbitmq.listener.simple.retry.enabled=true}，
 * 所以只要本处提供一个 recoverer Bean，它就会替换掉默认实现，重试耗尽时即产生 ERROR 日志。</p>
 *
 * <h2>两个必须守住的约束</h2>
 * <ul>
 *   <li><strong>全容器只能有一个 {@link MessageRecoverer}</strong>。因为上游用的是
 *       {@code getIfUnique()}：一旦出现两个，Boot 判定「不唯一」而整体放弃，静默退回默认实现，
 *       本配置就再也不生效（且不会有任何报错）。故此处用
 *       {@code @ConditionalOnMissingBean(MessageRecoverer.class)} 让业务侧的定义优先，
 *       同时各服务<strong>不应</strong>再新增第二个。</li>
 *   <li><strong>仍然不要给它加 {@code @ConditionalOnBean} 之类的依赖型条件</strong>。
 *       本类原位于 {@code uno.acloud.common.config}（会被各服务 {@code @ComponentScan} 扫到），
 *       那里写依赖型条件会因「先被扫描、后注册自动配置」而恒定求值为 false、Bean 静默不创建；
 *       2026-09-21 已随 C-10 迁入 {@code uno.acloud.autoconfig}（不被任何 {@code @ComponentScan} 覆盖）。
 *       条件仍只用「类是否在 classpath」（{@code @ConditionalOnClass}）与「缺省才建」
 *       （{@code @ConditionalOnMissingBean}）两种安全形态。</li>
 * </ul>
 *
 * <p>各服务（含未消费 DLQ 的 file / project / share / team）都会加载本配置 —— 这正是目的：
 * 那四个服务的死信队列没有消费者，全局 recoverer 是它们<strong>唯一</strong>的可观测信号。</p>
 */
@AutoConfiguration
@ConditionalOnClass(MessageRecoverer.class)
public class MqRecovererAutoConfiguration {

    /**
     * 注册全局日志型 recoverer。
     *
     * @param serviceName      来源服务名，取自 {@code spring.application.name}
     * @param retryMaxAttempts 消费重试上限，与 {@code spring.rabbitmq.listener.simple.retry.max-attempts}
     *                         保持同一份配置源（默认值与 {@code application-common.yml} 一致为 3）。
     *                         日志里显式打出它，是为了让排障者一眼看到「这条消息已经被试了几次」——
     *                         重试是在内存中进行的，消息头里不会留下次数痕迹。
     */
    @Bean
    @ConditionalOnMissingBean(MessageRecoverer.class)
    public MessageRecoverer loggingMessageRecoverer(
            @Value("${spring.application.name:unknown}") String serviceName,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int retryMaxAttempts) {
        return new LoggingMessageRecoverer(serviceName, retryMaxAttempts);
    }
}
