package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.dto.ContactVerifyRequest;
import uno.acloud.user.dto.EmailBindRequest;
import uno.acloud.user.dto.PhoneBindRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.EmailServiceMailClient;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.vo.ContactVerificationCodeVO;
import uno.acloud.user.vo.CurrentUserVO;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Slf4j
@Service
public class ContactVerificationService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9][0-9\\-\\s]{5,48}[0-9]$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String EMAIL_BIND_VERIFY_SCENE = "EMAIL_BIND";
    private static final String EMAIL_VERIFY_CODE_COOLDOWN_KEY_PREFIX = "zxyz:user:email-verify-code:cooldown:";

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final EmailServiceMailClient emailServiceMailClient;
    private final UserQueryHelper userQueryHelper;
    private final boolean returnCodeInResponse;
    /** 邮箱验证码发送冷却时长，默认 60 秒 */
    private final Duration emailVerifyCodeCooldown;

    public ContactVerificationService(UserMapper userMapper,
                                      StringRedisTemplate stringRedisTemplate,
                                      EmailServiceMailClient emailServiceMailClient,
                                      UserQueryHelper userQueryHelper,
                                      ServiceProperties serviceProperties,
                                      @Value("${app.email.verify-code.cooldown-seconds:60}") int emailVerifyCodeCooldownSeconds) {
        this.userMapper = userMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.emailServiceMailClient = emailServiceMailClient;
        this.userQueryHelper = userQueryHelper;
        this.returnCodeInResponse = serviceProperties.getVerification().isReturnCodeInResponse();
        this.emailVerifyCodeCooldown = Duration.ofSeconds(emailVerifyCodeCooldownSeconds);
    }

    public CurrentUserVO bindEmail(Long userId, EmailBindRequest request) {
        userQueryHelper.requireExistingUser(userId);
        String email = optionalText(request.getEmail());
        userQueryHelper.requireUpdated(userMapper.updateEmail(userId, email));
        return userQueryHelper.requireCurrentUser(userId);
    }

    public CurrentUserVO bindPhone(Long userId, PhoneBindRequest request) {
        userQueryHelper.requireExistingUser(userId);
        String phone = optionalText(request.getPhone());
        userQueryHelper.requireUpdated(userMapper.updatePhone(userId, phone));
        return userQueryHelper.requireCurrentUser(userId);
    }

    public ContactVerificationCodeVO createEmailVerificationCode(Long userId, String requestIp) {
        User user = userQueryHelper.requireExistingUser(userId);
        String email = requireText(user.getEmail(), "请先绑定邮箱");
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已验证，无需重复发送验证码");
        }
        String cooldownKey = acquireEmailVerifyCodeCooldown(userId, email);
        try {
            emailServiceMailClient.sendVerifyCode(email, EMAIL_BIND_VERIFY_SCENE, requestIp);
        } catch (RuntimeException e) {
            releaseEmailVerifyCodeCooldown(cooldownKey);
            throw e;
        }
        return new ContactVerificationCodeVO("email", null);
    }

    public ContactVerificationCodeVO createPhoneVerificationCode(Long userId) {
        User user = userQueryHelper.requireExistingUser(userId);
        if (!PHONE_PATTERN.matcher(requireText(user.getPhone(), "请先绑定手机号")).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        return createContactVerificationCode(userId, "phone");
    }

    public CurrentUserVO verifyContact(Long userId, ContactVerifyRequest request) {
        String type = normalizeContactType(request.getType());
        String code = optionalText(request.getCode());
        if ("email".equals(type)) {
            User user = userQueryHelper.requireExistingUser(userId);
            emailServiceMailClient.checkVerifyCode(requireText(user.getEmail(), "请先绑定邮箱"), EMAIL_BIND_VERIFY_SCENE, code);
            userQueryHelper.requireUpdated(userMapper.verifyEmail(userId));
            return userQueryHelper.requireCurrentUser(userId);
        }
        if (userMapper.countValidContactVerificationCode(userId, type, code) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码无效或已过期");
        }
        userQueryHelper.requireUpdated(userMapper.verifyPhone(userId));
        return userQueryHelper.requireCurrentUser(userId);
    }

    private ContactVerificationCodeVO createContactVerificationCode(Long userId, String type) {
        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        userMapper.upsertContactVerificationCode(userId, type, code);
        String responseCode = returnCodeInResponse ? code : null;
        return new ContactVerificationCodeVO(type, responseCode);
    }

    private String acquireEmailVerifyCodeCooldown(Long userId, String email) {
        String cooldownKey = buildEmailVerifyCodeCooldownKey(userId, email);
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", emailVerifyCodeCooldown);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码已发送，请 60 秒后再试");
            }
            return cooldownKey;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("邮箱验证码发送冷却校验失败：userId={}, email={}", userId, email, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "验证码发送校验暂不可用，请稍后再试");
        }
    }

    private void releaseEmailVerifyCodeCooldown(String cooldownKey) {
        try {
            stringRedisTemplate.delete(cooldownKey);
        } catch (RuntimeException e) {
            log.warn("邮箱验证码冷却键释放失败：key={}", cooldownKey, e);
        }
    }

    private String buildEmailVerifyCodeCooldownKey(Long userId, String email) {
        return EMAIL_VERIFY_CODE_COOLDOWN_KEY_PREFIX
                + userId
                + ":"
                + email.toLowerCase(Locale.ROOT);
    }

    private String normalizeContactType(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式类型不能为空");
        }
        return value.toLowerCase();
    }
}
