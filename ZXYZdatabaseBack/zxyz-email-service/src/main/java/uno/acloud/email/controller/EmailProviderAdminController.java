package uno.acloud.email.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.email.provider.EmailProvider;
import uno.acloud.email.provider.EmailProviderRegistry;

import java.util.List;
import java.util.Map;

/**
 * 邮件提供者（{@link EmailProvider} SPI）内省接口 —— <b>只读</b>。
 *
 * <p>职责边界（重要，勿随意扩展）：
 * <ul>
 *   <li>本控制器只回答两个问题：<b>当前注册了哪些 provider 实现</b>、<b>某个 provider 能否连通</b>。
 *       数据源是 {@link EmailProviderRegistry} 与 {@link EmailProvider#testConnection()}，
 *       二者都是真实数据，不做任何加工的猜测。</li>
 *   <li>真正的「邮件服务器配置」写入面 <b>不在本控制器</b>：
 *       配置的增删改、激活、连通性测试由 {@code EmailServerConfigService} 承载，
 *       并经 {@code /api/admin/email/server-configs}（网关映射到
 *       {@code EmailInternalController} 的 {@code /api/email/internal/server-configs}）对外提供；
 *       「发送功能是否开启」由 {@code /api/admin/email/runtime-status} 提供。
 *       <b>请勿在本控制器新增写接口</b> —— 历史上这里曾有一个
 *       {@code PATCH /{providerId}}：它收下 {@code {enabled}} 后只校验 provider 存在、打日志、
 *       返回 {@code Result.success()}，既不落库也不生效，而 {@code toVO} 又把 {@code enabled}
 *       硬编码为 {@code true}。结果是运维点「启用/停用」拿到 200 但配置毫无变化，
 *       列表也永远回 {@code enabled=true} —— 一个对外说谎的接口。
 *       该接口已删除；如需运行时开关能力，请扩展 {@code EmailServerConfigService}。</li>
 * </ul>
 *
 * <p>另注：当前真实发送路径为 {@code EmailDispatchService → SimpleJavaMailSender}，
 * 并<b>不</b>经过 {@link EmailProvider} SPI；本 SPI 目前是「已注册实现的观测面」，
 * 尚未接入发送链路。若后续要让发送真正可插拔，应改造 {@code EmailDispatchService}
 * 令其按 provider 路由，而不是在此控制器上做文章。
 */
@Slf4j
@Tag(name = "邮件提供者管理", description = "Admin 邮件提供者只读内省接口（配置写入见 /api/admin/email/server-configs）")
@RestController
@RequestMapping("/api/admin/email-providers")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class EmailProviderAdminController {

    private final EmailProviderRegistry registry;

    public EmailProviderAdminController(EmailProviderRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "列出所有已注册的邮件提供者")
    @GetMapping
    public Result<List<EmailProviderVO>> listAll() {
        List<EmailProvider> providers = registry.getAllProviders();
        List<EmailProviderVO> voList = providers.stream()
                .map(EmailProviderAdminController::toVO)
                .toList();
        return Result.of(voList);
    }

    @Operation(summary = "邮件提供者健康检查")
    @GetMapping("/{providerId}/health")
    public Result<Map<String, Object>> healthCheck(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {

        // 先按 ID 取 provider：不存在时由 registry 抛出「邮件提供者不存在: xxx」，
        // 避免把「ID 打错」误报成「连接失败」。
        EmailProvider provider = registry.getProvider(providerId);

        boolean healthy;
        String message;
        try {
            message = provider.testConnection();
            healthy = true;
        } catch (Exception e) {
            healthy = false;
            message = "提供者异常: " + e.getMessage();
            log.error("邮件提供者健康检查失败，providerId: {}", providerId, e);
        }

        return Result.of(Map.of(
                "providerId", providerId,
                "healthy", healthy,
                "message", message
        ));
    }

    /**
     * 邮件提供者 VO。
     *
     * <p>刻意<b>不</b>包含 {@code enabled} 字段：provider 是 Spring 装配出来的实现，
     * 不存在「启用/停用」这一可持久化状态（{@code EmailProvider} SPI 亦无此概念）。
     * 「发送功能是否开启」是系统级状态，请查 {@code /api/admin/email/runtime-status}。
     */
    public record EmailProviderVO(
            String providerId,
            String displayName
    ) {}

    private static EmailProviderVO toVO(EmailProvider provider) {
        return new EmailProviderVO(provider.providerId(), provider.displayName());
    }
}
