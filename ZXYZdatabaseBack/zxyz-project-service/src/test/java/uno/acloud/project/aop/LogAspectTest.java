package uno.acloud.project.aop;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void recordLogShouldPublishOperateLogEventAfterBusinessExecution() throws Throwable {
        DemoTarget demoTarget = new DemoTarget();
        when(joinPoint.getTarget()).thenReturn(demoTarget);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("demoMethod");
        when(joinPoint.getArgs()).thenReturn(new Object[]{"alpha", 7});
        when(joinPoint.proceed()).thenReturn("ok");

        when(authServicePort.isLogin()).thenReturn(true);
        when(authServicePort.getCurrentUserId()).thenReturn(99L);

        Object result = logAspect.recordLog(joinPoint);

        assertEquals("ok", result);

        ArgumentCaptor<OperateLog> logCaptor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(logCaptor.capture());

        OperateLog operateLog = logCaptor.getValue();
        assertEquals(99L, operateLog.getOperateUser());
        assertEquals("zxyz-project-service", operateLog.getServiceName());
        assertEquals(DemoTarget.class.getName(), operateLog.getClassName());
        assertEquals("demoMethod", operateLog.getMethodName());
        assertEquals("[alpha, 7]", operateLog.getMethodParams());
        assertEquals("\"ok\"", operateLog.getReturnValue());
        assertTrue(operateLog.getCostTime() >= 0);
    }

    @Test
    void recordLogShouldAllowAnonymousUserWhenSessionLookupFails() throws Throwable {
        DemoTarget demoTarget = new DemoTarget();
        when(joinPoint.getTarget()).thenReturn(demoTarget);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("demoMethod");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn(null);

        when(authServicePort.isLogin()).thenThrow(new IllegalStateException("session unavailable"));

        logAspect.recordLog(joinPoint);

        ArgumentCaptor<OperateLog> logCaptor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(logCaptor.capture());
        assertNull(logCaptor.getValue().getOperateUser());
        assertNull(logCaptor.getValue().getReturnValue());
    }

    static class DemoTarget {
        @Log
        public String demoMethod() {
            return "ok";
        }
    }
}
