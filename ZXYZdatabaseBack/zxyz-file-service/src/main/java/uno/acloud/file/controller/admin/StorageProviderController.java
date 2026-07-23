package uno.acloud.file.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.file.infrastructure.entity.ServiceProviderConfig;
import uno.acloud.file.infrastructure.mapper.ServiceProviderConfigMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 存储提供者管理控制器
 */
@Slf4j
@Tag(name = "存储提供者管理", description = "Admin 存储提供者配置接口")
@RestController
@RequestMapping("/api/admin/storage-providers")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class StorageProviderController {

    private final StorageProviderRegistry registry;
    private final ServiceProviderConfigMapper configMapper;

    public StorageProviderController(StorageProviderRegistry registry,
                                     ServiceProviderConfigMapper configMapper) {
        this.registry = registry;
        this.configMapper = configMapper;
    }

    @Operation(summary = "列出所有存储提供者")
    @GetMapping
    public Result<List<StorageProviderVO>> listAll() {
        List<StorageProvider> providers = registry.getAllEnabledProviders();
        List<StorageProviderVO> voList = providers.stream()
                .map(this::toVO)
                .toList();
        return Result.of(voList);
    }

    @Operation(summary = "更新存储提供者配置")
    @PatchMapping("/{providerId}")
    public Result<Void> updateConfig(
            @Parameter(description = "提供者标识") @PathVariable String providerId,
            @RequestBody UpdateProviderRequest request) {

        ServiceProviderConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ServiceProviderConfig>()
                        .eq(ServiceProviderConfig::getProviderId, providerId));

        if (config == null) {
            // 如果配置不存在，创建新配置
            config = new ServiceProviderConfig();
            config.setProviderId(providerId);
            config.setDisplayName(request.getDisplayName() != null ? request.getDisplayName() : providerId);
            config.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
            config.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
            config.setConfigJson(request.getConfigJson());
            config.setCreateTime(LocalDateTime.now());
            config.setModifyTime(LocalDateTime.now());
            configMapper.insert(config);
        } else {
            // 更新现有配置
            if (request.getEnabled() != null) {
                config.setEnabled(request.getEnabled());
            }
            if (request.getIsDefault() != null) {
                config.setIsDefault(request.getIsDefault());
            }
            if (request.getConfigJson() != null) {
                config.setConfigJson(request.getConfigJson());
            }
            config.setModifyTime(LocalDateTime.now());
            configMapper.updateById(config);
        }

        // 如果设置了默认，需要将其他提供者的 is_default 设为 false
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearOtherDefaults(providerId);
        }

        log.info("更新存储提供者配置，providerId: {}", providerId);
        return Result.success();
    }

    @Operation(summary = "存储提供者健康检查")
    @GetMapping("/{providerId}/health")
    public Result<Map<String, Object>> healthCheck(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {

        StorageProvider provider = registry.getProvider(providerId);
        boolean healthy;
        String message;
        try {
            healthy = provider.healthCheck();
            message = healthy ? "提供者正常" : "提供者异常";
        } catch (Exception e) {
            healthy = false;
            message = "提供者异常: " + e.getMessage();
            log.error("存储提供者健康检查失败，providerId: {}", providerId, e);
        }

        return Result.of(Map.of(
                "providerId", providerId,
                "healthy", healthy,
                "message", message
        ));
    }

    private StorageProviderVO toVO(StorageProvider provider) {
        // 从配置表获取配置
        ServiceProviderConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ServiceProviderConfig>()
                        .eq(ServiceProviderConfig::getProviderId, provider.providerId()));

        boolean enabled = config != null ? Boolean.TRUE.equals(config.getEnabled()) : true;
        boolean isDefault = config != null ? Boolean.TRUE.equals(config.getIsDefault()) : false;

        return new StorageProviderVO(
                provider.providerId(),
                provider.displayName(),
                enabled,
                isDefault,
                provider.supportsPresignedUpload(),
                provider.supportsPresignedDownload()
        );
    }

    private void clearOtherDefaults(String currentProviderId) {
        List<ServiceProviderConfig> allConfigs = configMapper.selectList(null);
        for (ServiceProviderConfig config : allConfigs) {
            if (!config.getProviderId().equals(currentProviderId)
                    && Boolean.TRUE.equals(config.getIsDefault())) {
                config.setIsDefault(false);
                config.setModifyTime(LocalDateTime.now());
                configMapper.updateById(config);
            }
        }
    }

    /**
     * 存储提供者 VO
     */
    public record StorageProviderVO(
            String providerId,
            String displayName,
            boolean enabled,
            boolean isDefault,
            boolean supportsPresignedUpload,
            boolean supportsPresignedDownload
    ) {}

    /**
     * 更新提供者配置请求
     */
    public static class UpdateProviderRequest {
        private String displayName;
        private Boolean enabled;
        private Boolean isDefault;
        private String configJson;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Boolean getIsDefault() { return isDefault; }
        public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
        public String getConfigJson() { return configJson; }
        public void setConfigJson(String configJson) { this.configJson = configJson; }
    }
}
