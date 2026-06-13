package uno.acloud.common;

public final class SystemPermissionCodes {

    public static final String SYSTEM_ACCESS = "system:access";
    public static final String FILE_UPLOAD = "file:upload";
    public static final String FILE_READ = "file:read";
    public static final String FILE_WRITE = "file:write";
    public static final String FILE_DELETE = "file:delete";
    public static final String FOLDER_CREATE = "folder:create";
    public static final String TRASH_READ = "trash:read";
    public static final String SHARE_CREATE = "share:create";
    public static final String SHARE_READ = "share:read";
    public static final String SHARE_MANAGE = "share:manage";
    public static final String IM_FILE_CARD = "im:file-card";
    public static final String SYSTEM_ROLE_MANAGE = "system:role:manage";
    public static final String SYSTEM_PERMISSION_READ = "system:permission:read";
    public static final String SYSTEM_AUDIT_READ = "system:audit:read";
    public static final String TEAM_CREATE = "team:create";
    public static final String IM_MESSAGE_READ = "im:message:read";
    public static final String IM_MESSAGE_SEND = "im:message:send";
    public static final String IM_CONVERSATION_READ = "im:conversation:read";
    public static final String IM_CONVERSATION_CREATE = "im:conversation:create";
    public static final String IM_TEAM_INVITE = "im:team:invite";
    public static final String IM_TEAM_ANNOUNCEMENT = "im:team:announcement";
    public static final String IM_TEAM_MUTE = "im:team:mute";
    public static final String IM_TEAM_INVITE_LINK = "im:team:invite-link";
    public static final String IM_TEAM_JOIN_REQUEST = "im:team:join-request";

    /**
     * 返回所有权限码常量的显式列表，避免使用反射获取。
     */
    public static java.util.List<String> allCodes() {
        return java.util.List.of(
                SYSTEM_ACCESS,
                FILE_UPLOAD,
                FILE_READ,
                FILE_WRITE,
                FILE_DELETE,
                FOLDER_CREATE,
                TRASH_READ,
                SHARE_CREATE,
                SHARE_READ,
                SHARE_MANAGE,
                IM_FILE_CARD,
                SYSTEM_ROLE_MANAGE,
                SYSTEM_PERMISSION_READ,
                SYSTEM_AUDIT_READ,
                TEAM_CREATE,
                IM_MESSAGE_READ,
                IM_MESSAGE_SEND,
                IM_CONVERSATION_READ,
                IM_CONVERSATION_CREATE,
                IM_TEAM_INVITE,
                IM_TEAM_ANNOUNCEMENT,
                IM_TEAM_MUTE,
                IM_TEAM_INVITE_LINK,
                IM_TEAM_JOIN_REQUEST
        );
    }

    private SystemPermissionCodes() {
    }
}
