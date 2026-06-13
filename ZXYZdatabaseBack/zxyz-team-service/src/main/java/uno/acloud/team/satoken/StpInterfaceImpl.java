package uno.acloud.team.satoken;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.team.service.PermissionPort;

import java.util.List;

@Component
public class StpInterfaceImpl implements StpInterface {

    private final PermissionPort permissionService;

    public StpInterfaceImpl(PermissionPort permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = parseUserId(loginId);
        if (userId == null) {
            return List.of();
        }
        return permissionService.getSystemPermissionsByUserId(userId);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = parseUserId(loginId);
        if (userId == null) {
            return List.of();
        }
        return permissionService.getSystemRolesByUserId(userId);
    }

    @Nullable
    private Long parseUserId(Object loginId) {
        if (loginId instanceof Number userId) {
            return userId.longValue();
        }
        if (loginId instanceof String userIdText) {
            try {
                return Long.valueOf(userIdText);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
