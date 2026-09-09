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

        verify(teamServicePermissionClient).assignBootstrapAdminRoleStrict(42L);
    }

    @Test
    void run_usesConfiguredPasswordWhenProvided() {
        ServiceProperties sp = buildProps(true, "admin", "fixedpass123");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);
        when(authService.createBootstrapAdmin(anyString(), anyString())).thenReturn(7L);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService).createBootstrapAdmin("admin", "fixedpass123");
        verify(teamServicePermissionClient).assignBootstrapAdminRoleStrict(7L);
    }

    @Test
    void run_skipsWhenDisabled() {
        ServiceProperties sp = buildProps(false, "admin", "");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);

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

        // 不应抛异常，应用继续启动
        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(teamServicePermissionClient).assignBootstrapAdminRoleStrict(7L);
    }
}
