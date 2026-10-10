package uno.acloud.user.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.dto.LinkedAccountTrustRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserEntityMapper;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.AuthSessionPort;
import uno.acloud.user.vo.AccountSwitchVO;
import uno.acloud.user.vo.CurrentUserVO;
import uno.acloud.user.vo.LinkedAccountVO;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLinkingServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuthSessionPort authSessionService;

    @Mock
    private TeamServicePermissionClient teamServicePermissionClient;

    @Mock
    private UserQueryHelper userQueryHelper;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserEntityMapper userEntityMapper;

    private AccountLinkingService accountLinkingService;

    @BeforeEach
    void setUp() {
        accountLinkingService = new AccountLinkingService(
                userMapper, authSessionService, teamServicePermissionClient,
                userQueryHelper, userProfileService, userEntityMapper);
    }

    private User userWith(Long id, String username, String password) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }

    // ==================== Trust linked account — should succeed ====================

    @Test
    void trustLinkedAccount_validRequest_shouldSucceed() {
        Long userId = 1L;
        Long targetUserId = 2L;

        LinkedAccountTrustRequest request = new LinkedAccountTrustRequest();
        request.setPassword("target-password");

        // Target is a verified linked account
        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);

        User target = userWith(targetUserId, "targetUser", "encoded-password");
        when(userQueryHelper.requireExistingUser(targetUserId)).thenReturn(target);
        when(userQueryHelper.passwordMatched("target-password", target)).thenReturn(true);
        when(userEntityMapper.toLinkedAccountVO(target))
                .thenReturn(new LinkedAccountVO(targetUserId, "targetUser", null, null, null, null, null));

        LinkedAccountVO result = accountLinkingService.trustLinkedAccount(userId, targetUserId, request);

        assertNotNull(result);
        assertEquals(targetUserId, result.getId());
        verify(userMapper).upsertAccountSwitchTrust(userId, targetUserId);
        verify(userMapper).upsertAccountSwitchTrust(targetUserId, userId);
    }

    // ==================== Trust already trusted account — should succeed (upsert) ====================

    @Test
    void trustLinkedAccount_alreadyTrusted_shouldSucceed() {
        Long userId = 1L;
        Long targetUserId = 2L;

        LinkedAccountTrustRequest request = new LinkedAccountTrustRequest();
        request.setPassword("target-password");

        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);

        User target = userWith(targetUserId, "targetUser", "encoded-password");
        when(userQueryHelper.requireExistingUser(targetUserId)).thenReturn(target);
        when(userQueryHelper.passwordMatched("target-password", target)).thenReturn(true);
        when(userEntityMapper.toLinkedAccountVO(target))
                .thenReturn(new LinkedAccountVO(targetUserId, "targetUser", null, null, null, null, null));

        // Trust already exists — upsert just updates create_time
        LinkedAccountVO result = accountLinkingService.trustLinkedAccount(userId, targetUserId, request);

        assertNotNull(result);
        // upsertAccountSwitchTrust is called for both directions
        verify(userMapper).upsertAccountSwitchTrust(userId, targetUserId);
        verify(userMapper).upsertAccountSwitchTrust(targetUserId, userId);
    }

    // ==================== Trust with wrong password — should throw ====================

    @Test
    void trustLinkedAccount_wrongPassword_shouldThrow() {
        Long userId = 1L;
        Long targetUserId = 2L;

        LinkedAccountTrustRequest request = new LinkedAccountTrustRequest();
        request.setPassword("wrong-password");

        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);

        User target = userWith(targetUserId, "targetUser", "encoded-password");
        when(userQueryHelper.requireExistingUser(targetUserId)).thenReturn(target);
        when(userQueryHelper.passwordMatched("wrong-password", target)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountLinkingService.trustLinkedAccount(userId, targetUserId, request));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("密码错误"));
    }

    // ==================== Switch linked account — should succeed ====================

    @Test
    void switchLinkedAccount_validRequest_shouldSucceed() {
        Long userId = 1L;
        Long targetUserId = 2L;

        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);
        when(userMapper.countAccountSwitchTrust(userId, targetUserId)).thenReturn(1);

        User target = userWith(targetUserId, "targetUser", "encoded-password");
        when(userQueryHelper.requireExistingUser(targetUserId)).thenReturn(target);

        when(teamServicePermissionClient.getSystemRolesByUserId(targetUserId)).thenReturn(List.of("user"));
        when(teamServicePermissionClient.getSystemPermissionsByUserId(targetUserId)).thenReturn(List.of());
        when(authSessionService.createLoginSession(eq(targetUserId), eq("targetUser"), anyList(), anyList()))
                .thenReturn("new-session-token");

        CurrentUserVO targetProfile = new CurrentUserVO(
                targetUserId, "targetUser", "Target User", null,
                null, null, false, false, null, List.of(), List.of());
        when(userProfileService.getCurrentUser(targetUserId)).thenReturn(Optional.of(targetProfile));

        AccountSwitchVO result = accountLinkingService.switchLinkedAccount(userId, targetUserId);

        assertNotNull(result);
        // body 收敛在 UserController（Cookie 下发后）做（P3-1），service 层始终携带真实 token
        assertEquals("new-session-token", result.getToken());
        assertTrue(result.getIsLogin());
        verify(teamServicePermissionClient).ensureDefaultRole(targetUserId, "targetUser");
    }

    @Test
    void switchLinkedAccount_alwaysCarriesTokenForCookieDispatch() {
        // 防回归：service 层不得自行收敛 token——Controller 需凭它下发 HttpOnly Cookie；
        // 即使开关关闭（默认），service 层返回的 token 也必须是真实值。
        Long userId = 1L;
        Long targetUserId = 2L;

        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);
        when(userMapper.countAccountSwitchTrust(userId, targetUserId)).thenReturn(1);

        User target = userWith(targetUserId, "targetUser", "encoded-password");
        when(userQueryHelper.requireExistingUser(targetUserId)).thenReturn(target);

        when(teamServicePermissionClient.getSystemRolesByUserId(targetUserId)).thenReturn(List.of("user"));
        when(teamServicePermissionClient.getSystemPermissionsByUserId(targetUserId)).thenReturn(List.of());
        when(authSessionService.createLoginSession(eq(targetUserId), eq("targetUser"), anyList(), anyList()))
                .thenReturn("cookie-dispatch-token");

        CurrentUserVO targetProfile = new CurrentUserVO(
                targetUserId, "targetUser", "Target User", null,
                null, null, false, false, null, List.of(), List.of());
        when(userProfileService.getCurrentUser(targetUserId)).thenReturn(Optional.of(targetProfile));

        AccountSwitchVO result = accountLinkingService.switchLinkedAccount(userId, targetUserId);

        assertEquals("cookie-dispatch-token", result.getToken());
    }

    // ==================== Switch without prior trust — should throw ====================

    @Test
    void switchLinkedAccount_noTrust_shouldThrow() {
        Long userId = 1L;
        Long targetUserId = 2L;

        when(userMapper.countVerifiedLinkedAccount(userId, targetUserId)).thenReturn(1);
        // No trust established
        when(userMapper.countAccountSwitchTrust(userId, targetUserId)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountLinkingService.switchLinkedAccount(userId, targetUserId));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("验证目标账号密码"));
    }

    // ==================== Trust with self — should throw ====================

    @Test
    void trustLinkedAccount_self_shouldThrow() {
        Long userId = 1L;

        LinkedAccountTrustRequest request = new LinkedAccountTrustRequest();
        request.setPassword("password");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountLinkingService.trustLinkedAccount(userId, userId, request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("目标账号不合法"));
    }
}
