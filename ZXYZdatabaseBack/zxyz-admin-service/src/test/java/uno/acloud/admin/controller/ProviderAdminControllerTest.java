package uno.acloud.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderAdminControllerTest {

    @Mock
    private StorageProviderClient storageProviderClient;

    @Mock
    private EmailProviderClient emailProviderClient;

    private ProviderAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ProviderAdminController(storageProviderClient, emailProviderClient);
    }

    // ==================== 存储提供者 ====================

    @Test
    void listStorageProviders_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("[{\"id\":\"local\"}]");
        when(storageProviderClient.listAll()).thenReturn(node);

        Result<JsonNode> result = controller.listStorageProviders();

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(storageProviderClient).listAll();
    }

    @Test
    void updateStorageProvider_delegatesToClient() throws Exception {
        Map<String, Object> request = Map.of("enabled", true);

        Result<Void> result = controller.updateStorageProvider("local", request);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(storageProviderClient).updateConfig(eq("local"), any());
    }

    @Test
    void storageProviderHealth_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"status\":\"UP\"}");
        when(storageProviderClient.healthCheck("local")).thenReturn(node);

        Result<JsonNode> result = controller.storageProviderHealth("local");

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(storageProviderClient).healthCheck("local");
    }

    // ==================== 邮件提供者 ====================

    @Test
    void listEmailProviders_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("[{\"id\":\"smtp\"}]");
        when(emailProviderClient.listAll()).thenReturn(node);

        Result<JsonNode> result = controller.listEmailProviders();

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(emailProviderClient).listAll();
    }

    @Test
    void updateEmailProvider_delegatesToClient() throws Exception {
        Map<String, Object> request = Map.of("enabled", true);

        Result<Void> result = controller.updateEmailProvider("smtp", request);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(emailProviderClient).updateConfig(eq("smtp"), any());
    }

    @Test
    void emailProviderHealth_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"status\":\"UP\"}");
        when(emailProviderClient.healthCheck("smtp")).thenReturn(node);

        Result<JsonNode> result = controller.emailProviderHealth("smtp");

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(emailProviderClient).healthCheck("smtp");
    }
}
