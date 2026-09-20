package uno.acloud.email.domain;

/**
 * 邮件模板状态（表 {@code email_template}）。
 *
 * <p>取值口径来自 Flyway 迁移 {@code zxyz-email-service/.../V1__init_schema.sql}：
 * {@code status TINYINT NOT NULL DEFAULT 0 COMMENT '0-启用，1-停用'}。</p>
 *
 * <p>注意与 {@link EmailRecordStatus} 区分：那个是 {@code email_record.status}，
 * 用的是 {@code PENDING/SENDING/SENT/FAILED} 字符串，不是本表。</p>
 */
public final class EmailTemplateStatus {
    /** 启用。 */
    public static final int ENABLED = 0;
    /** 停用。 */
    public static final int DISABLED = 1;

    private EmailTemplateStatus() {
    }
}
