package uno.acloud.user.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.mapper.UserMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQueryHelperTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserQueryHelper userQueryHelper;

    @Test
    void requireExistingUser_throwsWhenNotFound() {
        when(userMapper.getById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userQueryHelper.requireExistingUser(99L));
        assertEquals(UserErrorCode.USER_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void requireExistingUser_returnsUserWhenFound() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userMapper.getById(1L)).thenReturn(user);

        User result = userQueryHelper.requireExistingUser(1L);
        assertSame(user, result);
    }

    @Test
    void passwordMatched_returnsFalseWhenStoredPasswordNull() {
        User dbUser = new User();
        dbUser.setPassword(null);

        assertFalse(userQueryHelper.passwordMatched("raw", dbUser));
    }

    @Test
    void passwordMatched_returnsFalseWhenRawPasswordNull() {
        User dbUser = new User();
        dbUser.setPassword("encoded");

        assertFalse(userQueryHelper.passwordMatched(null, dbUser));
    }

    @Test
    void passwordMatched_returnsFalseWhenNoMatch() {
        User dbUser = new User();
        dbUser.setPassword("encoded");
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertFalse(userQueryHelper.passwordMatched("wrong", dbUser));
    }

    @Test
    void passwordMatched_returnsTrueWhenMatch() {
        User dbUser = new User();
        dbUser.setPassword("encoded");
        when(passwordEncoder.matches("correct", "encoded")).thenReturn(true);

        assertTrue(userQueryHelper.passwordMatched("correct", dbUser));
    }

    @Test
    void requireUpdated_throwsWhenZeroRows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userQueryHelper.requireUpdated(0));
        assertEquals(UserErrorCode.USER_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void requireUpdated_succeedsWhenOneRow() {
        // Should not throw
        userQueryHelper.requireUpdated(1);
    }
}
