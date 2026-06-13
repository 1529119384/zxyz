package uno.acloud.common;

/**
 * RabbitMQ 交换机名称与路由键常量集中定义。
 * 各服务模块统一引用此处，避免硬编码字符串重复。
 */
public final class RabbitMqConstants {

    /** Topic Exchange 名称 */
    public static final String EXCHANGE = "zxyz.topic";

    /** Dead Letter Exchange 名称 */
    public static final String DLX_EXCHANGE = "zxyz.dlx";

    // ---- Team routing keys ----
    public static final String ROUTING_KEY_TEAM_CREATED = "team.created";
    public static final String ROUTING_KEY_TEAM_UPDATED = "team.updated";
    public static final String ROUTING_KEY_TEAM_MEMBER_ADDED = "team.member.added";
    public static final String ROUTING_KEY_TEAM_MEMBER_REMOVED = "team.member.removed";

    // ---- Project routing keys ----
    public static final String ROUTING_KEY_PROJECT_MEMBER_ADDED = "project.member.added";
    public static final String ROUTING_KEY_PROJECT_MEMBER_REMOVED = "project.member.removed";

    // ---- File routing keys ----
    public static final String ROUTING_KEY_FILE_RESOURCE_CHANGED = "file.resource.changed";

    // ---- User routing keys ----
    public static final String ROUTING_KEY_USER_PROFILE_UPDATED = "user.profile.updated";

    // ---- Audit routing keys ----
    public static final String ROUTING_KEY_AUDIT_LOG = "audit.log";

    private RabbitMqConstants() {
    }
}
