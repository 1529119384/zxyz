package uno.acloud.common.permission;

public interface TeamPermissionPort {
    void check(long userId, long teamId, String permissionCode);

    boolean hasPermission(Long userId, Long teamId, String permissionCode);
}
