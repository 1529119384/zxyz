package uno.acloud.user.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.impl.AuthService;

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
        // 已存在用户走幂等自愈路径（后台线程异步执行），与新建账号共用同一条 strict 授权路径：
        // 提权能力只留在部署引导链路，不再经 ensureDefaultRole 暴露给公网注册/登录（审计 2.1.1）
        verify(teamServicePermissionClient, timeout(3000)).assignBootstrapAdminRoleStrict(1L);
    }

    @Test
    void run_refusesToCreateAdminWhenPasswordNotConfigured() {
        // D1-#1：不再「随机生成 + 明文打进日志」。未配置口令 => 拒绝创建（应用仍继续启动）。
        // 这条用例同时锁死「绝不再出现随机口令」这一行为：只要有人把生成逻辑加回来，本用例即失败。
        ServiceProperties sp = buildProps(true, "admin", "");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService, never()).createBootstrapAdmin(anyString(), anyString());
        // 没有创建账号 => 也不应启动后台角色分配线程
        verify(teamServicePermissionClient, never()).assignBootstrapAdminRoleStrict(any());
    }

    @Test
    void run_treatsBlankPasswordAsNotConfigured() {
        // 只用空白字符同样视为未配置（避免 .env 里写了空格就"以为配好了"）
        ServiceProperties sp = buildProps(true, "admin", "   ");
        when(userMapper.getByLoginIdentifier("admin")).thenReturn(null);

        new AdminBootstrapRunner(userMapper, authService, teamServicePermissionClient, sp).run(null);

        verify(authService, never()).createBootstrapAdmin(anyString(), anyString());
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
