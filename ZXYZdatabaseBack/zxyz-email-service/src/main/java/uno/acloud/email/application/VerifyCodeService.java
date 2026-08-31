package uno.acloud.email.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.VerifyCode;
import uno.acloud.email.infrastructure.VerifyCodeMapper;
import uno.acloud.exception.BusinessException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class VerifyCodeService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VerifyCodeMapper verifyCodeMapper;
    private final EmailDispatchService emailDispatchService;
    private final EmailRateLimiter emailRateLimiter;
    private final EmailProperties emailProperties;
    private final EmailSendingAvailabilityService emailSendingAvailabilityService;

    public VerifyCodeService(VerifyCodeMapper verifyCodeMapper,
                             EmailDispatchService emailDispatchService,
                             EmailRateLimiter emailRateLimiter,
                             EmailProperties emailProperties,
                             EmailSendingAvailabilityService emailSendingAvailabilityService) {
        this.verifyCodeMapper = verifyCodeMapper;
        this.emailDispatchService = emailDispatchService;
        this.emailRateLimiter = emailRateLimiter;
        this.emailProperties = emailProperties;
        this.emailSendingAvailabilityService = emailSendingAvailabilityService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void sendCode(String email, String scene, String requestIp) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        emailSendingAvailabilityService.requireSendingAvailable();
        emailRateLimiter.requireVerifyCodeAllowed(normalizedEmail, requestIp);

        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        Long recordId = emailDispatchService.sendByTemplate(
                normalizedEmail,
                "EMAIL_BIND_CODE",
                Map.of("code", code, "expireMinutes", emailProperties.getVerifyCodeExpireMinutes()),
                "VERIFY_CODE",
                normalizedScene,
                null
        );

        LocalDateTime now = LocalDateTime.now();
        VerifyCode verifyCode = new VerifyCode();
        verifyCode.setEmail(normalizedEmail);
        verifyCode.setScene(normalizedScene);
        verifyCode.setCode(code);
        verifyCode.markCreated(now.plusMinutes(emailProperties.getVerifyCodeExpireMinutes()));
        verifyCode.setRequestIp(requestIp);
        verifyCode.setEmailRecordId(recordId);
        verifyCode.setCreateTime(now);
        verifyCode.setUpdateTime(now);
        verifyCodeMapper.upsert(verifyCode);
    }

    public void checkCode(String email, String scene, String code) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedScene = normalizeScene(scene);
        String normalizedCode = requireText(code, "验证码不能为空");
        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码无效或已过期");
        }
        int maxAttempts = emailProperties.getVerifyCodeMaxAttempts();
        // 一次校验先计一次尝试；达到上限即作废该码（防 6 位码爆破）
        if (verifyCodeMapper.bumpAttemptCount(normalizedEmail, normalizedScene, maxAttempts) != 1) {
            // 行已失效（无存活记录/已使用/已过期）：若此前已耗尽尝试次数则提示过多，否则无效
            Integer attempt = verifyCodeMapper.findAttemptCount(normalizedEmail, normalizedScene);
            if (attempt != null && attempt >= maxAttempts) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "尝试次数过多，请重新发送");
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码无效或已过期");
        }
        // 仅在未达上限且码正确时消费成功
        if (verifyCodeMapper.markUsedByCode(normalizedEmail, normalizedScene, normalizedCode, maxAttempts) == 1) {
            return;
        }
        // 未能消费：可能码错误，或本次尝试恰好触达上限
        Integer attempt = verifyCodeMapper.findAttemptCount(normalizedEmail, normalizedScene);
        if (attempt != null && attempt >= maxAttempts) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "尝试次数过多，请重新发送");
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码无效或已过期");
    }

    private String normalizeEmail(String email) {
        String normalizedEmail = requireText(email, "邮箱不能为空", 255, "邮箱不能超过 255 个字符").toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        return normalizedEmail;
    }

    private String normalizeScene(String scene) {
        return requireText(scene, "验证码场景不能为空", 32, "验证码场景不能超过 32 个字符").toUpperCase();
    }
}
