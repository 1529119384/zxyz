package uno.acloud.user.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import uno.acloud.common.util.LogMaskingUtil;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.impl.AuthService;

/**
 * 部署时自动初始化初始管理员账号。
 *
 * <p>幂等：每次应用启动都会执行，但若目标用户名已存在则跳过创建，可安全重复运行（重启/重新部署）。</p>
 *
 * <p>角色分配采用后台线程重试：本服务启动可能早于 team-service 注册到注册中心（启动竞态，
 * 同步调用会报 "Service Instance cannot be null"），异步重试既不阻塞启动，又能保证角色最终就绪。</p>
 *
 * <p>任何同步阶段异常都不会阻止应用启动——整体 try/catch，仅记日志。</p>
 *
 * <p><b>[安全默认 · D1-#1] 口令绝不落日志、也不自动生成。</b>
 * 早期实现会在未配置口令时随机生成一个 16 位密码并把**明文**打进 WARN 日志；
 * 而日志会被 promtail 采集进 Loki ⇒ 等于把管理员口令长期留存在日志系统里，
 * 且「口令只存在于日志中」本身就意味着没有人真正持有一个可管理的凭据。
 * 现在：未配置 {@code BOOTSTRAP_ADMIN_PASSWORD} 时**拒绝创建**并给出可执行的指引，
 * 口令必须由部署者决定（scripts/init-secrets.sh 可生成并写入 .env）。</p>
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    /** 角色分配后台重试次数与间隔（24 × 15s = 6 分钟，覆盖 team-service 慢启动场景） */
    private static final int ROLE_RETRY_ATTEMPTS = 24;
    private static final long ROLE_RETRY_INTERVAL_MS = 15_000L;

    private final UserMapper userMapper;
    private final AuthService authService;
    private final TeamServicePermissionClient teamServicePermissionClient;
    private final ServiceProperties serviceProperties;

    public AdminBootstrapRunner(UserMapper userMapper,
                                AuthService authService,
                                TeamServicePermissionClient teamServicePermissionClient,
                                ServiceProperties serviceProperties) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.serviceProperties = serviceProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ServiceProperties.Bootstrap bootstrap = serviceProperties.getAdmin().getBootstrap();
        if (!bootstrap.isEnabled()) {
            log.info("初始管理员引导已禁用（app.admin.bootstrap.enabled=false），跳过");
            return;
        }

        String username = bootstrap.getUsername();
        try {
            User existing = userMapper.getByLoginIdentifier(username);
            if (existing != null) {
                log.info("初始管理员 {} 已存在，跳过创建；后台确保其具备角色", LogMaskingUtil.maskUsername(username));
                // 自愈：历史部署可能「创建成功但角色分配失败」（如 team-service 未就绪）。
                // 统一走 assignBootstrapAdminRoleStrict（team-service 侧幂等：该用户已有任意角色则 no-op）。
                // 不再走 ensureDefaultRole —— 后者已不允许把用户提权为管理员（审计 2.1.1）。
                ensureRoleAsync(existing.getId(), username);
                return;
            }

            String rawPassword = bootstrap.getPassword();
            if (rawPassword == null || rawPassword.isBlank()) {
                // [安全默认 · D1-#1] 不生成、不打印任何口令：
                //   * 日志会被 promtail 采集进 Loki ⇒ 明文口令等于长期留存；
                //   * 「随机生成 + 打进日志」还意味着没有人真正持有一个可管理的凭据。
                // 因此宁可拒绝创建（应用照常启动），并要求部署者显式提供口令。
                // 注意：此分支**不抛异常**，也不启动后台角色分配 —— 没有账号可分。
                log.error("未配置初始管理员口令（BOOTSTRAP_ADMIN_PASSWORD 为空），"
                        + "拒绝创建账号 {}。请先运行 scripts/init-secrets.sh 生成并写入 .env，"
                        + "或在 .env 中显式设置 BOOTSTRAP_ADMIN_PASSWORD，然后重启本服务。"
                        + "本次已跳过创建，应用继续启动。", LogMaskingUtil.maskUsername(username));
                return;
            }

            Long userId = authService.createBootstrapAdmin(username, rawPassword);
            log.info("已创建初始管理员账号 {}（口令来自 BOOTSTRAP_ADMIN_PASSWORD，不写入日志）",
                    LogMaskingUtil.maskUsername(username));

            ensureRoleAsync(userId, username);
        } catch (Exception e) {
            log.error("初始管理员引导失败（应用继续启动）: username={}",
                    LogMaskingUtil.maskUsername(username), e);
        }
    }

    /**
     * 后台线程重试角色分配，不阻塞应用启动与就绪判定。
     * <p>统一走 assignBootstrapAdminRoleStrict：新账号「必须授予管理员」，
     * 已存在账号的自愈也走同一条幂等路径（team-service 侧该用户已有任意角色则 no-op）。
     * 这样「把用户提权为系统管理员」的能力只存在于部署引导链路，
     * 不会通过 ensureDefaultRole 暴露给公网注册/登录路径。</p>
     */
    private void ensureRoleAsync(Long userId, String username) {
        Thread worker = new Thread(() -> {
            for (int attempt = 1; attempt <= ROLE_RETRY_ATTEMPTS; attempt++) {
                try {
                    teamServicePermissionClient.assignBootstrapAdminRoleStrict(userId);
                    log.info("初始管理员 {} 角色分配成功", LogMaskingUtil.maskUsername(username));
                    return;
                } catch (Exception e) {
                    log.warn("初始管理员 {} 角色分配第 {}/{} 次失败（team-service 可能尚未注册到注册中心，稍后重试）: {}",
                            LogMaskingUtil.maskUsername(username), attempt, ROLE_RETRY_ATTEMPTS, e.getMessage());
                }
                try {
                    Thread.sleep(ROLE_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log.error("初始管理员 {} 角色分配重试 {} 次后仍失败，请检查 team-service 状态或手动为其分配角色",
                    LogMaskingUtil.maskUsername(username), ROLE_RETRY_ATTEMPTS);
        }, "admin-bootstrap-role");
        worker.setDaemon(true);
        worker.start();
    }
}
