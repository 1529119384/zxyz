package uno.acloud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.UserInfoDTO;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户服务查询客户端（基础版）。
 * <p>供 project-service、team-service 等需要查询用户信息的模块复用。
 * 子类应添加 {@code @Component}，本类不注册为 Spring Bean。</p>
 *
 * <p>构造参数由子类从各自的 {@code ServiceProperties} 注入后传入，
 * 基类不使用 {@code @Value}，避免与子类配置来源冲突。</p>
 */
@Slf4j
public class UserQueryClient extends AbstractServiceClient {

    public UserQueryClient(RestClient restClient,
                           String userServiceBaseUrl,
                           String internalServiceToken,
                           ObjectMapper objectMapper) {
        super(restClient, userServiceBaseUrl, internalServiceToken, objectMapper);
    }

    @Override
    protected String serviceName() {
        return "用户服务";
    }

    /**
     * 批量查询用户信息。
     *
     * @param userIds 用户 ID 列表
     * @return 用户信息列表；调用失败时返回空列表
     */
    public List<UserInfoDTO> listByIds(List<Long> userIds) {
        try {
            JsonNode root = postJson("/api/internal/users/batch", Map.of("userIds", userIds));
            if (root.path("code").asInt(0) != ErrorCode.SUCCESS) {
                log.warn("批量查询用户信息返回非成功码: code={}", root.path("code").asInt());
                return Collections.emptyList();
            }
            return objectMapper().convertValue(
                    root.path("data"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<UserInfoDTO>>() {}
            );
        } catch (Exception e) {
            log.warn("批量查询用户信息失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 根据 ID 查询单个用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息；调用失败或用户不存在时返回 null
     */
    @Nullable
    public UserInfoDTO getUserById(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/users/" + userId);
            if (root.path("code").asInt(0) != ErrorCode.SUCCESS) {
                log.warn("查询用户信息返回非成功码: userId={}, code={}", userId, root.path("code").asInt());
                return null;
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }
            return objectMapper().convertValue(data, UserInfoDTO.class);
        } catch (Exception e) {
            log.warn("查询用户信息失败: userId={}", userId, e);
            return null;
        }
    }
}
