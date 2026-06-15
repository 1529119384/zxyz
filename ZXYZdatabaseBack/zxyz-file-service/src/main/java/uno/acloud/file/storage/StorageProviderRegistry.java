package uno.acloud.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 存储提供者注册中心
 * <p>
 * Spring 自动注入所有 {@link StorageProvider} 实现，按 providerId 路由。
 * </p>
 */
@Slf4j
@Component
public class StorageProviderRegistry {

    private final Map<String, StorageProvider> providers;
    private final String defaultProviderId;

    public StorageProviderRegistry(List<StorageProvider> providerList,
                                   @Value("${app.storage.default-provider:oss}") String defaultProviderId) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(StorageProvider::providerId, Function.identity()));
        this.defaultProviderId = defaultProviderId;

        log.info("已注册存储提供者: {}", providers.keySet());
        log.info("默认存储提供者: {}", defaultProviderId);

        if (!providers.containsKey(defaultProviderId)) {
            log.warn("默认存储提供者 '{}' 未注册，可用提供者: {}", defaultProviderId, providers.keySet());
        }
    }

    /**
     * 按 ID 获取提供者
     *
     * @param providerId 提供者标识
     * @return 提供者实例
     * @throws BusinessException 如果提供者不存在
     */
    public StorageProvider getProvider(String providerId) {
        StorageProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "存储提供者不存在: " + providerId);
        }
        return provider;
    }

    /**
     * 获取默认提供者（新文件上传时使用）
     *
     * @return 默认提供者实例
     */
    public StorageProvider getDefaultProvider() {
        return getProvider(defaultProviderId);
    }

    /**
     * 根据文件节点解析提供者
     * <p>
     * 已有文件操作时使用，优先使用文件记录的提供者，null 时 fallback 到默认。
     * </p>
     *
     * @param fileNode 文件节点
     * @return 对应的提供者实例
     */
    public StorageProvider resolveForFile(FileNode fileNode) {
        if (fileNode != null && fileNode.getStorageProvider() != null) {
            return getProvider(fileNode.getStorageProvider());
        }
        return getDefaultProvider();
    }

    /**
     * 获取所有已注册的提供者列表（Admin UI 用）
     *
     * @return 不可修改的提供者列表
     */
    public List<StorageProvider> getAllEnabledProviders() {
        return Collections.unmodifiableList(List.copyOf(providers.values()));
    }
}
