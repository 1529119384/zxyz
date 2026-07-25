package uno.acloud.user.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.infrastructure.mq.UserEventPublisher;
import uno.acloud.user.mapper.UserMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdminServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserEventPublisher userEventPublisher;

    @Mock
    private TeamServicePermissionClient teamServicePermissionClient;

    @InjectMocks
    private UserAdminService userAdminService;

    private User createUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    void deleteUser_selfDelete_shouldThrow() {
        when(userMapper.getById(1L)).thenReturn(createUser(1L, "admin"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userAdminService.deleteUser(1L, 1L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("管理员不能注销自身账户", ex.getMessage());
    }

    @Test
    void deleteUser_targetNotFound_shouldThrow() {
        when(userMapper.getById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userAdminService.deleteUser(1L, 99L));
        assertEquals(UserErrorCode.USER_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void deleteUser_lastSystemAdmin_shouldThrow() {
        when(userMapper.getById(2L)).thenReturn(createUser(2L, "superadmin"));
        when(teamServicePermissionClient.getSystemRolesByUserId(2L))
                .thenReturn(List.of(SystemRoleCodes.SYSTEM_ADMIN));
        when(teamServicePermissionClient.listSystemAdminUserIds())
                .thenReturn(List.of(2L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userAdminService.deleteUser(1L, 2L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能删除最后一位系统管理员"));
    }

    @Test
    void deleteUser_teamServiceUnreachableForAdmin_failClosed() {
        when(userMapper.getById(2L)).thenReturn(createUser(2L, "admin2"));
        when(teamServicePermissionClient.getSystemRolesByUserId(2L))
                .thenReturn(List.of(SystemRoleCodes.SYSTEM_ADMIN));
        when(teamServicePermissionClient.listSystemAdminUserIds()).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userAdminService.deleteUser(1L, 2L));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("无法验证系统管理员数量"));
        verify(userMapper, never()).deleteById(anyLong());
    }
}
