package uno.acloud.im.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import uno.acloud.common.audit.AuditEventPublisher;
import uno.acloud.common.audit.OperateLog;
import uno.acloud.satoken.AuthServicePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 审计切面生效性最小单测（54 号 §2.8 审计欠账补齐的验证钉）。
 *
 * <p>用 AspectJProxyFactory 对测试目标织入 {@link LogAspect}，断言：
 * ① 挂 {@code @Log} 的方法触发 publisher 调用；② serviceName 归属 zxyz-im-service；
 * ③ userId 来自 AuthServicePort（HTTP 会话语义）；④ 未挂 {@code @Log} 的方法不触发。</p>
 */
@ExtendWith(MockitoExtension.class)
class ImLogAspectTest {

    @Mock
    private AuditEventPublisher auditEventPublisher;
    @Mock
    private AuthServicePort authServicePort;

    private ObjectMapper objectMapper;
    private LogAspect aspect;

    /** 被织入的测试目标：模拟 controller 方法形态。 */
    public static class SampleController {

        @uno.acloud.common.audit.Log
        public String muteMember(String reason) {
            return "muted:" + reason;
        }

        @uno.acloud.common.audit.Log
        public String failingAction() {
            throw new IllegalStateException("boom");
        }

        public String plainRead() {
            return "data";
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aspect = new LogAspect(auditEventPublisher, objectMapper, authServicePort);
    }

    @SuppressWarnings("unchecked")
    private SampleController proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleController());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    void annotatedMethod_publishesOperateLogWithImServiceName() {
        when(authServicePort.isLogin()).thenReturn(true);
        when(authServicePort.getCurrentUserId()).thenReturn(42L);

        String result = proxied().muteMember("spam");

        assertEquals("muted:spam", result);
        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(captor.capture());
        OperateLog log = captor.getValue();
        assertEquals("zxyz-im-service", log.getServiceName());
        assertEquals(42L, log.getOperateUser());
        assertEquals("muteMember", log.getMethodName());
        assertNotNull(log.getOperateTime());
        assertNotNull(log.getCostTime());
    }

    @Test
    void annotatedMethod_withoutLoginSession_publishesNullUserButStillAudits() {
        // 内部链路/无会话上下文：审计不缺位（operateUser 为 null），且绝不抛异常阻断业务
        when(authServicePort.isLogin()).thenReturn(false);

        String result = proxied().muteMember("reason");

        assertEquals("muted:reason", result);
        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(auditEventPublisher).publish(captor.capture());
        OperateLog log = captor.getValue();
        assertEquals("zxyz-im-service", log.getServiceName());
        assertEquals(null, log.getOperateUser());
    }

    @Test
    void annotatedMethod_bizFailure_doesNotPublish() {
        // 与 file/user 既有口径一致：proceed 抛异常时审计不落（成功路径才记）
        when(authServicePort.isLogin()).thenReturn(true);

        try {
            proxied().failingAction();
        } catch (IllegalStateException expected) {
            // 业务异常原样上抛
        }

        verify(auditEventPublisher, org.mockito.Mockito.never()).publish(any());
    }

    @Test
    void plainMethod_withoutLogAnnotation_notIntercepted() {
        String result = proxied().plainRead();

        assertEquals("data", result);
        verify(auditEventPublisher, org.mockito.Mockito.never()).publish(any());
    }
}
