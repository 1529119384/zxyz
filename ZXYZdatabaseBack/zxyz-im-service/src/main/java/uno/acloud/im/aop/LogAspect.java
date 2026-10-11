package uno.acloud.im.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import uno.acloud.common.audit.AbstractLogAspect;
import uno.acloud.common.audit.AuditEventPublisher;
import uno.acloud.satoken.AuthServicePort;

/**
 * IM 服务操作日志切面（54 号 §2.8 审计欠账补齐）。
 *
 * <p>对齐 file/user/share/project 的既有模式：继承公共 {@link AbstractLogAspect}，
 * 切点 {@code @annotation(...Log)}（发布到 audit 侧 MQ，消费已就绪）。
 * 用户面写端点逐一挂 {@code @Log}；userId 由基类经 {@link AuthServicePort#isLogin()}
 * 探测 —— HTTP 请求线程上有会话则记录，无会话（如内部端点误标）降级为 null，绝不抛异常。</p>
 *
 * <p><b>审计模型边界（报告口径）</b>：① Netty WebSocket 侧不走 HTTP 的操作
 * （SEND_TEXT / SEND_FILE_CARD 命令、WS 握手、心跳）不在 {@code @Log} 切点可达范围内，
 * 属既有审计模型的已知边界；其中文件卡片解析走 HTTP 路径，已被覆盖。
 * ② {@code POST /api/im/ws/ticket} 刻意<b>不</b>挂 {@code @Log}：响应体是 WS 认证票据
 * （等价凭据），脱敏器按字段名匹配、裸字符串返回值不会命中 —— 挂上会把凭据明文写进
 * operate_log（R2 同型的审计泄漏）。</p>
 */
@Component
@Aspect
public class LogAspect extends AbstractLogAspect {

    public LogAspect(AuditEventPublisher auditEventPublisher, ObjectMapper objectMapper, AuthServicePort authServicePort) {
        super(auditEventPublisher, objectMapper, authServicePort);
    }

    @Override
    protected String getServiceName() {
        return "zxyz-im-service";
    }
}
