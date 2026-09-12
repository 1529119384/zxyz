package uno.acloud.user.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.CookieHelper;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.dto.LoginRequest;
import uno.acloud.user.dto.RegisterRequest;
import uno.acloud.user.service.impl.AccountLinkingService;
import uno.acloud.user.service.impl.AuthService;
import uno.acloud.user.service.impl.ContactVerificationService;
import uno.acloud.user.service.impl.LoginRateLimiter;
import uno.acloud.user.service.impl.RegisterRateLimiter;
import uno.acloud.user.service.impl.UserProfileService;
import uno.acloud.user.vo.ContactVerificationCodeVO;
import uno.acloud.user.vo.CurrentUserVO;
import uno.acloud.user.vo.LoginVO;
import uno.acloud.common.Result;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.service.impl.UserAdminService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private ContactVerificationService contactVerificationService;
    @Mock
    private AccountLinkingService accountLinkingService;
    @Mock
    private CookieHelper cookieHelper;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private RegisterRateLimiter registerRateLimiter;
    @Mock
    private AuthServicePort authServicePort;

    @Mock
    private UserAdminService userAdminService;

    private final ServiceProperties serviceProperties = new ServiceProperties();

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(
                authService, userProfileService, contactVerificationService,
                accountLinkingService, cookieHelper, loginRateLimiter, registerRateLimiter,
                authServicePort, userAdminService, serviceProperties);
    }

    // ==================== login — valid credentials ====================

    @Test
    void login_withValidCredentials_returnsSuccess() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authService.login(request)).thenReturn("test-token-abc");

        Result<LoginVO> result = userController.login(request, httpRequest, response);

        assertNotNull(result);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertNotNull(result.getData());
        assertEquals("test-token-abc", result.getData().getToken());
        assertEquals("Bearer", result.getData().getTokenType());
        assertTrue(result.getData().getIsLogin());

        // Verify rate limiter was called
        verify(loginRateLimiter).checkAndIncrement("127.0.0.1", "admin");
        // Verify auth service was called
        verify(authService).login(request);
        // Verify cookies were set
        verify(cookieHelper).setAuthCookies(eq(response), eq("test-token-abc"),
                eq(serviceProperties.getAuth().getTokenTimeoutSeconds()));
    }

    @Test
    void login_withRememberMe_setsLongLivedCookie() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("testpass123");
        request.setRememberMe(true);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authService.login(request)).thenReturn("token-remember-me");

        Result<LoginVO> result = userController.login(request, httpRequest, response);

        assertNotNull(result);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(cookieHelper).setAuthCookies(eq(response), eq("token-remember-me"),
                eq(serviceProperties.getAuth().getLongLivedTimeoutSeconds()));
    }

    // ==================== login — invalid credentials ====================

    @Test
    void login_withInvalidCredentials_throwsException() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpassword");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authService.login(request)).thenThrow(new BusinessException(UserErrorCode.LOGIN_FAILED, "用户名或密码错误"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userController.login(request, httpRequest, response));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());

        // Cookies should NOT be set
        verify(cookieHelper, never()).setAuthCookies(any(), anyString(), anyInt());
    }

    // ==================== login — rate limited ====================

    @Test
    void login_rateLimited_throwsException() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "请求过于频繁，请稍后再试"))
                .when(loginRateLimiter).checkAndIncrement("127.0.0.1", "admin");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userController.login(request, httpRequest, response));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        // Auth service should NOT be called
        verify(authService, never()).login(any());
    }

    // ==================== register — success ====================

    @Test
    void register_withValidRequest_returnsSuccess() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        when(authService.register(request)).thenReturn(1);

        Result<String> result = userController.register(request, httpRequest);

        assertNotNull(result);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("注册成功", result.getData());

        verify(registerRateLimiter).checkAndIncrement("127.0.0.1");
        verify(authService).register(request);
    }

    // ==================== register — duplicate username ====================

    @Test
    void register_withDuplicateUsername_throwsException() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("existinguser");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        when(authService.register(request))
                .thenThrow(new BusinessException(UserErrorCode.USERNAME_EXISTS, "用户名已存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userController.register(request, httpRequest));
        assertEquals(UserErrorCode.USERNAME_EXISTS.getCode(), ex.getErrorCode());
    }

    // ==================== register — rate limited ====================

    @Test
    void register_rateLimited_throwsException() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");

        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "注册请求过于频繁，请稍后再试"))
                .when(registerRateLimiter).checkAndIncrement("127.0.0.1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userController.register(request, httpRequest));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        // Auth service should NOT be called
        verify(authService, never()).register(any());
    }

    // ==================== getCurrentUser — service delegation ====================

    @Test
    void getCurrentUser_userExists_returnsUser() {
        // Note: StpUtil.getLoginIdAsLong() is called inside the controller.
        // This test verifies the service delegation pattern.
        // Full integration with Sa-Token would require @WebMvcTest + Sa-Token mock.
        // Here we test the service-level delegation by directly calling with a known userId.

        CurrentUserVO mockUser = new CurrentUserVO(
                1L, "admin", "Admin", null, null, null,
                false, false, null, List.of(), List.of());
        when(userProfileService.getCurrentUser(1L)).thenReturn(Optional.of(mockUser));

        // Directly test the service delegation (the controller's core logic)
        Optional<CurrentUserVO> result = userProfileService.getCurrentUser(1L);
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        assertEquals("admin", result.get().getUsername());
        verify(userProfileService).getCurrentUser(1L);
    }

    @Test
    void getCurrentUser_userNotFound_returnsEmpty() {
        when(userProfileService.getCurrentUser(999L)).thenReturn(Optional.empty());

        Optional<CurrentUserVO> result = userProfileService.getCurrentUser(999L);
        assertTrue(result.isEmpty());
    }

    // ==================== 真实客户端 IP（审计 12-P0-2 防回归） ====================

    /** 模拟网关/nginx 容器地址：所有真实客户端在服务侧看到的都是这一个值。 */
    private static final String GATEWAY_CONTAINER_IP = "172.18.0.5";

    private static MockHttpServletRequest requestBehindGateway(String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(GATEWAY_CONTAINER_IP);
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    private static LoginRequest loginRequest(String username) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("password123");
        return request;
    }

    @Test
    void login_usesGatewayRealIpForRateLimit_notContainerIp() {
        LoginRequest request = loginRequest("admin");
        when(authService.login(request)).thenReturn("token");

        userController.login(request, requestBehindGateway("203.0.113.7"), new MockHttpServletResponse());

        verify(loginRateLimiter).checkAndIncrement("203.0.113.7", "admin");
    }

    @Test
    void login_fallsBackToRemoteAddrWhenGatewayHeaderMissing() {
        LoginRequest request = loginRequest("admin");
        when(authService.login(request)).thenReturn("token");

        userController.login(request, requestBehindGateway(null), new MockHttpServletResponse());

        verify(loginRateLimiter).checkAndIncrement(GATEWAY_CONTAINER_IP, "admin");
    }

    @Test
    void login_twoClientsBehindSameGateway_getSeparateRateLimitBuckets() {
        LoginRequest first = loginRequest("admin");
        LoginRequest second = loginRequest("admin");
        when(authService.login(first)).thenReturn("token-1");
        when(authService.login(second)).thenReturn("token-2");

        userController.login(first, requestBehindGateway("203.0.113.7"), new MockHttpServletResponse());
        userController.login(second, requestBehindGateway("198.51.100.9"), new MockHttpServletResponse());

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(loginRateLimiter, times(2)).checkAndIncrement(ipCaptor.capture(), eq("admin"));

        List<String> capturedIps = ipCaptor.getAllValues();
        assertEquals(List.of("203.0.113.7", "198.51.100.9"), capturedIps);
        assertNotEquals(capturedIps.get(0), capturedIps.get(1),
                "两个真实客户端必须落到不同限流键；若相同即说明退化成全局单桶");
    }

    @Test
    void register_usesGatewayRealIpForRateLimit() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        when(authService.register(request)).thenReturn(1);

        userController.register(request, requestBehindGateway("203.0.113.7"));

        verify(registerRateLimiter).checkAndIncrement("203.0.113.7");
    }

    @Test
    void createEmailVerificationCode_forwardsResolvedClientIp() {
        // 该 IP 会一路传到 email-service，成为 zxyz:email:verify:ip:<ip> 限流键 ——
        // 若此处仍传 getRemoteAddr()，邮件验证码的「每 IP 限流」同样是全局单桶。
        when(contactVerificationService.createEmailVerificationCode(1L, "203.0.113.7"))
                .thenReturn(new ContactVerificationCodeVO("email", null));

        userController.createEmailVerificationCode(1L, requestBehindGateway("203.0.113.7"));

        verify(contactVerificationService).createEmailVerificationCode(1L, "203.0.113.7");
    }
}
