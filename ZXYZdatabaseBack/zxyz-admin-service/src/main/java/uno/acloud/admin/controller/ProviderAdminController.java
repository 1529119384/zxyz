package uno.acloud.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;

import java.util.Map;

/**
 * 提供者统一管理控制器
 * <p>
 * 聚合存储提供者和邮件提供者的管理接口，提供统一的 Admin API。
 * </p>
 */
@Tag(name = "提供者管理", description = "存储和邮件提供者管理接口")
@RestController
@RequestMapping("/providers")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class ProviderAdminController {

    private final StorageProviderClient storageProviderClient;
    private final EmailProviderClient emailProviderClient;

    public ProviderAdminController(StorageProviderClient storageProviderClient,
                                   EmailProviderClient emailProviderClient) {
        this.storageProviderClient = storageProviderClient;
        this.emailProviderClient = emailProviderClient;
    }

    // ==================== 存储提供者 ====================

    @Operation(summary = "获取所有存储提供者")
    @GetMapping("/storage")
    public Result<Object> listStorageProviders() {
        return Result.of(storageProviderClient.listAll());
    }

    @Operation(summary = "更新存储提供者配置")
    @PatchMapping("/storage/{providerId}")
    public Result<Void> updateStorageProvider(
            @Parameter(description = "提供者标识") @PathVariable String providerId,
            @RequestBody Map<String, Object> request) {
        storageProviderClient.updateConfig(providerId, request);
        return Result.success();
    }

    @Operation(summary = "存储提供者健康检查")
    @GetMapping("/storage/{providerId}/health")
    public Result<Object> storageProviderHealth(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {
        return Result.of(storageProviderClient.healthCheck(providerId));
    }

    // ==================== 邮件提供者 ====================

    @Operation(summary = "获取所有邮件提供者")
    @GetMapping("/email")
    public Result<Object> listEmailProviders() {
        return Result.of(emailProviderClient.listAll());
    }

    @Operation(summary = "更新邮件提供者配置")
    @PatchMapping("/email/{providerId}")
    public Result<Void> updateEmailProvider(
            @Parameter(description = "提供者标识") @PathVariable String providerId,
            @RequestBody Map<String, Object> request) {
        emailProviderClient.updateConfig(providerId, request);
        return Result.success();
    }

    @Operation(summary = "邮件提供者健康检查")
    @GetMapping("/email/{providerId}/health")
    public Result<Object> emailProviderHealth(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {
        return Result.of(emailProviderClient.healthCheck(providerId));
    }
}
