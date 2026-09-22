package uno.acloud.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import uno.acloud.common.audit.AuditBufferRetryScheduler;
import uno.acloud.common.audit.AuditEventPublisher;

/**
 * 审计缓冲重试任务的自动配置（原 {@code uno.acloud.common.audit.AuditBufferRetryConfig}）。
 *
 * <h2>为什么必须迁到 {@code uno.acloud.autoconfig}（C-12，已用命令证实）</h2>
 * 原类位于 {@code uno.acloud.common.audit} —— 一个被各服务的 {@code @ComponentScan}
 * 覆盖的包（其 basePackages 含 {@code uno.acloud.common}）。
 * 组件扫描得到的配置类在 {@code ConfigurationClassBeanDefinitionReader} 里
 * <b>按插入顺序</b>注册 {@code @Bean}，而 {@code @EnableScheduling} 的 {@code @Import} 属于
 * 启动类自身的解析过程 —— 两者谁先谁后，取决于 Spring 内部的解析步序，<b>对使用者是不可依赖的</b>。
 *
 * <p>用 {@code ApplicationContextRunner} 实测（2026-09-21，zxyz-common）：</p>
 * <ul>
 *   <li>配置类<b>先</b>注册 ⇒ {@code AuditBufferRetryScheduler} <b>ABSENT</b>；</li>
 *   <li>调度<b>先</b>注册 ⇒ <b>PRESENT</b>；</li>
 *   <li><b>忠实复现线上形态</b>（同一个类上既有 {@code @EnableScheduling} 又有
 *       {@code @ComponentScan("uno.acloud.common.audit")}）⇒ <b>ABSENT</b>。</li>
 * </ul>
 * <p>即：{@code @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)} 在被扫包内
 * <b>恒定求值为 false</b>，审计回退缓冲的定时重试任务<b>从未注册过</b>，且全过程无任何日志或异常
 * —— 与 {@code PermissionCacheAutoConfiguration} / {@code TeamPermissionCacheAutoConfiguration}
 * 属于同一族缺陷（那两条已先期迁入本包）。</p>
 *
 * <h2>迁移为什么能修好它</h2>
 * {@code AutoConfiguration.imports} 里的类由 {@code AutoConfigurationImportSelector}
 * （{@code DeferredImportSelector}）在<b>全部常规解析结束之后</b>才处理 ⇒ 其配置类总是排在
 * {@code SchedulingConfiguration}（由 {@code @EnableScheduling} 在常规阶段引入）<b>之后</b>，
 * 于是本类 {@code @Bean} 上的条件求值时
 * {@code ScheduledAnnotationBeanPostProcessor} 的 BeanDefinition 一定已就位。
 * 求值因此从「依赖内部步序」变为<b>确定</b>。
 *
 * <p>{@code after = TaskSchedulingAutoConfiguration.class} 把这条依赖显式写进契约 ——
 * 它是 Spring Boot 自己的调度自动配置（{@code org.springframework.boot.autoconfigure.task}），
 * 且自身带 {@code @ConditionalOnBean(name = scheduledAnnotationBeanPostProcessor)}，
 * 语义上正对应「调度已启用、处理器已就位」这一前提。若此处漏写 {@code after}，
 * 排序会退回声明顺序，契约就重新变成隐式的了。</p>
 *
 * <p><b>守卫刻意保留</b>：未启用 {@code @EnableScheduling} 的服务不应凭空多出一个永不触发的
 * 定时任务 Bean。回归测试同时钉住两个方向（启用 ⇒ 必须有、未启用 ⇒ 必须没有），见
 * {@code uno.acloud.autoconfig.AuditBufferRetryAutoConfigurationTest}。</p>
 */
@AutoConfiguration(after = TaskSchedulingAutoConfiguration.class)
public class AuditBufferRetryAutoConfiguration {

    @Bean
    @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)
    public AuditBufferRetryScheduler auditBufferRetryScheduler(AuditEventPublisher auditEventPublisher) {
        return new AuditBufferRetryScheduler(auditEventPublisher);
    }
}
