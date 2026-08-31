package uno.acloud.satoken;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程 StpInterface 基类，通过 REST 调用 team-service 获取用户权限和角色。
 * <p>
 * 各微服务（user-service、im-service、file-service、share-service）继承此类，
 * 仅需注入配置值即可，无需重复实现调用逻辑。
 */
@Slf4j
public class RemoteStpInterfaceImpl implements StpInterface {

    private final RestClient restClient;
    private final String teamServiceBaseUrl;
    private final ObjectMapper objectMapper;
    private final String internalServiceToken;
    private final String sourceService;
    private final String selfServiceKey;
    private final PermissionCache permissionCache;

    public RemoteStpInterfaceImpl(RestClient restClient,
                                  String teamServiceBaseUrl,
                                  ObjectMapper objectMapper,
                                  String internalServiceToken,
                                  String sourceService,
                                  String selfServiceKey,
                                  PermissionCache permissionCache) {
        this.restClient = restClient;
        this.teamServiceBaseUrl = teamServiceBaseUrl;
        this.objectMapper = objectMapper;
        this.internalServiceToken = internalServiceToken;
        this.sourceService = sourceService;
        this.selfServiceKey = selfServiceKey;
        this.permissionCache = permissionCache;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<String> cached = permissionCache.getPermissions(loginId, loginType);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> permissions = fetchCodes(
                    "/api/internal/permissions/user/system-permissions/{userId}", loginId, "权限");
            permissionCache.putPermissions(loginId, loginType, permissions);
            return permissions;
        } catch (BusinessException e) {
            // team-service 不可达或返回异常：若缓存有旧值则降级返回，避免全站鉴权失败
            List<String> stale = permissionCache.getPermissions(loginId, loginType);
            if (stale != null) {
                log.warn("team-service 不可用，使用缓存权限通过鉴权: loginId={}, loginType={}", loginId, loginType);
                return stale;
            }
            throw e;
        }
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        List<String> cached = permissionCache.getRoles(loginId, loginType);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> roles = fetchCodes(
                    "/api/internal/permissions/user/system-roles/{userId}", loginId, "角色");
            permissionCache.putRoles(loginId, loginType, roles);
            return roles;
        } catch (BusinessException e) {
            List<String> stale = permissionCache.getRoles(loginId, loginType);
            if (stale != null) {
                log.warn("team-service 不可用，使用缓存角色通过鉴权: loginId={}, loginType={}", loginId, loginType);
                return stale;
            }
            throw e;
        }
    }

    private List<String> fetchCodes(String path, Object loginId, String label) {
        try {
            String token = (selfServiceKey != null && !selfServiceKey.isBlank()) ? selfServiceKey : internalServiceToken;
            String responseBody = restClient.get()
                    .uri(teamServiceBaseUrl + path, loginId)
                    .header(InternalServiceHeaders.TOKEN_HEADER, token)
                    .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                throw new BusinessException(
                        root.path("code").asInt(ErrorCode.SYSTEM_ERROR),
                        root.path("msg").asText(label + "服务返回异常")
                );
            }
            List<String> codes = new ArrayList<>();
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    codes.add(node.asText());
                }
            }
            return codes;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用团队服务获取用户{}失败", label, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, label + "服务暂不可用");
        }
    }

}
