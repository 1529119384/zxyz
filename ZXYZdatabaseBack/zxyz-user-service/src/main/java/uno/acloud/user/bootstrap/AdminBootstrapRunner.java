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
 * <p>幂等：每次应用启动都会执行，但若目标用户名已存在则直接跳过，可安全重复运行（重启/重新部署）。
 * 任何异常都不会阻止应用启动——整体 try/catch，仅记日志。</p>
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_PASSWORD_LENGTH = 16;

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
                log.info("初始管理员 {} 已存在，跳过引导", LogMaskingUtil.maskUsername(username));
                return;
            }

            String rawPassword = bootstrap.getPassword();
            boolean generated = false;
            if (rawPassword == null || rawPassword.isEmpty()) {
                rawPassword = generateRandomPassword();
                generated = true;
            }

            Long userId = authService.createBootstrapAdmin(username, rawPassword);

            teamServicePermissionClient.assignBootstrapAdminRoleStrict(userId);

            if (generated) {
                // 随机密码仅在服务器私有日志中输出一次明文，便于部署者首次获取凭据。
                log.warn("已创建初始管理员账号 {}，随机生成密码（明文，仅此一行，请立即登录修改密码！）: {}",
                        LogMaskingUtil.maskUsername(username), rawPassword);
            } else {
                log.info("已创建初始管理员账号 {}", LogMaskingUtil.maskUsername(username));
            }
        } catch (Exception e) {
            log.error("初始管理员引导失败（应用继续启动）: username={}",
                    LogMaskingUtil.maskUsername(username), e);
        }
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
