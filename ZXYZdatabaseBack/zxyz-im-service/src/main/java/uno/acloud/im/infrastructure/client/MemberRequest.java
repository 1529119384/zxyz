package uno.acloud.im.infrastructure.client;

public class MemberRequest {

    private Long teamId;
    private Long userId;

    public MemberRequest(Long teamId, Long userId) {
        this.teamId = teamId;
        this.userId = userId;
    }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
