package uno.acloud.team.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.audit.AuditEventPublisher;
import uno.acloud.common.audit.OperateLog;
import uno.acloud.common.audit.Log;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.team.dto.team.UpdateTeamMemberStatusRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * team 审计切面触发测试（54 号 §2.8 收口）：
 * 基类 {@link AbstractLogAspect} 的行为（脱敏、耗时、匿名降级）已由 common 的
 * {@code AbstractLogAspectMaskingTest} 端到端覆盖，本类钉住 team 侧的接线：
 * serviceName=team、经 {@code uno.acloud.team.controller.*} 真实签名参数渲染、
 * 业务异常时**不**发布审计（防毒）。参数 DTO 含 password 字段（创建成员请求），
 * 顺带断言结构层脱敏在 team 参数形态上生效。
 */
@ExtendWith(MockitoExtension.class)
class LogAspectTest {

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Signature signature;

    @Mock
    private AuthServicePort authServicePort;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private LogAspect logAspect;

    @Test
    void recordLogShouldPublishOperateLogEventWithTeamServiceName() throws Throwable {
        // 模拟 TeamController#createMember(Long userId, Long teamId, CreateTeamMemberRequest) 的真实签名
        CreateMemberRequestStub request = new CreateMemberRequestStub();
        request.setUsername("newuser");
        request.setPassword("plain-password");

        when(joinPoint.getTarget()).thenReturn(new TeamControllerStub());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("createMember");
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L, 10L, request});
        when(joinPoint.proceed()).thenReturn("ok");

        when(authServicePort.isLogin()).thenReturn(true);
        when(authServicePort.getCurrentUserId()).thenReturn(77L);

        Object result = logAspect.recordLog(joinPoint);

        assertEquals("ok", result);

        ArgumentCaptor<OperateLog> logCaptor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(logCaptor.capture());

        OperateLog operateLog = logCaptor.getValue();
        assertEquals(77L, operateLog.getOperateUser());
        assertEquals("zxyz-team-service", operateLog.getServiceName());
        assertEquals(TeamControllerStub.class.getName(), operateLog.getClassName());
        assertEquals("createMember", operateLog.getMethodName());
        assertTrue(operateLog.getCostTime() >= 0);
        // 结构层脱敏：password 字段必须打码，username 保留审计价值
        assertTrue(operateLog.getMethodParams().contains("password=***"),
                "password 字段必须被结构层脱敏，实际=" + operateLog.getMethodParams());
        assertTrue(operateLog.getMethodParams().contains("username=newuser"),
                "非敏感字段保留原文以保住审计价值，实际=" + operateLog.getMethodParams());
        assertEquals("\"ok\"", operateLog.getReturnValue());
    }

    @Test
    void recordLogShouldAllowAnonymousUserWhenSessionLookupFails() throws Throwable {
        when(joinPoint.getTarget()).thenReturn(new TeamControllerStub());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("leaveTeam");
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L, 10L});
        when(joinPoint.proceed()).thenReturn(null);

        when(authServicePort.isLogin()).thenThrow(new IllegalStateException("session unavailable"));

        logAspect.recordLog(joinPoint);

        ArgumentCaptor<OperateLog> logCaptor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(logCaptor.capture());
        assertNull(logCaptor.getValue().getOperateUser());
        assertNull(logCaptor.getValue().getReturnValue());
    }

    @Test
    void recordLogShouldNotPublishWhenBusinessThrows() throws Throwable {
        // 审计只记「成功执行」的操作（与基类实现一致）：proceed 抛异常时不发布，
        // 避免把「被拒绝/失败」的请求写入操作日志形成噪音与误导。
        when(joinPoint.getTarget()).thenReturn(new TeamControllerStub());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("updateMemberStatus");
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L, 10L, 2L, new UpdateTeamMemberStatusRequest()});
        when(joinPoint.proceed()).thenThrow(new RuntimeException("boom"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> logAspect.recordLog(joinPoint));

        verify(auditEventPublisher, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any());
    }

    /** 仅用于提供 {@code @Log} 注解上下文与真实包名的桩 Controller。 */
    static class TeamControllerStub {
        @Log
        public String createMember(Long userId, Long teamId, CreateMemberRequestStub request) {
            return "ok";
        }

        @Log
        public void leaveTeam(Long userId, Long teamId) {
        }

        @Log
        public void updateMemberStatus(Long operatorId, Long teamId, Long userId,
                                       UpdateTeamMemberStatusRequest request) {
        }
    }

    /** 与 CreateTeamMemberRequest 同形态的桩（含 password 敏感字段），避免依赖具体 DTO 构造。 */
    public static class CreateMemberRequestStub {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
