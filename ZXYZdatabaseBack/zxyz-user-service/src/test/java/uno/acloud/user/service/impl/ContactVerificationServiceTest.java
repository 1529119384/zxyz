package uno.acloud.user.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.dto.ContactVerifyRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.EmailServiceMailClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.vo.ContactVerificationCodeVO;
import uno.acloud.user.vo.CurrentUserVO;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactVerificationServiceTest {

    /** 对应 @Value("${app.email.verify-code.cooldown-seconds:60}") 的注入值 */
    private static final int EMAIL_VERIFY_CODE_COOLDOWN_SECONDS = 60;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EmailServiceMailClient emailServiceMailClient;

    @Mock
    private UserQueryHelper userQueryHelper;

    private ContactVerificationService contactVerificationService;

    private ServiceProperties serviceProperties;

    @BeforeEach
    void setUp() {
        serviceProperties = new ServiceProperties();
        serviceProperties.getVerification().setReturnCodeInResponse(true);

        contactVerificationService = new ContactVerificationService(
                userMapper, stringRedisTemplate, emailServiceMailClient,
                userQueryHelper, serviceProperties, EMAIL_VERIFY_CODE_COOLDOWN_SECONDS);
    }

    private User userWithEmail(Long id, String email, boolean emailVerified) {
        User user = new User();
        user.setId(id);
        user.setUsername("testuser");
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        return user;
    }

    // ==================== Send verification code — should succeed ====================

    @Test
    void createEmailVerificationCode_validEmail_shouldSucceed() {
        Long userId = 1L;
        String email = "test@example.com";

        User user = userWithEmail(userId, email, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);

        // Redis cooldown acquisition succeeds
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        ContactVerificationCodeVO result = contactVerificationService.createEmailVerificationCode(userId, "127.0.0.1");

        assertNotNull(result);
        assertEquals("email", result.getType());
        verify(emailServiceMailClient).sendVerifyCode(email, "EMAIL_BIND", "127.0.0.1");
        // 冷却时长来自 @Value 注入的 app.email.verify-code.cooldown-seconds
        verify(valueOperations).setIfAbsent(anyString(), eq("1"),
                eq(Duration.ofSeconds(EMAIL_VERIFY_CODE_COOLDOWN_SECONDS)));
    }

    // ==================== Send verification code — already verified ====================

    @Test
    void createEmailVerificationCode_alreadyVerified_shouldThrow() {
        Long userId = 1L;
        String email = "test@example.com";

        User user = userWithEmail(userId, email, true);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.createEmailVerificationCode(userId, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("邮箱已验证"));
    }

    // ==================== Send verification code — cooldown active ====================

    @Test
    void createEmailVerificationCode_cooldownActive_shouldThrow() {
        Long userId = 1L;
        String email = "test@example.com";

        User user = userWithEmail(userId, email, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);

        // Redis cooldown acquisition fails (key already exists)
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.createEmailVerificationCode(userId, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("60 秒后再试"));
    }

    // ==================== Verify with correct code — should succeed ====================

    @Test
    void verifyContact_correctEmailCode_shouldSucceed() {
        Long userId = 1L;
        String email = "test@example.com";

        User user = userWithEmail(userId, email, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);

        ContactVerifyRequest request = new ContactVerifyRequest();
        request.setType("email");
        request.setCode("123456");

        // emailServiceMailClient.checkVerifyCode succeeds (no-op mock for void)
        when(userMapper.verifyEmail(userId)).thenReturn(1);

        CurrentUserVO currentUser = new CurrentUserVO(
                userId, "testuser", "Test User", null,
                email, null, true, false, null, List.of(), List.of());
        when(userQueryHelper.requireCurrentUser(userId)).thenReturn(currentUser);

        CurrentUserVO result = contactVerificationService.verifyContact(userId, request);

        assertNotNull(result);
        assertTrue(result.getEmailVerified());
        verify(emailServiceMailClient).checkVerifyCode(email, "EMAIL_BIND", "123456");
        verify(userMapper).verifyEmail(userId);
    }

    // ==================== Verify with wrong code — should throw ====================

    @Test
    void verifyContact_wrongEmailCode_shouldThrow() {
        Long userId = 1L;
        String email = "test@example.com";

        User user = userWithEmail(userId, email, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);

        ContactVerifyRequest request = new ContactVerifyRequest();
        request.setType("email");
        request.setCode("000000");

        // emailServiceMailClient.checkVerifyCode throws on wrong code
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误"))
                .when(emailServiceMailClient).checkVerifyCode(email, "EMAIL_BIND", "000000");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.verifyContact(userId, request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("验证码错误"));
    }

    // ==================== Verify expired code — should throw ====================

    @Test
    void verifyContact_expiredPhoneCode_shouldThrow() {
        Long userId = 1L;

        ContactVerifyRequest request = new ContactVerifyRequest();
        request.setType("phone");
        request.setCode("654321");

        // Expired code: countValidContactVerificationCode returns 0
        when(userMapper.countValidContactVerificationCode(userId, "phone", "654321")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.verifyContact(userId, request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("验证码无效或已过期"));
    }

    // ==================== Verify phone with correct code — should succeed ====================

    @Test
    void verifyContact_correctPhoneCode_shouldSucceed() {
        Long userId = 1L;

        ContactVerifyRequest request = new ContactVerifyRequest();
        request.setType("phone");
        request.setCode("123456");

        when(userMapper.countValidContactVerificationCode(userId, "phone", "123456")).thenReturn(1);
        when(userMapper.verifyPhone(userId)).thenReturn(1);

        CurrentUserVO currentUser = new CurrentUserVO(
                userId, "testuser", "Test User", null,
                null, "+8613800138000", false, true, null, List.of(), List.of());
        when(userQueryHelper.requireCurrentUser(userId)).thenReturn(currentUser);

        CurrentUserVO result = contactVerificationService.verifyContact(userId, request);

        assertNotNull(result);
        assertTrue(result.getPhoneVerified());
        verify(userMapper).verifyPhone(userId);
    }
}
