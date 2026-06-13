package uno.acloud.im.domain.event;

public final class ImDomainEventType {

    public static final String TEAM_INVITATION_CREATED = "TEAM_INVITATION_CREATED";
    public static final String TEAM_JOIN_REQUEST_SUBMITTED = "TEAM_JOIN_REQUEST_SUBMITTED";
    public static final String TEAM_JOIN_REQUEST_APPROVED = "TEAM_JOIN_REQUEST_APPROVED";
    public static final String TEAM_JOIN_REQUEST_REJECTED = "TEAM_JOIN_REQUEST_REJECTED";
    public static final String TEAM_ANNOUNCEMENT_PUBLISHED = "TEAM_ANNOUNCEMENT_PUBLISHED";
    public static final String TEAM_MENTION_CREATED = "TEAM_MENTION_CREATED";
    public static final String TEAM_MEMBER_LEFT = "TEAM_MEMBER_LEFT";
    public static final String TEAM_MEMBER_REMOVED = "TEAM_MEMBER_REMOVED";

    private ImDomainEventType() {
    }
}
