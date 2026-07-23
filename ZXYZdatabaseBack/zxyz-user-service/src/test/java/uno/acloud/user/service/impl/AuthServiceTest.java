package uno.acloud.user.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.dto.LoginRequest;
import uno.acloud.user.dto.RegisterRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.AuthSessionPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TeamServicePermissionClient teamServicePermissionClient;

    @Mock
    private AuthSessionPort authSessionService;

    @Mock
    private UserQueryHelper userQueryHelper;

    @InjectMocks
    private AuthService authService;

    // ---- login tests ----

    @Test
    void login_throwsWhenUserNotFound() {
        LoginRequest request = new LoginRequest();
        request.setUsername("nobody");
        request.setPassword("pass");
        when(userMapper.getByLoginIdentifier("nobody")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(request));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());
    }

    @Test
    void login_throwsWhenPasswordWrong() {
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong");

        User dbUser = new User();
        dbUser.setId(1L);
        dbUser.setUsername("alice");
        dbUser.setPassword("encoded");

        when(userMapper.getByLoginIdentifier("alice")).thenReturn(dbUser);
        when(userQueryHelper.passwordMatched("wrong", dbUser)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(request));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());
    }

    @Test
    void login_succeedsAndCreatesSession() {
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("correct");

        User dbUser = new User();
        dbUser.setId(1L);
        dbUser.setUsername("alice");
        dbUser.setPassword("encoded");

        when(userMapper.getByLoginIdentifier("alice")).thenReturn(dbUser);
        when(userQueryHelper.passwordMatched("correct", dbUser)).thenReturn(true);
        when(teamServicePermissionClient.getSystemRolesByUserId(1L)).thenReturn(List.of("USER"));
        when(teamServicePermissionClient.getSystemPermissionsByUserId(1L)).thenReturn(List.of("read"));
        when(authSessionService.createLoginSession(1L, "alice", List.of("USER"), List.of("read")))
                .thenReturn("token-abc");

        String token = authService.login(request);

        assertEquals("token-abc", token);
    }

    @Test
    void login_callsEnsureDefaultRole() {
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("correct");

        User dbUser = new User();
        dbUser.setId(1L);
        dbUser.setUsername("alice");
        dbUser.setPassword("encoded");

        when(userMapper.getByLoginIdentifier("alice")).thenReturn(dbUser);
        when(userQueryHelper.passwordMatched("correct", dbUser)).thenReturn(true);
        when(teamServicePermissionClient.getSystemRolesByUserId(1L)).thenReturn(List.of());
        when(teamServicePermissionClient.getSystemPermissionsByUserId(1L)).thenReturn(List.of());
        when(authSessionService.createLoginSession(anyLong(), anyString(), any(), any()))
                .thenReturn("token");

        authService.login(request);

        verify(teamServicePermissionClient).ensureDefaultRole(1L, "alice");
    }

    // ---- register tests ----

    @Test
    void register_encodesPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("bob");
        request.setPassword("rawPassword");

        when(userMapper.countUsers()).thenReturn(1);
        when(passwordEncoder.encode("rawPassword")).thenReturn("encodedPassword");
        when(userMapper.addByUsernameAndPassword(any(User.class))).thenReturn(1);

        authService.register(request);

        verify(passwordEncoder).encode("rawPassword");
    }

    @Test
    void register_throwsWhenUsernameExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("pass123");

        when(userMapper.countUsers()).thenReturn(1);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded");
        when(userMapper.addByUsernameAndPassword(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(request));
        assertEquals(UserErrorCode.USERNAME_EXISTS.getCode(), ex.getErrorCode());
    }

    @Test
    void register_assignsAdminRoleForFirstUser() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("firstuser");
        request.setPassword("pass123");

        when(userMapper.countUsers()).thenReturn(0);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded");
        when(userMapper.addByUsernameAndPassword(any(User.class))).thenReturn(1);

        authService.register(request);

        verify(teamServicePermissionClient).assignBootstrapAdminRoleStrict(any());
        verify(teamServicePermissionClient, never()).ensureDefaultRole(any(), anyString());
    }

    @Test
    void register_assignsDefaultRoleForSubsequentUsers() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("seconduser");
        request.setPassword("pass123");

        when(userMapper.countUsers()).thenReturn(5);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded");
        when(userMapper.addByUsernameAndPassword(any(User.class))).thenReturn(1);

        authService.register(request);

        verify(teamServicePermissionClient).ensureDefaultRole(any(), eq("seconduser"));
        verify(teamServicePermissionClient, never()).assignBootstrapAdminRoleStrict(any());
    }

    @Test
    void register_throwsWhenRoleAssignmentFails() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("failuser");
        request.setPassword("pass123");

        when(userMapper.countUsers()).thenReturn(0);
        when(passwordEncoder.encode("pass123")).thenReturn("encoded");
        when(userMapper.addByUsernameAndPassword(any(User.class))).thenReturn(1);
        doThrow(new RuntimeException("HTTP 500"))
                .when(teamServicePermissionClient).assignBootstrapAdminRoleStrict(any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(request));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
    }
}
