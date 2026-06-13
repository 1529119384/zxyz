package uno.acloud.team.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import uno.acloud.client.FileStorageClient;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.team.config.ServiceProperties;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调用 file-service InternalStorageController 的 HTTP 客户端。
 * 用于获取存储用量信息。继承公共基类，覆盖为静默降级版本。
 */
@Slf4j
@Component
public class FileServiceClient extends FileStorageClient {

    public FileServiceClient(RestClient restClient,
                             ServiceProperties serviceProperties,
                             ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getFileService().normalizedBaseUrl(), serviceProperties.getInternalServiceToken(), objectMapper);
    }

    /**
     * 查询活跃文件总大小（静默降级版本，失败返回 0）。
     */
    @Override
    public long sumActiveFileSize(Long userId, Long teamId, Integer spaceType, Long projectId) {
        try {
            return super.sumActiveFileSize(userId, teamId, spaceType, projectId);
        } catch (Exception e) {
            log.warn("查询活跃文件总大小失败", e);
            return 0;
        }
    }

    /**
     * 批量查询个人存储用量（静默降级版本，失败返回空列表）。
     */
    @Override
    public List<PersonalStorageUsage> listPersonalStorageUsageByUsers(List<Long> userIds) {
        try {
            return super.listPersonalStorageUsageByUsers(userIds);
        } catch (Exception e) {
            log.warn("批量查询个人存储用量失败", e);
            return List.of();
        }
    }

    /**
     * 批量查询个人存储用量，返回 userId → usedStorage 的 Map。
     * 供团队成员存储用量列表等场景使用。
     */
    public Map<Long, Long> listPersonalStorageUsageAsMap(List<Long> userIds) {
        try {
            List<PersonalStorageUsage> list = super.listPersonalStorageUsageByUsers(userIds);
            return list.stream().collect(Collectors.toMap(
                    PersonalStorageUsage::getUserId,
                    PersonalStorageUsage::getUsedStorage));
        } catch (Exception e) {
            log.warn("批量查询个人存储用量失败", e);
            return Map.of();
        }
    }

    /**
     * 批量查询团队存储用量（静默降级版本，失败返回空 Map）。
     */
    @Override
    public Map<Long, Long> listTeamStorageUsageByTeamIds(List<Long> teamIds) {
        try {
            return super.listTeamStorageUsageByTeamIds(teamIds);
        } catch (Exception e) {
            log.warn("批量查询团队存储用量失败", e);
            return Map.of();
        }
    }
}
