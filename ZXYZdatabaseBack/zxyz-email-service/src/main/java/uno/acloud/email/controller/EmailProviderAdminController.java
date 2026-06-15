package uno.acloud.email.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
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
import uno.acloud.email.provider.EmailProvider;
import uno.acloud.email.provider.EmailProviderRegistry;

import java.util.List;
import java.util.Map;

/**
 * 邮件提供者管理控制器
 */
@Slf4j
@Tag(name = "邮件提供者管理", description = "Admin 邮件提供者配置接口")
@RestController
@RequestMapping("/api/admin/email-providers")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class EmailProviderAdminController {

    private final EmailProviderRegistry registry;

    public EmailProviderAdminController(EmailProviderRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "列出所有邮件提供者")
    @GetMapping
    public Result<List<EmailProviderVO>> listAll() {
        List<EmailProvider> providers = registry.getAllProviders();
        List<EmailProviderVO> voList = providers.stream()
                .map(this::toVO)
                .toList();
        return Result.of(voList);
    }

    @Operation(summary = "更新邮件提供者配置")
    @PatchMapping("/{providerId}")
    public Result<Void> updateConfig(
            @Parameter(description = "提供者标识") @PathVariable String providerId,
            @RequestBody UpdateProviderRequest request) {
        // 验证提供者存在
        registry.getProvider(providerId);

        log.info("更新邮件提供者配置，providerId: {}, request: {}", providerId, request);
        // 实际更新逻辑需要根据具体需求实现
        return Result.success();
    }

    @Operation(summary = "邮件提供者健康检查")
    @GetMapping("/{providerId}/health")
    public Result<Map<String, Object>> healthCheck(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {

        EmailProvider provider = registry.getProvider(providerId);
        boolean healthy = false;
        String message = "";

        try {
            String result = provider.testConnection();
            healthy = true;
            message = result;
        } catch (Exception e) {
            message = "提供者异常: " + e.getMessage();
            log.error("邮件提供者健康检查失败，providerId: {}", providerId, e);
        }

        return Result.of(Map.of(
                "providerId", providerId,
                "healthy", healthy,
                "message", message
        ));
    }

    private EmailProviderVO toVO(EmailProvider provider) {
        return new EmailProviderVO(
                provider.providerId(),
                provider.displayName(),
                true  // 默认启用，实际应该从配置读取
        );
    }

    /**
     * 邮件提供者 VO
     */
    public record EmailProviderVO(
            String providerId,
            String displayName,
            boolean enabled
    ) {}

    /**
     * 更新提供者配置请求
     */
    public static class UpdateProviderRequest {
        private Boolean enabled;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
