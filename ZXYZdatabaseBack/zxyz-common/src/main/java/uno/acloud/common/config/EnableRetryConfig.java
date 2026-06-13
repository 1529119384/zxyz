package uno.acloud.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * 启用 Spring Retry 注解支持（@Retryable / @Recover）。
 * 当前 MQ 发布者使用编程式 RetryTemplate，此配置为未来注解式重试预留。
 */
@Configuration
@EnableRetry
public class EnableRetryConfig {
}
