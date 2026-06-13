package uno.acloud.im.infrastructure.client;

public class RoleGrantRequest {

    private Long teamId;
    private Long userId;
    private String roleCode;

    public RoleGrantRequest(Long teamId, Long userId, String roleCode) {
        this.teamId = teamId;
        this.userId = userId;
        this.roleCode = roleCode;
    }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
}
