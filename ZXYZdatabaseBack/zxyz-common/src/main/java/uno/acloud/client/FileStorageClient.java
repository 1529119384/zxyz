package uno.acloud.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件服务存储查询客户端（基础版）。
 * <p>供 project-service、team-service 等需要查询文件存储用量的模块复用。
 * 子类应添加 {@code @Component}，本类不注册为 Spring Bean。</p>
 *
 * <p>构造参数由子类从各自的 {@code ServiceProperties} 注入后传入，
 * 基类不使用 {@code @Value}，避免与子类配置来源冲突。</p>
 */
@Slf4j
public class FileStorageClient extends AbstractServiceClient {

    public FileStorageClient(RestClient restClient,
                             String fileServiceBaseUrl,
                             String internalServiceToken,
                             ObjectMapper objectMapper) {
        super(restClient, fileServiceBaseUrl, internalServiceToken, objectMapper);
    }

    @Override
    protected String serviceName() {
        return "文件服务";
    }

    /**
     * 查询活跃文件总大小。
     *
     * @param userId    用户 ID（可为 null，缺省 0）
     * @param teamId    团队 ID（可为 null，缺省 0）
     * @param spaceType 空间类型（可为 null，缺省 0）
     * @param projectId 项目 ID（可为 null，缺省 0）
     * @return 活跃文件总字节数
     */
    public long sumActiveFileSize(Long userId, Long teamId, Integer spaceType, Long projectId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId != null ? userId : 0);
        payload.put("teamId", teamId != null ? teamId : 0);
        payload.put("spaceType", spaceType != null ? spaceType : 0);
        payload.put("projectId", projectId != null ? projectId : 0);

        JsonNode root = postJson("/api/internal/storage/sum-active", payload);
        return root.path("data").asLong(0);
    }

    /**
     * 批量查询个人存储用量。
     *
     * @param userIds 用户 ID 列表
     * @return 各用户存储用量列表
     */
    public List<PersonalStorageUsage> listPersonalStorageUsageByUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        JsonNode root = postJson("/api/internal/storage/personal-usage-list",
                Map.of("userIds", userIds));
        return objectMapper().convertValue(
                root.path("data"),
                new TypeReference<List<PersonalStorageUsage>>() {}
        );
    }

    /**
     * 批量查询团队存储用量，返回 teamId → usedStorage 的 Map。
     *
     * @param teamIds 团队 ID 列表
     * @return teamId → usedStorage 映射；调用失败时返回空 Map
     */
    public Map<Long, Long> listTeamStorageUsageByTeamIds(List<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = postJson("/api/internal/storage/team-usage-list",
                    Map.of("teamIds", teamIds));
            List<TeamStorageUsage> list = objectMapper().convertValue(
                    root.path("data"),
                    new TypeReference<List<TeamStorageUsage>>() {}
            );
            return list.stream().collect(Collectors.toMap(
                    TeamStorageUsage::getTeamId,
                    TeamStorageUsage::getUsedStorage));
        } catch (Exception e) {
            log.warn("批量查询团队存储用量失败", e);
            return Collections.emptyMap();
        }
    }
}
