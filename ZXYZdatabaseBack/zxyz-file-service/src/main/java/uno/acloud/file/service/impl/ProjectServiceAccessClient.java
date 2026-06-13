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

import java.util.Map;

/**
 * 调用 project-service 的项目访问权限校验 HTTP 客户端。
 *
 * <p>错误处理契约：checkAccess 抛出异常（权限校验失败 = 拒绝访问）。</p>
 */
@Slf4j
@Component
public class ProjectServiceAccessClient extends AbstractServiceClient {

    public ProjectServiceAccessClient(
            RestClient restClient,
            ServiceProperties serviceProperties,
            ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getProjectService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "项目服务";
    }

    public void checkAccess(Long projectId, Long userId) {
        try {
            postJson("/api/internal/projects/{projectId}/access-check",
                    Map.of("userId", userId), projectId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) {
                throw new BusinessException(ErrorCode.NO_PERMISSION, "无权访问该项目空间文件");
            }
            log.warn("项目权限校验失败: {}", e.getStatusCode());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "权限校验服务暂不可用");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("项目权限校验异常", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "权限校验服务暂不可用");
        }
    }
}
