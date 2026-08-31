package uno.acloud.email.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailServerConfig;
import uno.acloud.email.domain.EmailTestStatus;
import uno.acloud.email.convert.EmailEntityMapper;
import uno.acloud.email.infrastructure.EmailServerConfigMapper;
import uno.acloud.email.dto.EmailConnectivityTestVO;
import uno.acloud.email.dto.EmailServerConfigRequest;
import uno.acloud.email.vo.EmailServerConfigVO;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Slf4j
@Service
public class EmailServerConfigService {

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_ADDRESS_LENGTH = 255;
    private static final int MAX_TRANSPORT_STRATEGY_LENGTH = 32;
    private static final String DEFAULT_CONFIG_NAME = "默认 SMTP";
    private static final String DEFAULT_TRANSPORT_STRATEGY = "SMTP_TLS";

    private final EmailServerConfigMapper configMapper;
    private final EmailSecretCipher secretCipher;
    private final SmtpConnectivityTester smtpConnectivityTester;
    private final EmailProperties emailProperties;
    private final EmailServerConfigService self;
    private final EmailEntityMapper emailEntityMapper;

    public EmailServerConfigService(EmailServerConfigMapper configMapper,
                                    EmailSecretCipher secretCipher,
                                    SmtpConnectivityTester smtpConnectivityTester,
                                    EmailProperties emailProperties,
                                    @Lazy EmailServerConfigService self,
                                    EmailEntityMapper emailEntityMapper) {
        this.configMapper = configMapper;
        this.secretCipher = secretCipher;
        this.smtpConnectivityTester = smtpConnectivityTester;
        this.emailProperties = emailProperties;
        this.self = self;
        this.emailEntityMapper = emailEntityMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = Exception.class)
    public void initializeDefaultConfig() {
        if (configMapper.countAll() > 0) {
            return;
        }
        if (!secretCipher.hasSecretKey()) {
            log.warn("未配置 EMAIL_CONFIG_SECRET，跳过默认 SMTP 配置初始化");
            return;
        }
        EmailServerConfig config = new EmailServerConfig();
        config.setConfigName(DEFAULT_CONFIG_NAME);
        config.setHost(requireText(emailProperties.getHost(), "SMTP 主机不能为空", MAX_HOST_LENGTH, "SMTP 主机不能超过 255 个字符"));
        config.setPort(normalizePort(emailProperties.getPort()));
        config.setUsername(requireText(emailProperties.getUsername(), "SMTP 账号不能为空", MAX_ADDRESS_LENGTH, "SMTP 账号不能超过 255 个字符"));
        config.setPasswordCipher(secretCipher.encrypt(emailProperties.getPassword()));
        config.setFromAddress(optionalText(emailProperties.getFrom(), MAX_ADDRESS_LENGTH, "发件人地址不能超过 255 个字符"));
        config.setTransportStrategy(DEFAULT_TRANSPORT_STRATEGY);
        config.setActive(true);
        config.setLastTestStatus(EmailTestStatus.NOT_TESTED);
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(config.getCreateTime());
        configMapper.insert(config);
        log.info("已根据 application.yml 初始化默认 SMTP 配置：host={}, username={}", config.getHost(), config.getUsername());
    }

    public List<EmailServerConfigVO> listConfigs() {
        return configMapper.listAll().stream()
                .map(this::toVO)
                .toList();
    }

    public Optional<EmailServerConfigVO> getCurrentConfig() {
        EmailServerConfig active = configMapper.getActive();
        return Optional.ofNullable(toVO(active));
    }

