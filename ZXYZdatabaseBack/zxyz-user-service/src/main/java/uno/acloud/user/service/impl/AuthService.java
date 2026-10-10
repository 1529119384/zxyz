package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.dto.LoginRequest;
import uno.acloud.user.dto.RegisterRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.AuthSessionPort;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TeamServicePermissionClient teamServicePermissionClient;
    private final AuthSessionPort authSessionService;
    private final UserQueryHelper userQueryHelper;
    private final LoginFailureLockoutService loginFailureLockoutService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       TeamServicePermissionClient teamServicePermissionClient,
                       AuthSessionPort authSessionService,
                       UserQueryHelper userQueryHelper,
                       LoginFailureLockoutService loginFailureLockoutService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.authSessionService = authSessionService;
        this.userQueryHelper = userQueryHelper;
        this.loginFailureLockoutService = loginFailureLockoutService;
    }

    public String login(LoginRequest request) {
        // P3-2 账号维度爆破防护：锁定期间在密码校验前直接拒绝（不泄露密码是否正确）。
        // 与 LoginRateLimiter 的频控互补——频控拦「快」，锁定拦「慢速持续撞库」。
        loginFailureLockoutService.checkLocked(request.getUsername());
        User dbUser = userMapper.getByLoginIdentifier(request.getUsername());
        if (dbUser == null || !userQueryHelper.passwordMatched(request.getPassword(), dbUser)) {
            // 失败累计（含用户名不存在的尝试，防用户名枚举）；达到阈值即锁定该账号。
            loginFailureLockoutService.recordFailure(request.getUsername());
            throw new BusinessException(UserErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        Long userId = dbUser.getId();
        String username = dbUser.getUsername();
        // 密码校验通过即清零失败计数，从零重新累计。
        loginFailureLockoutService.recordSuccess(username);
        teamServicePermissionClient.ensureDefaultRole(userId, username);
        log.info("用户 {} 登录成功", uno.acloud.common.util.LogMaskingUtil.maskUsername(username));
        return authSessionService.createLoginSession(
                userId,
                username,
                teamServicePermissionClient.getSystemRolesByUserId(userId),
                teamServicePermissionClient.getSystemPermissionsByUserId(userId),
                request.isRememberMe()
        );
    }

    public int register(RegisterRequest request) {
        User user = buildRegisterUser(request);

        // 第一步：本地 DB 操作（单条 INSERT，数据库层面已是原子操作）
        int result = insertUser(user);

        // 第二步：远程角色分配（事务外 HTTP 调用，避免 H-4 事务边界问题）
        //
        // 这里刻意不再有「首个注册用户自动成为 SYSTEM_ADMIN」的分支（审计 2.1.1）：
        // /api/users/register 在网关白名单里、无验证码、无邮箱验证，是公网可达路径，
        // 「先到先得管理员」在新环境部署 / 数据重建 / user 表清空的窗口期内
        // 就是一条可直接利用的提权路径，且 count 与 INSERT 之间本身还有并发竞态。
        // 管理员引导由 AdminBootstrapRunner 专职负责（部署时 ADMIN_INIT_USERNAME/PASSWORD 控制）。
        try {
            teamServicePermissionClient.ensureDefaultRole(user.getId(), user.getUsername());
        } catch (Exception e) {
            log.error("用户 {} 角色分配失败", user.getUsername(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册成功但角色分配失败，请联系管理员");
        }
        return result;
    }

    /**
     * 本地用户插入，处理用户名重复。
     * 单条 INSERT 语句在数据库层面已是原子操作，无需 @Transactional。
     */
    private int insertUser(User user) {
        user.setCreateTime(LocalDateTime.now());
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        log.info("用户 {} 注册时间: {}", user.getUsername(), user.getCreateTime());
        try {
            return userMapper.addByUsernameAndPassword(user);
        } catch (DuplicateKeyException e) {
            log.warn("用户 {} 注册失败，用户名已存在", user.getUsername(), e);
            throw new BusinessException(UserErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
    }

    /**
     * 创建引导管理员账号（用于部署时自动初始化初始管理员）。
     * 复用本地插入逻辑（含密码编码与用户名唯一性校验），返回新用户主键 id。
     */
    public Long createBootstrapAdmin(String username, String rawPassword) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(rawPassword);
        insertUser(user);
        return user.getId();
    }

    private User buildRegisterUser(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        return user;
    }
}
