package uno.acloud.team.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.config.ServiceProperties;

/**
 * 调用 project-service InternalProjectController 的 HTTP 客户端。
 *
 * <p>错误处理契约：查询类方法在调用失败时抛出 BusinessException，阻止不安全的操作继续执行。</p>
 */
@Slf4j
@Component
public class ProjectServiceClient extends AbstractServiceClient {

    public ProjectServiceClient(RestClient restClient,
                                ServiceProperties serviceProperties,
                                ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getProjectService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "项目服务";
    }

    /**
     * 查询用户作为负责人(project leader)的活跃项目数量。
     */
    public int countActiveProjectsLedBy(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/projects/{userId}/active-projects-led-count", userId);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                log.warn("查询用户活跃项目负责人数量返回错误码: userId={}, code={}", userId, root.path("code").asInt());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法验证项目负责人状态，请稍后重试");
            }
            return root.path("data").asInt(0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("查询用户活跃项目负责人数量失败: userId={}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法验证项目负责人状态，请稍后重试");
        }
    }
}
