package uno.acloud.email.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 邮件异步发送线程池。
 *
 * <p><b>B-14（2026-10-03）：拒绝策略由 CallerRuns 改为 Abort</b>。</p>
 * <ul>
 *   <li><b>为什么不能 CallerRuns</b>：队列（1000）满时它让<b>提交线程亲自执行任务</b> ——
 *       对本池而言就是 HTTP 请求线程同步跑一遍 SMTP 发送（TLS 握手 + 投递，秒级），
 *       管理员批量模板邮件（50 收件人/批）时接口延迟被放大数十倍。</li>
 *   <li><b>为什么 Abort 是安全的</b>：每条邮件在提交任务<b>之前</b>已落库为 PENDING
 *       （{@code EmailDispatchService.createPendingRecord}，事务先于提交线程池），
 *       拒绝只丢「触发时机」不丢「记录」；被拒记录由 {@code EmailRetryTask} 的
 *       定时 {@code dispatchDueRecords} 兜底捞起发送。</li>
 *   <li><b>提交端配套</b>：{@code EmailDispatchService.triggerAsyncIfDue} 的两条提交路径
 *       均已 try-catch（同批 B-14），拒绝异常记 WARN 后吞掉，不冒泡进事务同步链或请求线程。</li>
 * </ul>
 */
@Configuration
public class EmailAsyncConfig {

    @Bean("emailTaskExecutor")
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("email-send-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        // 队列满时直接拒绝（抛 RejectedExecutionException），由调用方 try-catch 承接；
        // 已落库的 PENDING 记录由 EmailRetryTask 定时重试兜底，投递不丢。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
