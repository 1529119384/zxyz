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

            // then — 验证 DB 操作
            verify(configMapper).updateValue("app.name", "new-value");
            verify(auditMapper).insert(eq("app.name"), isNull(), eq("new-value"), eq(1L));

            // then — 手动触发 afterCommit，验证 Redis Pub/Sub 通知
            ArgumentCaptor<TransactionSynchronization> syncCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
            mockedStatic.verify(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            syncCaptor.getValue().afterCommit();
            verify(stringRedisTemplate).convertAndSend("zxyz:config:changed", "app.name");
        }
    }
}
