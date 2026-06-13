package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.satoken.SaTokenAuthSessionService;
import uno.acloud.user.vo.CurrentUserVO;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class UserQueryHelper {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthServicePort authServicePort;

    public UserQueryHelper(UserMapper userMapper, PasswordEncoder passwordEncoder, AuthServicePort authServicePort) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authServicePort = authServicePort;
    }

    public User requireExistingUser(Long userId) {
        User dbUser = userMapper.getById(userId);
        if (dbUser == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return dbUser;
    }

    public CurrentUserVO requireCurrentUser(Long userId) {
        return getCurrentUser(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    public Optional<CurrentUserVO> getCurrentUser(Long userId) {
        User dbUser = userMapper.getById(userId);
        if (dbUser == null) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) authServicePort.getCurrentSessionAttribute(SaTokenAuthSessionService.EXTRA_ROLE);
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) authServicePort.getCurrentSessionAttribute(SaTokenAuthSessionService.EXTRA_PERMISSION);
        return Optional.of(new CurrentUserVO(
                dbUser.getId(),
                dbUser.getUsername(),
                dbUser.getName(),
                dbUser.getAvatar(),
                dbUser.getEmail(),
                dbUser.getPhone(),
                Boolean.TRUE.equals(dbUser.getEmailVerified()),
                Boolean.TRUE.equals(dbUser.getPhoneVerified()),
                dbUser.getDefaultTeamId(),
                roles,
                permissions
        ));
    }

    public void requireUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
    }

    public boolean passwordMatched(String rawPassword, User dbUser) {
        String storedPassword = dbUser.getPassword();
        if (storedPassword == null || rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, storedPassword);
    }
}
