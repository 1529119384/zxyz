package uno.acloud.user.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;

import java.util.Map;

/**
 * 调用 email-service 的 HTTP 客户端，用于发送验证码和校验验证码。
 * 替代 main-service 本地的 MailClient。
 */
@Slf4j
@Component
public class EmailServiceMailClient extends AbstractServiceClient {

    public EmailServiceMailClient(RestClient restClient,
                                   ServiceProperties serviceProperties,
                                   @Value("${app.internal-service-token:}") String internalServiceToken) {
        super(restClient, serviceProperties.getEmailService().normalizedBaseUrl(),
              internalServiceToken, null);
    }

    @Override
    protected String serviceName() {
        return "邮件服务";
    }

    /**
     * 发送验证码邮件。
     */
    public void sendVerifyCode(String email, String scene, String requestIp) {
        try {
            Map<String, Object> payload = Map.of(
                    "email", email,
                    "scene", scene,
                    "requestIp", requestIp != null ? requestIp : ""
            );
            postJson("/api/email/internal/verify-codes/send", payload);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("发送验证码失败: email={}", email, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "验证码发送失败，请稍后再试");
        }
    }

    /**
     * 校验验证码。
     */
    public void checkVerifyCode(String email, String scene, String code) {
        try {
            Map<String, Object> payload = Map.of(
                    "email", email,
                    "scene", scene,
                    "code", code
            );
            postJson("/api/email/internal/verify-codes/check", payload);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("校验验证码失败: email={}", email, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验失败");
        }
    }
}
