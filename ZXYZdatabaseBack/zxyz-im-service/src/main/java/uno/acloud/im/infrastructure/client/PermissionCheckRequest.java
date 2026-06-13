package uno.acloud.im.infrastructure.client;

public class PermissionCheckRequest {

    private Long teamId;
    private Long userId;
    private String permissionCode;

    public PermissionCheckRequest(Long teamId, Long userId, String permissionCode) {
        this.teamId = teamId;
        this.userId = userId;
        this.permissionCode = permissionCode;
    }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
}
