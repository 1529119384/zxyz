package uno.acloud.share.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ShareFileServiceClientTest {

    private ShareFileServiceClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        ShareServiceProperties shareProps = new ShareServiceProperties();
        shareProps.getFileService().setBaseUrl("http://zxyz-file-service");

        TeamServiceProperties teamProps = new TeamServiceProperties();
        teamProps.setBaseUrl("http://zxyz-team-service");
        teamProps.setInternalServiceToken("test-token");

        client = new ShareFileServiceClient(
                RestClient.builder().build(),
                shareProps,
                teamProps,
                objectMapper
        );
    }

    // ==================== mapToProjection ====================

    @Test
    void mapToProjection_shouldMapAllFields() throws Exception {
        Method method = getPrivateMethod("mapToProjection", JsonNode.class);
        JsonNode data = objectMapper.readTree("""
                {"id":1,"fileType":1,"uuidName":"abc","originalName":"test.txt","category":0,"fileSize":1024,"storePath":"/path","deleted":0,"modifyTime":"2026-04-27T13:45:12"}
                """);
        ShareFileProjection result = (ShareFileProjection) method.invoke(client, data);

        assertEquals(1L, result.getId());
        assertEquals(1, result.getFileType());
        assertEquals("abc", result.getUuidName());
        assertEquals("test.txt", result.getOriginalName());
        assertEquals(0, result.getCategory());
        assertEquals(1024L, result.getFileSize());
        assertEquals("/path", result.getStorePath());
        assertEquals(0, result.getDeleted());
        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result.getModifyTime());
    }

    @Test
    void mapToProjection_shouldHandleMissingFields() throws Exception {
        Method method = getPrivateMethod("mapToProjection", JsonNode.class);
        JsonNode data = objectMapper.readTree("{\"id\":1,\"originalName\":\"test.txt\"}");
        ShareFileProjection result = (ShareFileProjection) method.invoke(client, data);

        assertEquals(1L, result.getId());
        assertEquals("test.txt", result.getOriginalName());
        // 缺失字段的 null-safe 行为：
        // - asText(null) 对缺失节点返回 null → uuidName/storePath/modifyTime 为 null
        // - asInt() 对缺失节点返回 0 → category 为 0
        // - asLong() 对缺失节点返回 0 → fileSize 为 0L
        // fileType 和 deleted 已改为 has 判断，缺失时为 null
        assertNull(result.getUuidName());
        assertNull(result.getStorePath());
        assertNull(result.getModifyTime());
        assertNull(result.getFileType());
        assertNull(result.getDeleted());
        assertEquals(0, result.getCategory());
        assertEquals(0L, result.getFileSize());
    }

    @Test
    void mapToProjection_fileType1_shouldBeFile() throws Exception {
        Method method = getPrivateMethod("mapToProjection", JsonNode.class);
        JsonNode data = objectMapper.readTree("{\"id\":1,\"fileType\":1}");
        ShareFileProjection result = (ShareFileProjection) method.invoke(client, data);

        assertTrue(result.isFile());
        assertFalse(result.isFolder());
    }

    @Test
    void mapToProjection_fileType0_shouldBeFolder() throws Exception {
        Method method = getPrivateMethod("mapToProjection", JsonNode.class);
        JsonNode data = objectMapper.readTree("{\"id\":1,\"fileType\":0}");
        ShareFileProjection result = (ShareFileProjection) method.invoke(client, data);

        assertFalse(result.isFile());
        assertTrue(result.isFolder());
    }

    // ==================== parseLocalDateTime ====================

    @Test
    void parseLocalDateTime_shouldParseIsoFormat() throws Exception {
        Method method = getPrivateMethod("parseLocalDateTime", JsonNode.class);
        JsonNode node = objectMapper.readTree("\"2026-04-27T13:45:12\"");
        LocalDateTime result = (LocalDateTime) method.invoke(client, node);

        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result);
    }

    @Test
    void parseLocalDateTime_shouldReturnNullForBlank() throws Exception {
        Method method = getPrivateMethod("parseLocalDateTime", JsonNode.class);

        // null 节点 → null
        JsonNode nullNode = objectMapper.readTree("null");
        assertNull(method.invoke(client, nullNode));

        // 缺失节点 → null
        JsonNode missingNode = objectMapper.getNodeFactory().missingNode();
        assertNull(method.invoke(client, missingNode));
    }

    // ==================== enforceSuccessCode ====================

    @Test
    void enforceSuccessCode_shouldPassWhenCodeIsSuccess() throws Exception {
        Method method = getPrivateMethod("enforceSuccessCode", JsonNode.class, String.class);
        JsonNode successNode = objectMapper.readTree("{\"code\":1}");
        // 不应抛出异常
        method.invoke(client, successNode, "fallback");
    }

    @Test
    void enforceSuccessCode_shouldThrowWhenCodeNotSuccess() throws Exception {
        Method method = getPrivateMethod("enforceSuccessCode", JsonNode.class, String.class);
        JsonNode errorNode = objectMapper.readTree("{\"code\":2,\"msg\":\"存储不可用\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeUnwrapped(method, client, errorNode, "fallback"));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("存储不可用", ex.getMessage());
    }

    @Test
    void enforceSuccessCode_shouldThrowWithFallbackWhenRootIsNull() throws Exception {
        Method method = getPrivateMethod("enforceSuccessCode", JsonNode.class, String.class);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeUnwrapped(method, client, null, "fallback-msg"));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("fallback-msg", ex.getMessage());
    }

    @Test
    void enforceSuccessCode_shouldUseFallbackWhenMsgMissing() throws Exception {
        Method method = getPrivateMethod("enforceSuccessCode", JsonNode.class, String.class);
        JsonNode errorNode = objectMapper.readTree("{\"code\":2}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeUnwrapped(method, client, errorNode, "默认消息"));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("默认消息", ex.getMessage());
    }

    // ==================== Helpers ====================

    /**
     * 通过反射调用私有方法，自动解包 InvocationTargetException。
     * Method.invoke 会将目标异常包装为 InvocationTargetException，
     * 这里提取原始异常以便 assertThrows 正确匹配。
     */
    private static Object invokeUnwrapped(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method getPrivateMethod(String name, Class<?>... paramTypes) throws Exception {
        Method m = ShareFileServiceClient.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return m;
    }
}
