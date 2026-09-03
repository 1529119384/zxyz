package uno.acloud.email.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 邮件提供者注册中心
 * <p>
 * Spring 自动注入所有 {@link EmailProvider} 实现，按 providerId 路由。
 * </p>
 */
@Slf4j
@Component
// 重启生效（无需 @RefreshScope）：构造期一次性装配 provider 映射；且 app.email.default-provider 属静态配置，
// 不在 zxyz-dynamic.yml 热更清单内。
public class EmailProviderRegistry {

    private final Map<String, EmailProvider> providers;
    private final String defaultProviderId;

    public EmailProviderRegistry(List<EmailProvider> providerList,
                                 @Value("${app.email.default-provider:smtp}") String defaultProviderId) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(EmailProvider::providerId, Function.identity()));
        this.defaultProviderId = defaultProviderId;

        log.info("已注册邮件提供者: {}", providers.keySet());
        log.info("默认邮件提供者: {}", defaultProviderId);

        if (!providers.containsKey(defaultProviderId)) {
            log.warn("默认邮件提供者 '{}' 未注册，可用提供者: {}", defaultProviderId, providers.keySet());
        }
    }

    /**
     * 按 ID 获取提供者
     *
     * @param providerId 提供者标识
     * @return 提供者实例
     * @throws BusinessException 如果提供者不存在
     */
    public EmailProvider getProvider(String providerId) {
        EmailProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "邮件提供者不存在: " + providerId);
        }
        return provider;
    }

    /**
     * 获取默认提供者
     *
     * @return 默认提供者实例
     */
    public EmailProvider getDefaultProvider() {
        return getProvider(defaultProviderId);
    }

    /**
     * 获取所有已注册的提供者列表
     *
     * @return 不可修改的提供者列表
     */
    public List<EmailProvider> getAllProviders() {
        return Collections.unmodifiableList(List.copyOf(providers.values()));
    }
}
