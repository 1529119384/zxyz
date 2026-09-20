package uno.acloud.project.common;

/**
 * 项目状态。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-project-service/.../V1__init_schema.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常，1-归档，2-禁用'}。</p>
 */
public final class ProjectStatus {
    /** 正常。 */
    public static final int NORMAL = 0;
    /** 已归档。 */
    public static final int ARCHIVED = 1;
    /** 已禁用。 */
    public static final int DISABLED = 2;

    private ProjectStatus() {
    }
}
