package uno.acloud.user.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import uno.acloud.common.audit.AbstractLogAspect;
import uno.acloud.common.audit.AuditEventPublisher;
import uno.acloud.satoken.AuthServicePort;

@Component
@Aspect
public class LogAspect extends AbstractLogAspect {

    public LogAspect(AuditEventPublisher auditEventPublisher, ObjectMapper objectMapper, AuthServicePort authServicePort) {
        super(auditEventPublisher, objectMapper, authServicePort);
    }

    @Override
    protected String getServiceName() {
        return "zxyz-user-service";
    }
}
