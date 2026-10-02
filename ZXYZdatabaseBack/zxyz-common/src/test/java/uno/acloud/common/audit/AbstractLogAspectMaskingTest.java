package uno.acloud.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.satoken.AuthServicePort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AbstractLogAspect} 发布端脱敏的<b>端到端</b>测试（#13 / B-2）。
 *
 * <p>不直接测私有方法，而是喂一个真实的、含敏感字段的 DTO 给切面，
 * 捕获真正会被发布出去的 {@link OperateLog}，断言 {@code method_params} 里没有明文。
 * 这条链路正是报告指出的泄露路径：参数经 {@code Arrays.toString} 变成
 * {@code Dto(password=xxx)}，<b>不是</b> JSON 形态。</p>
 */
@ExtendWith(MockitoExtension.class)
class AbstractLogAspectMaskingTest {

    /** 模拟「注解都漏了」的 DTO：既无 @ToString(exclude) 也无 @JsonProperty(WRITE_ONLY)。 */
    static class SloppyRequest {
        private final String username = "alice";
        private final String password = "PlainTextPwd";
        private final String oldPassword = "OldPlainPwd";

        @Override
        public String toString() {
            return "SloppyRequest(username=" + username + ", password=" + password
                    + ", oldPassword=" + oldPassword + ")";
        }
    }

    /** 自定义 toString 形态：值里带空格与引号，逃避朴素的分隔符假设。 */
    static class QuotedSecretDto {
        @Override
        public String toString() {
            return "QuotedSecretDto(token=\"tok with spaces\")";
        }
    }

    @Mock
    private AuditEventPublisher publisher;
    @Mock
    private AuthServicePort authServicePort;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private Signature signature;

