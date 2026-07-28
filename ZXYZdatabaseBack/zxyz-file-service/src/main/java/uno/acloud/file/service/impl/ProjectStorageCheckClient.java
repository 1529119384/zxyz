package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.config.ServiceProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用 project-service 的存储配额校验 HTTP 客户端。
 * <p>继承 {@link AbstractServiceClient}，获得 Resilience4j 重试+熔断保护。</p>
 *
 * <p>错误处理契约：配额不足(409) → BAD_REQUEST；其他异常 → SYSTEM_ERROR。</p>
 */
@Slf4j
@Component
public class ProjectStorageCheckClient extends AbstractServiceClient {

    public ProjectStorageCheckClient(RestClient restClient,
                                     ServiceProperties serviceProperties,
                                     ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getProjectService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "项目服务(配额校验)";
    }

    /**
     * 校验目标空间的存储配额是否足够。
     *
     * @param userId     用户 ID
     * @param teamId     团队 ID（个人空间为 null）
     * @param spaceType  空间类型
     * @param projectId  项目 ID（非项目空间为 null）
     * @param totalSize  本次操作所需总字节数
     * @throws BusinessException 配额不足或服务不可用时抛出
     */
    public void checkQuota(Long userId, Long teamId, Integer spaceType, Long projectId, long totalSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("teamId", teamId);
        body.put("spaceType", spaceType);
        body.put("projectId", projectId);
        body.put("totalSize", totalSize);
        try {
            postJson("/api/internal/storage/check-quota", body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足");
            }
            log.error("调用存储配额校验失败(status={}): {}", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务异常，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用存储配额校验失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务不可用，请稍后重试");
        }
    }
}
