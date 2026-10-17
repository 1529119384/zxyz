package uno.acloud.user.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import uno.acloud.common.UserErrorCode;
import uno.acloud.common.util.VerifyCodeHasher;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.dto.EmailBindRequest;
import uno.acloud.user.dto.PhoneBindRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.EmailServiceMailClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.vo.CurrentUserVO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 62g-S2 / 63 号「前端合约断裂」补测：bindEmail/bindPhone 必须校验当前密码，
 * 且改绑落库后返回最新用户视图。此前这两个方法零测试覆盖（63 号指出的盲区）。
 */
@ExtendWith(MockitoExtension.class)
class ContactVerificationServiceBindTest {

    private static final String TEST_PEPPER = "test-pepper";

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private EmailServiceMailClient emailServiceMailClient;

    @Mock
    private UserQueryHelper userQueryHelper;

    private ContactVerificationService contactVerificationService;

    private User userWithEmail(Long id, String email, boolean emailVerified) {
        User user = new User();
        user.setId(id);
        user.setUsername("testuser");
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        return user;
    }

    @BeforeEach
    void setUp() {
        ServiceProperties serviceProperties = new ServiceProperties();
        serviceProperties.getVerification().setReturnCodeInResponse(true);
        VerifyCodeHasher verifyCodeHasher = new VerifyCodeHasher(TEST_PEPPER);
        contactVerificationService = new ContactVerificationService(
                userMapper, stringRedisTemplate, emailServiceMailClient,
                userQueryHelper, serviceProperties, verifyCodeHasher, 60);
    }

    @Test
    void bindEmail_wrongPassword_shouldThrow() {
        Long userId = 7L;
        User user = userWithEmail(userId, "old@example.com", true);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);
        when(userQueryHelper.passwordMatched(eq("bad"), any(User.class))).thenReturn(false);

        EmailBindRequest request = new EmailBindRequest();
        request.setEmail("new@example.com");
        request.setPassword("bad");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.bindEmail(userId, request));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());
        verify(userMapper, never()).updateEmail(anyLong(), anyString());
    }

    @Test
    void bindEmail_correctPassword_shouldUpdateAndReturnProfile() {
        Long userId = 7L;
        User user = userWithEmail(userId, "old@example.com", true);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);
        when(userQueryHelper.passwordMatched(eq("right"), any(User.class))).thenReturn(true);
        when(userMapper.updateEmail(eq(userId), eq("new@example.com"))).thenReturn(1);
        CurrentUserVO vo = mock(CurrentUserVO.class);
        when(userQueryHelper.requireCurrentUser(userId)).thenReturn(vo);

        EmailBindRequest request = new EmailBindRequest();
        request.setEmail("new@example.com");
        request.setPassword("right");

        CurrentUserVO result = contactVerificationService.bindEmail(userId, request);
        assertSame(vo, result);
        verify(userMapper).updateEmail(userId, "new@example.com");
    }

    @Test
    void bindPhone_wrongPassword_shouldThrow() {
        Long userId = 8L;
        User user = userWithEmail(userId, null, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);
        when(userQueryHelper.passwordMatched(eq("bad"), any(User.class))).thenReturn(false);

        PhoneBindRequest request = new PhoneBindRequest();
        request.setPhone("+8613800138000");
        request.setPassword("bad");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contactVerificationService.bindPhone(userId, request));
        assertEquals(UserErrorCode.LOGIN_FAILED.getCode(), ex.getErrorCode());
        verify(userMapper, never()).updatePhone(anyLong(), anyString());
    }

    @Test
    void bindPhone_correctPassword_shouldUpdateAndReturnProfile() {
        Long userId = 8L;
        User user = userWithEmail(userId, null, false);
        when(userQueryHelper.requireExistingUser(userId)).thenReturn(user);
        when(userQueryHelper.passwordMatched(eq("right"), any(User.class))).thenReturn(true);
        when(userMapper.updatePhone(eq(userId), eq("+8613800138000"))).thenReturn(1);
        CurrentUserVO vo = mock(CurrentUserVO.class);
        when(userQueryHelper.requireCurrentUser(userId)).thenReturn(vo);

        PhoneBindRequest request = new PhoneBindRequest();
        request.setPhone("+8613800138000");
        request.setPassword("right");

        CurrentUserVO result = contactVerificationService.bindPhone(userId, request);
        assertSame(vo, result);
        verify(userMapper).updatePhone(userId, "+8613800138000");
    }
}
