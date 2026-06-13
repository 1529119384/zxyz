package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
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

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       TeamServicePermissionClient teamServicePermissionClient,
                       AuthSessionPort authSessionService,
                       UserQueryHelper userQueryHelper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.authSessionService = authSessionService;
        this.userQueryHelper = userQueryHelper;
    }

    public String login(LoginRequest request) {
        User dbUser = userMapper.getByLoginIdentifier(request.getUsername());
        if (dbUser == null || !userQueryHelper.passwordMatched(request.getPassword(), dbUser)) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        Long userId = dbUser.getId();
        String username = dbUser.getUsername();
        teamServicePermissionClient.ensureDefaultRole(userId, username);
        log.info("用户 {} 登录成功", username);
        return authSessionService.createLoginSession(
                userId,
                username,
                teamServicePermissionClient.getSystemRolesByUserId(userId),
                teamServicePermissionClient.getSystemPermissionsByUserId(userId)
        );
    }

    public int register(RegisterRequest request) {
        boolean firstRegisteredUser = userMapper.countUsers() == 0;
        User user = buildRegisterUser(request);

        // 第一步：本地 DB 操作（单条 INSERT，数据库层面已是原子操作）
        int result = insertUser(user);

        // 第二步：远程角色分配（事务外 HTTP 调用，避免 H-4 事务边界问题）
        try {
            if (firstRegisteredUser) {
                teamServicePermissionClient.assignBootstrapAdminRoleStrict(user.getId());
            } else {
                teamServicePermissionClient.ensureDefaultRole(user.getId(), user.getUsername());
            }
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
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
    }

    private User buildRegisterUser(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        return user;
    }
}
