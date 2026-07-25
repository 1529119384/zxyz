package uno.acloud.admin.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_config"; }

    @MockitoBean
    private EmailProviderClient emailProviderClient;

    @MockitoBean
    private StorageProviderClient storageProviderClient;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private JasyptEncryptor jasyptEncryptor;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SysConfigMapper configMapper;

    @Test
    void insertAndSelectByKey_roundTrip() {
        SysConfig config = new SysConfig();
        config.setConfigKey("test.key");
        config.setConfigValue("test-value");
        config.setConfigType("SYSTEM");

        configMapper.insert(config);

        SysConfig found = configMapper.selectByKey("test.key");
        assertNotNull(found);
        assertEquals("test-value", found.getConfigValue());
    }

    @Test
    void updateValue_modifiesExistingKey() {
        SysConfig config = new SysConfig();
        config.setConfigKey("update.key");
        config.setConfigValue("original");
        config.setConfigType("SYSTEM");

        configMapper.insert(config);

        configMapper.updateValue("update.key", "modified");

        SysConfig found = configMapper.selectByKey("update.key");
        assertNotNull(found);
        assertEquals("modified", found.getConfigValue());
    }
}
