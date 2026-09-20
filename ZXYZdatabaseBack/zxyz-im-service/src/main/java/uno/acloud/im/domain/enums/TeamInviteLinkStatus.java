package uno.acloud.im.domain.enums;

/**
 * 团队邀请链接状态（表 {@code team_invite_link}，注意与 {@code team_invitation.status} 是两张不同的表）。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-im-service/.../V1__init_schema.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-失效'}。</p>
 */
public final class TeamInviteLinkStatus {
    /** 正常。 */
    public static final int ACTIVE = 0;
    /** 已失效。 */
    public static final int EXPIRED = 1;

    private TeamInviteLinkStatus() {
    }
}
