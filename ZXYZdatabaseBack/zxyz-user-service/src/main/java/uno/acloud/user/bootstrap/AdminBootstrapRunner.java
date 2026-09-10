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

import java.security.SecureRandom;

/**
 * 部署时自动初始化初始管理员账号。
 *
 * <p>幂等：每次应用启动都会执行，但若目标用户名已存在则跳过创建，可安全重复运行（重启/重新部署）。</p>
 *
 * <p>角色分配采用后台线程重试：本服务启动可能早于 team-service 注册到注册中心（启动竞态，
 * 同步调用会报 "Service Instance cannot be null"），异步重试既不阻塞启动，又能保证角色最终就绪。</p>
 *
 * <p>任何同步阶段异常都不会阻止应用启动——整体 try/catch，仅记日志。</p>
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_PASSWORD_LENGTH = 16;
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
                // ensureDefaultRole 幂等：已有角色则 no-op；无角色且系统尚无其他管理员则授 SYSTEM_ADMIN
                //（与登录时的默认角色语义一致）。
                ensureRoleAsync(existing.getId(), username, true);
                return;
            }

            String rawPassword = bootstrap.getPassword();
            boolean generated = false;
            if (rawPassword == null || rawPassword.isEmpty()) {
                rawPassword = generateRandomPassword();
                generated = true;
            }

            Long userId = authService.createBootstrapAdmin(username, rawPassword);

            if (generated) {
                // 必须在角色分配之前打印：若角色分配失败（如 team-service 未就绪），密码也不能丢
                log.warn("已创建初始管理员账号 {}，随机生成密码（明文，仅此一行，请立即登录修改密码！）: {}",
                        LogMaskingUtil.maskUsername(username), rawPassword);
            } else {
                log.info("已创建初始管理员账号 {}", LogMaskingUtil.maskUsername(username));
            }

            ensureRoleAsync(userId, username, false);
        } catch (Exception e) {
            log.error("初始管理员引导失败（应用继续启动）: username={}",
                    LogMaskingUtil.maskUsername(username), e);
        }
    }

    /**
     * 后台线程重试角色分配，不阻塞应用启动与就绪判定。
     * healExisting=true 时走 ensureDefaultRole（幂等自愈，语义同登录默认角色）；
     * 否则走 assignBootstrapAdminRoleStrict（新账号必须授予管理员，失败抛异常）。
     */
    private void ensureRoleAsync(Long userId, String username, boolean healExisting) {
        Thread worker = new Thread(() -> {
            for (int attempt = 1; attempt <= ROLE_RETRY_ATTEMPTS; attempt++) {
                try {
                    if (healExisting) {
                        teamServicePermissionClient.ensureDefaultRole(userId, username);
                    } else {
                        teamServicePermissionClient.assignBootstrapAdminRoleStrict(userId);
                    }
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

    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(RANDOM_PASSWORD_LENGTH);
        for (int i = 0; i < RANDOM_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
