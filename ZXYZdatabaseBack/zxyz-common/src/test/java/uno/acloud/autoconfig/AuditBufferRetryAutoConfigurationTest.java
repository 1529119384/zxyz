package uno.acloud.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import uno.acloud.common.audit.AuditBufferRetryScheduler;
import uno.acloud.common.audit.AuditEventPublisher;

import static org.mockito.Mockito.mock;

/**
 * {@link AuditBufferRetryAutoConfiguration} 的回归测试（C-12）。
 *
 * <h2>它修的是什么</h2>
 * 原 {@code uno.acloud.common.audit.AuditBufferRetryConfig} 长这样：一个被
 * {@code @ComponentScan} 扫到的 {@code @Configuration}，其 {@code @Bean} 上挂着
 * {@code @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)}。
 *
 * <p>2026-09-21 用 {@code ApplicationContextRunner} 实测（三种注册形态）：</p>
 * <table border="1">
 *   <caption>实测结果</caption>
 *   <tr><th>注册形态</th><th>结果</th></tr>
 *   <tr><td>配置类先注册、调度后注册</td><td>{@code ABSENT}</td></tr>
 *   <tr><td>调度先注册、配置类后注册</td><td>{@code PRESENT}</td></tr>
 *   <tr><td><b>忠实复现线上形态</b>（同一个类上既 {@code @EnableScheduling} 又
 *       {@code @ComponentScan} 该包）</td><td><b>{@code ABSENT}</b></td></tr>
 * </table>
 *
 * <p>⇒ 线上属于第三行：<b>审计回退缓冲的定时重试任务从未注册过</b>，且无日志无异常。
 * 迁入 {@code uno.acloud.autoconfig} 并加 {@code @AutoConfiguration} 后，本类由
 * {@code DeferredImportSelector} 在全部常规解析<b>之后</b>处理，求值因此变为确定。</p>
 *
 * <h2>为什么两个方向都要钉</h2>
 * 只钉「启用调度 ⇒ 有 Bean」会漏掉「守卫被顺手删掉」的回归；只钉「没调度 ⇒ 没 Bean」
 * 会漏掉这次真正修的缺陷。两个方向都断言，才既保住修复、又保住守卫的意图。
 */
class AuditBufferRetryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditBufferRetryAutoConfiguration.class));

    /** 启用调度的最小上下文（同时提供 AuditEventPublisher，供调度任务构造注入）。 */
    @Configuration
    @EnableScheduling
    static class SchedulingEnabled {

        @Bean
        AuditEventPublisher auditEventPublisher() {
            return mock(AuditEventPublisher.class);
        }
    }

    @Test
    void registersSchedulerWhenSchedulingIsEnabled() {
        runner.withUserConfiguration(SchedulingEnabled.class).run(context -> {
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeanNamesForType(AuditBufferRetryScheduler.class).length == 1,
                    "启用 @EnableScheduling 时必须恰好注册一个 AuditBufferRetryScheduler —— "
                            + "C-12 的缺陷正是它静默缺失。实际存在的 Bean："
                            + java.util.Arrays.toString(context.getBeanNamesForType(AuditBufferRetryScheduler.class)));
        });
    }

    @Test
    void doesNotRegisterSchedulerWhenSchedulingIsAbsent() {
        runner.withBean("auditEventPublisher", AuditEventPublisher.class,
                        () -> mock(AuditEventPublisher.class))
                .run(context -> org.junit.jupiter.api.Assertions.assertEquals(
                        0,
                        context.getBeanNamesForType(AuditBufferRetryScheduler.class).length,
                        "未启用 @EnableScheduling 的服务不应凭空多出一个永不触发的定时任务 Bean —— "
                                + "这条守卫是刻意的，别为了『让它一定注册』而删掉"));
    }
}