    public EmailServerConfig requireConfig(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件服务器配置不存在");
        }
        EmailServerConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件服务器配置不存在");
        }
        return config;
    }

    public EmailServerConfig requireActiveConfig() {
        EmailServerConfig config = configMapper.getActive();
        if (config == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先配置并启用邮件服务器");
        }
        return config;
    }

    @Transactional(rollbackFor = Exception.class)
    public EmailServerConfigVO createConfig(EmailServerConfigRequest request) {
        EmailServerConfig config = buildConfig(null, request, true);
        config.setActive(false);
        configMapper.insert(config);
        return toVO(config);
    }

    @Transactional(rollbackFor = Exception.class)
    public EmailServerConfigVO updateConfig(Long id, EmailServerConfigRequest request) {
        EmailServerConfig existing = requireConfig(id);
        EmailServerConfig config = buildConfig(existing, request, false);
        config.setId(existing.getId());
        configMapper.update(config);
        return toVO(configMapper.selectById(existing.getId()));
    }

    public String decryptPassword(EmailServerConfig config) {
        return secretCipher.decrypt(config.getPasswordCipher());
    }

    public EmailConnectivityTestVO testConfig(Long id) {
        EmailServerConfig config = requireConfig(id);
        EmailConnectivityTestVO result = smtpConnectivityTester.test(config, decryptPassword(config));
        configMapper.updateLastTest(config.getId(), result.getStatus(), result.getTestTime(), result.getMessage());
        return result;
    }

    public EmailServerConfigVO activateConfig(Long id) {
        // 先在事务外执行 SMTP 连通性测试，避免网络 I/O 阻塞数据库连接
        EmailConnectivityTestVO result = testConfig(id);
        if (!EmailTestStatus.SUCCESS.equals(result.getStatus())) {
            // 切换前必须真实连通，避免把生产发送入口切到不可用账号。
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SMTP 连接测试失败：" + result.getMessage(), result);
        }
        // 通过代理调用事务方法，确保事务边界正确
        return self.doActivate(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public EmailServerConfigVO doActivate(Long id) {
        configMapper.deactivateAll();
        if (configMapper.activate(id) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件服务器配置不存在");
        }
        return getCurrentConfig().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "邮件服务器配置不存在"));
    }

    @Nullable
    public EmailServerConfigVO toVO(@Nullable EmailServerConfig config) {
        return emailEntityMapper.toServerConfigVO(config);
    }

    private EmailServerConfig buildConfig(EmailServerConfig existing,
                                          EmailServerConfigRequest request,
                                          boolean passwordRequired) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件服务器配置不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        EmailServerConfig config = new EmailServerConfig();
        config.setConfigName(requireText(request.getConfigName(), "配置名称不能为空", MAX_NAME_LENGTH, "配置名称不能超过 64 个字符"));
        config.setHost(requireText(request.getHost(), "SMTP 主机不能为空", MAX_HOST_LENGTH, "SMTP 主机不能超过 255 个字符"));
        config.setPort(normalizePort(request.getPort()));
        config.setUsername(requireText(request.getUsername(), "SMTP 账号不能为空", MAX_ADDRESS_LENGTH, "SMTP 账号不能超过 255 个字符"));
        config.setPasswordCipher(resolvePasswordCipher(existing, request.getPassword(), passwordRequired));
        config.setFromAddress(optionalText(request.getFromAddress(), MAX_ADDRESS_LENGTH, "发件人地址不能超过 255 个字符"));
        config.setTransportStrategy(normalizeTransportStrategy(request.getTransportStrategy()));
        config.setActive(existing != null && existing.isEnabled());
        config.setLastTestStatus(existing == null ? EmailTestStatus.NOT_TESTED : existing.getLastTestStatus());
        config.setLastTestTime(existing == null ? null : existing.getLastTestTime());
        config.setLastTestMessage(existing == null ? null : existing.getLastTestMessage());
        config.setCreateTime(existing == null ? now : existing.getCreateTime());
        config.setUpdateTime(now);
        return config;
    }

    private String resolvePasswordCipher(EmailServerConfig existing, String password, boolean passwordRequired) {
        if (password != null && !password.isBlank()) {
            return secretCipher.encrypt(password);
        }
        if (!passwordRequired && existing != null && existing.getPasswordCipher() != null && !existing.getPasswordCipher().isBlank()) {
            return existing.getPasswordCipher();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "SMTP 授权码不能为空");
    }

    private int normalizePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SMTP 端口必须在 1 到 65535 之间");
        }
        return port;
    }

    private String normalizeTransportStrategy(String value) {
        String strategy = optionalText(value, MAX_TRANSPORT_STRATEGY_LENGTH, "传输策略不能超过 32 个字符");
        if (strategy == null) {
            return DEFAULT_TRANSPORT_STRATEGY;
        }
        return strategy.toUpperCase(Locale.ROOT);
    }
}
