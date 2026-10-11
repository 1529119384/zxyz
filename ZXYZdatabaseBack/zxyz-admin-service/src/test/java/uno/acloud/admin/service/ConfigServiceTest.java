package uno.acloud.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private SysConfigAuditMapper auditMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private JasyptEncryptor jasyptEncryptor;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new ConfigService(configMapper, auditMapper, stringRedisTemplate, jasyptEncryptor);
    }

    // ==================== get(String key) ====================

    @Test
    void get_existingKey_returnsDecryptedValue() {
        // given
        SysConfig config = new SysConfig();
        config.setConfigKey("app.name");
        config.setConfigValue("ENC(encrypted_value)");
        when(configMapper.selectByKey("app.name")).thenReturn(config);
        when(jasyptEncryptor.decrypt("ENC(encrypted_value)")).thenReturn("plain_value");

        // when
        String result = configService.get("app.name");

        // then
        assertEquals("plain_value", result);
        verify(configMapper).selectByKey("app.name");
        verify(jasyptEncryptor).decrypt("ENC(encrypted_value)");

        // 第二次调用应命中 Caffeine 缓存，不再查询 DB
        String cachedResult = configService.get("app.name");
        assertEquals("plain_value", cachedResult);
        verify(configMapper, times(1)).selectByKey("app.name");
    }

    @Test
    void get_missingKey_returnsNull() {
        // given
        when(configMapper.selectByKey("app.missing")).thenReturn(null);

        // when
        String result = configService.get("app.missing");

        // then
        assertNull(result);
        verify(configMapper).selectByKey("app.missing");
        verify(jasyptEncryptor, never()).decrypt(any());
    }

    @Test
    void get_nullValue_returnsNull() {
        // given
        SysConfig config = new SysConfig();
        config.setConfigKey("app.null");
        config.setConfigValue(null);
        when(configMapper.selectByKey("app.null")).thenReturn(config);
        when(jasyptEncryptor.decrypt(null)).thenReturn(null);

        // when
        String result = configService.get("app.null");

        // then
        assertNull(result);
        verify(configMapper).selectByKey("app.null");
        verify(jasyptEncryptor).decrypt(null);
    }

    // ==================== get(String key) — 敏感键掩码（ISSUE/54 R2）====================

    @Test
    void get_sensitiveKey_returnsMask_regardlessOfStorageForm() {
        // given — 敏感键（键名含 password）无论库中是 ENC 密文还是历史遗留明文，读侧都必须掩码
        SysConfig config = new SysConfig();
        config.setConfigKey("smtp.password");
        config.setConfigValue("plain-secret-in-db");
        when(configMapper.selectByKey("smtp.password")).thenReturn(config);

        // when
        String result = configService.get("smtp.password");

        // then — 返回掩码而非明文；且不把值送进解密器
        assertEquals("ENC:****", result);
        verify(jasyptEncryptor, never()).decrypt(any());
    }

    @Test
    void get_sensitiveKeyStoredAsCipher_stillReturnsMask() {
        // given
        SysConfig config = new SysConfig();
        config.setConfigKey("oss.accessKey");
        config.setConfigValue("ENC(ciphertext)");
        when(configMapper.selectByKey("oss.accessKey")).thenReturn(config);

        // when / then
        assertEquals("ENC:****", configService.get("oss.accessKey"));
        verify(jasyptEncryptor, never()).decrypt(any());
    }

    @Test
    void get_sensitiveKeyMissing_returnsNullNotMask() {
        // given — 键不存在时仍应走 NOT_FOUND 语义（null），掩码只用于「存在但敏感」
        when(configMapper.selectByKey("smtp.password")).thenReturn(null);

        // when / then
        assertNull(configService.get("smtp.password"));
    }

    @Test
    void get_nonSensitiveKey_behavesUnchanged() {
        // given — 非敏感键不受掩码改造影响
        SysConfig config = new SysConfig();
        config.setConfigKey("app.team.default-max-members");
        config.setConfigValue("100");
        when(configMapper.selectByKey("app.team.default-max-members")).thenReturn(config);
        when(jasyptEncryptor.decrypt("100")).thenReturn("100");

        // when / then
        assertEquals("100", configService.get("app.team.default-max-members"));
    }

    // ==================== get(String key, Class<T> type) ====================

    @Test
    void getType_integerValue_returnsInteger() {
        // given
        SysConfig config = new SysConfig();
        config.setConfigKey("app.port");
        config.setConfigValue("ENC(MTAwMC1pbnQ=)");
        when(configMapper.selectByKey("app.port")).thenReturn(config);
        when(jasyptEncryptor.decrypt("ENC(MTAwMC1pbnQ=)")).thenReturn("42");

        // when
        Integer result = configService.get("app.port", Integer.class);

        // then
        assertEquals(42, result);
    }

    @Test
    void getType_unsupportedType_throwsException() {
        // given
        SysConfig config = new SysConfig();
        config.setConfigKey("app.rate");
        config.setConfigValue("3.14");
        when(configMapper.selectByKey("app.rate")).thenReturn(config);
        when(jasyptEncryptor.decrypt("3.14")).thenReturn("3.14");

        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> configService.get("app.rate", Double.class));
    }

    // ==================== isSensitive / maskIfSensitive（口径自检）====================

    @Test
    void isSensitive_coversExpectedMarkerFamilies() {
        // 命中清单的典型键（审计掩码与落库加密共用同一判定）
        assertTrue(ConfigService.isSensitive("smtp.password"));
        assertTrue(ConfigService.isSensitive("oss.accessKey"));
        assertTrue(ConfigService.isSensitive("app.oss.bucket-secret"));
        assertTrue(ConfigService.isSensitive("share.cookie.token"));
        assertTrue(ConfigService.isSensitive("internal.jasypt.key"));
        assertTrue(ConfigService.isSensitive("EMAIL_SMTP_HOST"));
        // 非敏感键不得误伤（大小写不敏感子串的反例）
        assertFalse(ConfigService.isSensitive("app.name"));
        assertFalse(ConfigService.isSensitive("app.team.default-max-members"));
        assertFalse(ConfigService.isSensitive(""));
        assertFalse(ConfigService.isSensitive(null));
    }

    @Test
    void maskIfSensitive_keepsNonSensitiveValueUntouched() {
        assertEquals("100", ConfigService.maskIfSensitive("app.max", "100"));
        assertNull(ConfigService.maskIfSensitive("smtp.password", null));
        // 空白值原样返回（与 null 同口径），掩码语义只作用于「有真实内容」的敏感键
        assertEquals("  ", ConfigService.maskIfSensitive("smtp.password", "  "));
        assertEquals("ENC:****", ConfigService.maskIfSensitive("smtp.password", "whatever"));
    }

    // ==================== update(String key, String value, Long operatorId) ====================

    @Test
    void update_writesAuditAndNotifiesAfterCommit() {
        // given — 用 MockedStatic 包裹 TransactionSynchronizationManager
        // 使 registerSynchronization 能在无真实事务的测试环境中工作
        try (var mockedStatic = mockStatic(TransactionSynchronizationManager.class)) {
            // selectByKey 返回 null（表示该配置之前不存在，oldValue = null）
            when(configMapper.selectByKey("app.name")).thenReturn(null);

            // when
            configService.update("app.name", "new-value", 1L);

            // then — 验证 DB 操作（非敏感键原样落库，不触发加密）
            verify(configMapper).updateValue("app.name", "new-value");
            verify(jasyptEncryptor, never()).encrypt(any());
            verify(auditMapper).insert(eq("app.name"), isNull(), eq("new-value"), eq(1L));

            // then — 手动触发 afterCommit，验证 Redis Pub/Sub 通知
            ArgumentCaptor<TransactionSynchronization> syncCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
            mockedStatic.verify(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            syncCaptor.getValue().afterCommit();
            verify(stringRedisTemplate).convertAndSend("zxyz:config:changed", "app.name");
        }
    }

    // ==================== update — 敏感键强制加密落库（ISSUE/54 R2）====================

    @Test
    void update_sensitiveKey_encryptsBeforePersisting() {
        // given
        try (var mockedStatic = mockStatic(TransactionSynchronizationManager.class)) {
            when(configMapper.selectByKey("smtp.password")).thenReturn(null);
            when(jasyptEncryptor.encrypt("new-smtp-secret")).thenReturn("ENC(cipher-for-smtp)");

            // when
            configService.update("smtp.password", "new-smtp-secret", 1L);

            // then — 落库值必须是 ENC 前缀密文，而非明文；审计仍按明文入参掩码
            verify(configMapper).updateValue("smtp.password", "ENC(cipher-for-smtp)");
            verify(auditMapper).insert(eq("smtp.password"), isNull(), eq("ENC:****"), eq(1L));
            mockedStatic.verify(() -> TransactionSynchronizationManager.registerSynchronization(any()));
        }
    }

    @Test
    void update_sensitiveKeyAlreadyEncrypted_doesNotDoubleEncrypt() {
        // given — 已是 ENC(...) 格式的值（例如从别处复制来的密文）原样落库，不二次加密
        try (var mockedStatic = mockStatic(TransactionSynchronizationManager.class)) {
            when(configMapper.selectByKey("oss.accessKey")).thenReturn(null);
            when(jasyptEncryptor.isEncrypted("ENC(existing-cipher)")).thenReturn(true);

            // when
            configService.update("oss.accessKey", "ENC(existing-cipher)", 1L);

            // then
            verify(configMapper).updateValue("oss.accessKey", "ENC(existing-cipher)");
            verify(jasyptEncryptor, never()).encrypt(any());
            // 审计侧掩码不变（value 本身就不是明文）
            verify(auditMapper).insert(eq("oss.accessKey"), isNull(), eq("ENC:****"), eq(1L));
        }
    }

    @Test
    void update_sensitiveKey_rejectsMaskPlaceholderAsNewValue() {
        // given — 把 GET 回的掩码 ENC:**** 原样提交回来没有信息量，只会把真实值改坏
        try (var mockedStatic = mockStatic(TransactionSynchronizationManager.class)) {
            // when / then
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> configService.update("smtp.password", "ENC:****", 1L));
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());            // 拒绝必须发生在任何写操作之前
            verify(configMapper, never()).updateValue(any(), any());
            verify(auditMapper, never()).insert(any(), any(), any(), any());
        }
    }

    @Test
    void update_sensitiveKeyAuditMasksBothOldAndNewValue() {
        // given — oldValue 经 get() 回的是掩码，new value 是明文：两列都必须是掩码入审计
        try (var mockedStatic = mockStatic(TransactionSynchronizationManager.class)) {
            SysConfig existing = new SysConfig();
            existing.setConfigKey("smtp.password");
            existing.setConfigValue("ENC(old-cipher)");
            when(configMapper.selectByKey("smtp.password")).thenReturn(existing);
            when(jasyptEncryptor.encrypt("new-plain")).thenReturn("ENC(new-cipher)");

            // when
            configService.update("smtp.password", "new-plain", 1L);

            // then
            verify(auditMapper).insert(eq("smtp.password"), eq("ENC:****"), eq("ENC:****"), eq(1L));
        }
    }
}
