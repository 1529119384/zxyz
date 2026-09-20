package uno.acloud.common;

/**
 * 团队状态。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-team-service/.../V2__team_owner_transfer_and_dissolve.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-禁用，2-已解散(所有者注销且无继任者)'}。</p>
 *
 * <p>本类原先在 {@code zxyz-im-service} 的 {@code im.domain.enums} 包下（只有 ACTIVE/DISABLED，
 * 漏了 DDL 里已定义的第 3 个取值），因 {@code zxyz-team-service} 是独立 Maven 模块、无法依赖 im，
 * 导致 team-service 只能写裸 {@code 0}。故上移到 {@code zxyz-common}（所有服务都依赖），
 * 并补齐 {@link #DISSOLVED}。取值与迁移前完全一致，不影响任何存量数据。</p>
 */
public final class TeamStatus {
    /** 正常。 */
    public static final int ACTIVE = 0;
    /** 禁用。 */
    public static final int DISABLED = 1;
    /** 已解散：所有者注销且无继任者（V2 迁移引入）。 */
    public static final int DISSOLVED = 2;

    private TeamStatus() {
    }
}
