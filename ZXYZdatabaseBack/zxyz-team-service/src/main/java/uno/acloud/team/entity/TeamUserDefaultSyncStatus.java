package uno.acloud.team.entity;

/**
 * team_user_default_sync 表状态常量（与 email_record 的 EmailRecordStatus 风格一致）。
 */
public final class TeamUserDefaultSyncStatus {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private TeamUserDefaultSyncStatus() {
    }
}
