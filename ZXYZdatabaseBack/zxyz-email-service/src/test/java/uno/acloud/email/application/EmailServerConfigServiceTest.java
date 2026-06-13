package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServerConfigServiceTest {

    @Mock
    private EmailServerConfigMapper configMapper;
    @Mock
    private SmtpConnectivityTester smtpConnectivityTester;

    @Test
    void createConfigShouldEncryptPasswordAndHideItFromResponse() {
        EmailServerConfigService service = newService();
        when(configMapper.insert(any(EmailServerConfig.class))).thenAnswer(invocation -> {
            EmailServerConfig config = invocation.getArgument(0);
            config.setId(7L);
            return 1;
        });
        EmailServerConfigRequest request = new EmailServerConfigRequest();
        request.setConfigName("QQ 邮箱");
        request.setHost("smtp.qq.com");
        request.setPort(587);
        request.setUsername("user@qq.com");
        request.setPassword("auth-code");

        EmailServerConfigVO response = service.createConfig(request);

        ArgumentCaptor<EmailServerConfig> configCaptor = ArgumentCaptor.forClass(EmailServerConfig.class);
        verify(configMapper).insert(configCaptor.capture());
        EmailServerConfig saved = configCaptor.getValue();
        assertNotEquals("auth-code", saved.getPasswordCipher());
        assertTrue(response.getPasswordSet());
        assertEquals(false, response.getActive());
    }

    @Test
    void updateConfigShouldKeepOldPasswordWhenPasswordIsBlank() {
        EmailProperties properties = new EmailProperties();
        properties.setConfigSecret("unit-test-secret");
        EmailSecretCipher cipher = new EmailSecretCipher(properties);
        EmailServerConfigService service = new EmailServerConfigService(configMapper, cipher, smtpConnectivityTester, properties, null, Mappers.getMapper(EmailEntityMapper.class));
        EmailServerConfig existing = new EmailServerConfig();
        existing.setId(7L);
        existing.setConfigName("旧配置");
        existing.setHost("smtp.old.com");
        existing.setPort(587);
        existing.setUsername("old@example.com");
        existing.setPasswordCipher(cipher.encrypt("old-password"));
        existing.setActive(true);
        when(configMapper.update(any(EmailServerConfig.class))).thenReturn(1);
        EmailServerConfig updated = new EmailServerConfig();
        updated.setId(7L);
        updated.setConfigName("新配置");
        updated.setHost("smtp.new.com");
        updated.setPort(465);
        updated.setUsername("new@example.com");
        updated.setPasswordCipher(existing.getPasswordCipher());
        updated.setActive(true);
        when(configMapper.selectById(7L)).thenReturn(existing, updated);
        EmailServerConfigRequest request = new EmailServerConfigRequest();
        request.setConfigName("新配置");
        request.setHost("smtp.new.com");
        request.setPort(465);
        request.setUsername("new@example.com");
        request.setPassword(" ");

        service.updateConfig(7L, request);

        ArgumentCaptor<EmailServerConfig> configCaptor = ArgumentCaptor.forClass(EmailServerConfig.class);
        verify(configMapper).update(configCaptor.capture());
        assertEquals(existing.getPasswordCipher(), configCaptor.getValue().getPasswordCipher());
    }

    @Test
    void activateConfigShouldRejectWhenConnectivityTestFails() {
        EmailProperties properties = new EmailProperties();
        properties.setConfigSecret("unit-test-secret");
        EmailSecretCipher cipher = new EmailSecretCipher(properties);
        EmailServerConfigService service = new EmailServerConfigService(configMapper, cipher, smtpConnectivityTester, properties, null, Mappers.getMapper(EmailEntityMapper.class));
        EmailServerConfig existing = new EmailServerConfig();
        existing.setId(7L);
        existing.setConfigName("QQ 邮箱");
        existing.setHost("smtp.qq.com");
        existing.setPort(587);
        existing.setUsername("user@qq.com");
        existing.setPasswordCipher(cipher.encrypt("old-password"));
        when(configMapper.selectById(7L)).thenReturn(existing);
        when(smtpConnectivityTester.test(existing, "old-password"))
                .thenReturn(new EmailConnectivityTestVO(7L, EmailTestStatus.FAILED, LocalDateTime.now(), "认证失败"));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.activateConfig(7L));

        assertEquals("SMTP 连接测试失败：认证失败", exception.getMessage());
        verify(configMapper).updateLastTest(any(), any(), any(), any());
    }

    private EmailServerConfigService newService() {
        EmailProperties properties = new EmailProperties();
        properties.setConfigSecret("unit-test-secret");
        return new EmailServerConfigService(configMapper, new EmailSecretCipher(properties), smtpConnectivityTester, properties, null, Mappers.getMapper(EmailEntityMapper.class));
    }
}
