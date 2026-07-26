package uno.acloud.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.admin.service.ConfigService;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConfigService 集成测试 — 验证 Jasypt 解密流程和 Redis Pub/Sub 通知。
 * <p>
 * 继承 {@link AbstractIntegrationTest}，获得 MySQL 8.4 + Redis 7 Testcontainers 环境。
 * 外部依赖（邮件/存储客户端、RabbitMQ、Redis、Jasypt）均通过 {@link MockitoBean} mock。
 * </p>
 */
@Transactional
class ConfigServiceIntegrationTest extends AbstractIntegrationTest {

    static {
        DB_NAME = "zxyz_config";
    }

    // ---- Mock 外部依赖 ----

    @MockitoBean
    private EmailProviderClient emailProviderClient;

    @MockitoBean
    private StorageProviderClient storageProviderClient;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private JasyptEncryptor jasyptEncryptor;

    // ---- 注入被测对象 ----

    @Autowired
    private ConfigService configService;

    @Autowired
    private SysConfigMapper sysConfigMapper;

    // ---- 测试用例 ----

    /**
     * 验证完整写入→读取→Jasypt 解密流程。
     * <p>
     * 通过 mapper 直接插入一条 ENC 格式的配置值，
     * mock Jasypt decrypt 返回明文字符串，
     * 再通过 ConfigService.get() 读取并验证解密结果。
     * </p>
     */
    @Test
    void get_roundTrip_withJasyptDecrypt() {
        // 1. 直接插入测试数据（ConfigService 无 insert 方法，使用 mapper）
        SysConfig config = new SysConfig();
        config.setConfigKey("test.key.roundtrip.1");
        config.setConfigValue("ENC(abc)");
        sysConfigMapper.insert(config);

        // 2. Mock Jasypt 解密行为：ENC(abc) -> decrypted
        when(jasyptEncryptor.decrypt("ENC(abc)")).thenReturn("decrypted");

        // 3. 调用 ConfigService.get() — 首次调用命中 DB，经 Jasypt 解密后缓存
        String value = configService.get("test.key.roundtrip.1");

        // 4. 验证解密后的值
        assertEquals("decrypted", value);

        // 5. 验证 JasyptEncryptor.decrypt 被以密文参数调用
        verify(jasyptEncryptor).decrypt("ENC(abc)");
    }

    /**
     * 验证 ConfigService.update() 在事务提交后触发 Redis Pub/Sub 通知。
     * <p>
     * 插入一条配置数据后调用 update，
     * 验证 StringRedisTemplate.convertAndSend 被以正确的 channel 和 key 调用。
     * </p>
     */
    @Test
    void update_triggersRedisNotificationAfterCommit() {
        // 1. 插入测试数据，使 updateValue 有行可更新
        SysConfig config = new SysConfig();
        config.setConfigKey("test.key.notify.2");
        config.setConfigValue("value");
        sysConfigMapper.insert(config);

        // 2. Mock Jasypt：非 ENC 格式原样返回（模拟真实 decrypt 行为）
        when(jasyptEncryptor.decrypt(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 3. 调用 update — @Transactional 方法，afterCommit 回调在事务提交后触发
        configService.update("test.key.notify.2", "value", 2L);

        // 4. 验证 Redis Pub/Sub 通知已发送
        verify(stringRedisTemplate).convertAndSend("zxyz:config:changed", "test.key.notify.2");
    }
}