    private AbstractLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AbstractLogAspect(publisher, new ObjectMapper(), authServicePort) {
            @Override
            protected String getServiceName() {
                return "test-service";
            }
        };
    }

    private OperateLog invokeAspectWith(Object... args) throws Throwable {
        when(joinPoint.getTarget()).thenReturn(this);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("testMethod");
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(null);
        when(authServicePort.isLogin()).thenReturn(false);

        aspect.recordLog(joinPoint);

        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordLog_masksPlainPasswordInLombokStyleToString() throws Throwable {
        OperateLog published = invokeAspectWith(new SloppyRequest());

        assertNotNull(published.getMethodParams());
        assertFalse(published.getMethodParams().contains("PlainTextPwd"),
                "password 明文不得进入 method_params，实际：" + published.getMethodParams());
        assertFalse(published.getMethodParams().contains("OldPlainPwd"),
                "带前缀的 oldPassword 同样不得泄露，实际：" + published.getMethodParams());
        assertTrue(published.getMethodParams().contains("username=alice"),
                "非敏感字段应保留以便审计，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_masksQuotedSecretWithSpaces() throws Throwable {
        OperateLog published = invokeAspectWith(new QuotedSecretDto());

        assertFalse(String.valueOf(published.getMethodParams()).contains("tok with spaces"),
                "带引号/空格的字符串值同样是泄露，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_keepsNonSensitiveArgumentsIntact() throws Throwable {
        OperateLog published = invokeAspectWith(42L, "report.pdf", 7);

        assertTrue(published.getMethodParams().contains("42"),
                "普通参数不得被打码误伤，实际：" + published.getMethodParams());
        assertTrue(published.getMethodParams().contains("report.pdf"));
    }

    @Test
    void recordLog_publishesEvenWhenMaskingIsNotNeeded() throws Throwable {
        // 反向断言：脱敏不得导致「该发的审计事件不发」
        OperateLog published = invokeAspectWith("plain");

        assertNotNull(published);
        assertTrue(published.getMethodParams().contains("plain"));
        verify(publisher, org.mockito.Mockito.times(1)).publish(any(OperateLog.class));
    }

    // ==================== R1：第三层（结构层/反射）必须是活代码 ====================

    /**
     * 结构层不可绕过的证据：{@code toString()} <b>只返回值、完全不含键名</b>。
     * <p>文本层对此无能为力（拼成文本后「哪个值是口令」这一信息已丢失），
     * 只有结构层的字段名扫描能拦住 —— 这条用例证明第三层确实在执行，
     * 而不是像 R1 指出的那样是「声称存在、从不执行」的死代码。</p>
     */
    static class OpaqueToStringRequest {
        @SuppressWarnings("unused")
        private final String password = "HiddenPwd";

        @SuppressWarnings("unused")
        private final String username = "bob";

        @Override
        public String toString() {
            // 故意只输出值，不输出任何键名 —— 任何基于文本形态的正则都不可能识别
            return "OpaqueToStringRequest[HiddenPwd]";
        }
    }

    @Test
    void recordLog_masksSensitiveFieldEvenWhenToStringOmitsFieldNames() throws Throwable {
        OperateLog published = invokeAspectWith(new OpaqueToStringRequest());

        assertFalse(String.valueOf(published.getMethodParams()).contains("HiddenPwd"),
                "toString 不含键名时也必须拦住（证明结构层在跑），实际：" + published.getMethodParams());
        assertTrue(String.valueOf(published.getMethodParams()).contains("password=***"),
                "命中敏感字段名后应逐字段渲染并打码，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_keepsNonSensitiveFieldsOfSensitiveObject() throws Throwable {
        OperateLog published = invokeAspectWith(new SloppyRequest());

        // 逐字段渲染：非敏感字段保留（保住审计价值），不是整对象丢弃
        assertTrue(String.valueOf(published.getMethodParams()).contains("username=alice"),
                "同对象的非敏感字段应保留以便审计，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_masksSensitiveReturnValueStructurally() throws Throwable {
        // 用真实 VO（有 getter ⇒ 能被 Jackson 序列化）验证返回值的结构层打码
        when(joinPoint.getTarget()).thenReturn(this);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("testMethod");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn(new CredentialVo("bob", "VoPwd"));
        when(authServicePort.isLogin()).thenReturn(false);

        aspect.recordLog(joinPoint);

        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(publisher).publish(captor.capture());
        String returnValue = String.valueOf(captor.getValue().getReturnValue());
        assertFalse(returnValue.contains("VoPwd"),
                "返回值同样要过结构层，实际：" + returnValue);
        assertTrue(returnValue.contains("bob"), "非敏感字段保留，实际：" + returnValue);
    }

    @Test
    void recordLog_keepsReturnValueJsonShapeForScalars() throws Throwable {
        // 回归修复：return_value 列一直是 JSON 文本，字符串返回值必须存为 "ok" 而不是 [ok]
        when(joinPoint.getTarget()).thenReturn(this);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("testMethod");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        when(authServicePort.isLogin()).thenReturn(false);

        aspect.recordLog(joinPoint);

        ArgumentCaptor<OperateLog> captor = ArgumentCaptor.forClass(OperateLog.class);
        verify(publisher).publish(captor.capture());
        assertEquals("\"ok\"", captor.getValue().getReturnValue(),
                "标量返回值必须保持 JSON 形状（历史契约），不得变成 [ok]");
    }

    /** 带 getter 的 VO：可被 Jackson 序列化，用于验证返回值结构层打码。 */
    public static class CredentialVo {
        private final String username;
        private final String password;

        public CredentialVo(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    @Test
    void recordLog_handlesMapAndCollectionArguments() throws Throwable {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", "bob");
        body.put("password", "MapPwd");

        OperateLog published = invokeAspectWith(body, java.util.List.of("plain", "alsoPlain"));

        assertFalse(String.valueOf(published.getMethodParams()).contains("MapPwd"),
                "Map 载荷中键名敏感的条目必须打码，实际：" + published.getMethodParams());
        assertTrue(String.valueOf(published.getMethodParams()).contains("bob"),
                "Map 中非敏感条目应保留，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_doesNotBlowUpOnCyclicOrDeepStructures() throws Throwable {
        // 环状引用 + 自引用：结构层必须有深度/环保护，绝不能抛异常或死循环
        java.util.List<Object> cyclic = new java.util.ArrayList<>();
        cyclic.add(cyclic);
        cyclic.add(new SloppyRequest());

        OperateLog published = invokeAspectWith(cyclic);

        assertNotNull(published.getMethodParams());
        assertFalse(published.getMethodParams().contains("PlainTextPwd"),
                "环状结构中的敏感字段仍要打码，实际：" + published.getMethodParams());
    }

    @Test
    void recordLog_handlesNullArgument() throws Throwable {
        OperateLog published = invokeAspectWith((Object) null, new SloppyRequest());

        assertFalse(String.valueOf(published.getMethodParams()).contains("PlainTextPwd"),
                "null 参数不得打乱同批其它参数的脱敏，实际：" + published.getMethodParams());
    }
}
