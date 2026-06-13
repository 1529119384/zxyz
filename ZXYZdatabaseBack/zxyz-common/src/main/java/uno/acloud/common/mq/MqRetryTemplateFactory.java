package uno.acloud.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * MQ 事件发布重试模板工厂。
 * 提供统一的 RetryTemplate 配置：3 次重试，1 秒固定退避，每次重试记录 warn 日志。
 */
public final class MqRetryTemplateFactory {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long BACKOFF_PERIOD = 1000L;

    private MqRetryTemplateFactory() {}

    public static RetryTemplate createDefault(String publisherName) {
        Logger logger = LoggerFactory.getLogger(publisherName);
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(new SimpleRetryPolicy(MAX_RETRY_ATTEMPTS));
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(BACKOFF_PERIOD);
        rt.setBackOffPolicy(backOff);
        rt.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                logger.warn("MQ事件发布失败，第{}次重试: {}", context.getRetryCount(), throwable.getMessage());
            }
        });
        return rt;
    }
}
