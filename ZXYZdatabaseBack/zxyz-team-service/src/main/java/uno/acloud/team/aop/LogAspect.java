package uno.acloud.team.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import uno.acloud.common.audit.AbstractLogAspect;
import uno.acloud.common.audit.AuditEventPublisher;
import uno.acloud.satoken.AuthServicePort;

/**
 * team-service 操作审计切面。
 *
 * <p>与 user/file/share/project 的 {@code LogAspect} 同型：继承 common 的
 * {@link AbstractLogAspect}（切点 {@code @annotation(...Log)}，发布端脱敏 + MQ 发布均由基类完成），
 * 子类只提供服务名。切面以 {@code @Component @Aspect} 注册，由
 * {@code ZxyzTeamServiceApplication} 的 {@code @ComponentScan("uno.acloud.team")} 扫描生效。</p>
 */
@Component
@Aspect
public class LogAspect extends AbstractLogAspect {

    public LogAspect(AuditEventPublisher auditEventPublisher, ObjectMapper objectMapper, AuthServicePort authServicePort) {
        super(auditEventPublisher, objectMapper, authServicePort);
    }

    @Override
    protected String getServiceName() {
        return "zxyz-team-service";
    }
}
