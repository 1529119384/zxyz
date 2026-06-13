package uno.acloud.email.application;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.dto.EmailRuntimeStatusVO;
import uno.acloud.exception.BusinessException;

@Service
public class EmailSendingAvailabilityService {

    public static final String SEND_DISABLED_MESSAGE = "邮件发送功能已关闭，请联系管理员";

    private final EmailProperties emailProperties;
    private final EmailServerConfigService emailServerConfigService;

    public EmailSendingAvailabilityService(EmailProperties emailProperties,
                                           EmailServerConfigService emailServerConfigService) {
        this.emailProperties = emailProperties;
        this.emailServerConfigService = emailServerConfigService;
    }

    public void requireSendingAvailable() {
        if (!emailProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, SEND_DISABLED_MESSAGE);
        }
        emailServerConfigService.requireActiveConfig();
    }

    public EmailRuntimeStatusVO getRuntimeStatus() {
        boolean activeServerConfigured = emailServerConfigService.getCurrentConfig().isPresent();
        boolean sendingEnabled = emailProperties.isEnabled();
        String message;
        if (!sendingEnabled) {
            message = SEND_DISABLED_MESSAGE;
        } else if (!activeServerConfigured) {
            message = "请先配置并启用邮件服务器";
        } else {
            message = "邮件发送已开启";
        }
        return new EmailRuntimeStatusVO(sendingEnabled, activeServerConfigured, message);
    }
}
