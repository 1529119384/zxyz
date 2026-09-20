package uno.acloud.project.common;

/**
 * 项目创建申请状态。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-project-service/.../V1__init_schema.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待审，1-通过，2-拒绝'}。</p>
 */
public final class ProjectCreateRequestStatus {
    /** 待审。 */
    public static final int PENDING = 0;
    /** 已通过。 */
    public static final int APPROVED = 1;
    /** 已拒绝。 */
    public static final int REJECTED = 2;

    private ProjectCreateRequestStatus() {
    }
}
