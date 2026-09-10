package uno.acloud.user.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.impl.AuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuthService authService;

    @Mock
    private TeamServicePermissionClient teamServicePermissionClient;

    private ServiceProperties buildProps(boolean enabled, String username, String password) {
        ServiceProperties sp = new ServiceProperties();
        sp.getAdmin().getBootstrap().setEnabled(enabled);
        sp.getAdmin().getBootstrap().setUsername(username);
        sp.getAdmin().getBootstrap().setPassword(password);
        return sp;
    }

    @Test
    void run_skipsWhenAdminAlreadyExists() {
        ServiceProperties sp = buildProps(true, "admin", "");
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("admin");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(existing);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService, never()).createBootstrapAdmin(anyString(), anyString());
        verify(teamServicePermissionClient, never()).assignBootstrapAdminRoleStrict(any());
        // 已存在用户走幂等自愈路径（后台线程异步执行）
        verify(teamServicePermissionClient, timeout(3000)).ensureDefaultRole(1L, "admin");
    }

    @Test
    void run_createsAdminAndAssignsRoleWhenMissing_withRandomPassword() {
        ServiceProperties sp = buildProps(true, "admin", "");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);
        when(authService.createBootstrapAdmin(anyString(), anyString())).thenReturn(42L);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> passCaptor = ArgumentCaptor.forClass(String.class);
        verify(authService).createBootstrapAdmin(userCaptor.capture(), passCaptor.capture());
        assertEquals("admin", userCaptor.getValue());
        assertEquals(16, passCaptor.getValue().length());

        // 角色分配为后台线程异步执行
        verify(teamServicePermissionClient, timeout(3000)).assignBootstrapAdminRoleStrict(42L);
    }

    @Test
    void run_usesConfiguredPasswordWhenProvided() {
        ServiceProperties sp = buildProps(true, "admin", "fixedpass123");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);
        when(authService.createBootstrapAdmin(anyString(), anyString())).thenReturn(7L);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService).createBootstrapAdmin("admin", "fixedpass123");
        verify(teamServicePermissionClient, timeout(3000)).assignBootstrapAdminRoleStrict(7L);
    }

    @Test
    void run_skipsWhenDisabled() {
        ServiceProperties sp = buildProps(false, "admin", "");
        // enabled=false 时 runner 在查库前即返回，不得 stub getByLoginIdentifier（Mockito 严格模式会判 UnnecessaryStubbing）

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService, never()).createBootstrapAdmin(anyString(), anyString());
    }

    @Test
    void run_doesNotThrowWhenRoleAssignmentFails() {
        ServiceProperties sp = buildProps(true, "admin", "fixedpass123");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);
        when(authService.createBootstrapAdmin(anyString(), anyString())).thenReturn(7L);
        doThrow(new RuntimeException("team-service down"))
                .when(teamServicePermissionClient).assignBootstrapAdminRoleStrict(any());

        // 不应抛异常，应用继续启动（角色分配失败由后台线程重试）
        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(teamServicePermissionClient, timeout(3000)).assignBootstrapAdminRoleStrict(7L);
    }
}
