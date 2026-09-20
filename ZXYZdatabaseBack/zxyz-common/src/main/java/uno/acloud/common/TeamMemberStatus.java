package uno.acloud.common;

/**
 * 团队成员状态。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-team-service/.../V1__init_schema.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用，2-已移除'}。</p>
 *
 * <p>本类原先在 {@code zxyz-im-service} 的 {@code im.domain.enums} 包下，因 {@code zxyz-team-service}
 * 是独立 Maven 模块、无法依赖 im 而上移到 {@code zxyz-common}。取值逐字保留。</p>
 */
public final class TeamMemberStatus {
    /** 正常。 */
    public static final int ACTIVE = 0;
    /** 禁用。 */
    public static final int DISABLED = 1;
    /** 已移除。 */
    public static final int REMOVED = 2;

    private TeamMemberStatus() {
    }
}
