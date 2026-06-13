package uno.acloud.project.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.FileStorageClient;
import uno.acloud.project.config.ServiceProperties;

import java.util.List;
import java.util.Map;

/**
 * project-service 专用的文件服务存储查询客户端。
 * 继承公共基础类 {@link FileStorageClient}，额外提供项目特有的存储聚合方法。
 */
@Slf4j
@Component
public class FileServiceClient extends FileStorageClient {

    public FileServiceClient(RestClient restClient,
                             ServiceProperties serviceProperties,
                             ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getFileService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    /**
     * 汇总指定用户列表的个人存储用量总和（项目空间维度）。
     */
    public long sumPersonalStorageByUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        JsonNode root = postJson("/api/internal/storage/sum-personal", Map.of("userIds", userIds));
        return root.path("data").asLong(0);
    }
}
