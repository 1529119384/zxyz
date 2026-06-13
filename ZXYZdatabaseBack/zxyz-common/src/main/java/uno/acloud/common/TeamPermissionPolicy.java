package uno.acloud.common;

import java.util.List;
import java.util.Map;

public final class TeamPermissionPolicy {

    private static final List<PermissionDefinition> BUILT_IN_PERMISSIONS = List.of(
            new PermissionDefinition("团队查看", TeamPermissionCodes.TEAM_VIEW, "允许查看团队资料"),
            new PermissionDefinition("团队更新", TeamPermissionCodes.TEAM_UPDATE, "允许更新团队资料"),
            new PermissionDefinition("成员查看", TeamPermissionCodes.TEAM_MEMBER_VIEW, "允许查看成员列表"),
            new PermissionDefinition("成员创建", TeamPermissionCodes.TEAM_MEMBER_CREATE, "允许创建成员账号"),
            new PermissionDefinition("邀请成员", TeamPermissionCodes.TEAM_MEMBER_INVITE, "允许邀请成员"),
            new PermissionDefinition("成员分配角色", TeamPermissionCodes.TEAM_MEMBER_ASSIGN_ROLE, "允许为成员分配角色"),
            new PermissionDefinition("成员移除", TeamPermissionCodes.TEAM_MEMBER_REMOVE, "允许移除成员"),
            new PermissionDefinition("公告发布", TeamPermissionCodes.TEAM_ANNOUNCEMENT_PUBLISH, "允许发布公告"),
            new PermissionDefinition("禁言管理", TeamPermissionCodes.TEAM_MUTE_MANAGE, "允许禁言和解除禁言"),
            new PermissionDefinition("邀请链接管理", TeamPermissionCodes.TEAM_INVITE_LINK_MANAGE, "允许管理邀请链接"),
            new PermissionDefinition("加入申请审核", TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW, "允许审核加入申请"),
            new PermissionDefinition("团队角色管理", TeamPermissionCodes.TEAM_ROLE_MANAGE, "允许管理团队角色"),
            new PermissionDefinition("团队权限查看", TeamPermissionCodes.TEAM_PERMISSION_READ, "允许查看团队权限"),
            new PermissionDefinition("团队审计查看", TeamPermissionCodes.TEAM_AUDIT_READ, "允许查看团队权限审计"),
            new PermissionDefinition("项目管理", TeamPermissionCodes.TEAM_PROJECT_MANAGE, "允许管理项目组"),
            new PermissionDefinition("团队文件读取", TeamPermissionCodes.TEAM_FILE_READ, "允许读取团队文件"),
            new PermissionDefinition("团队文件写入", TeamPermissionCodes.TEAM_FILE_WRITE, "允许修改团队文件"),
            new PermissionDefinition("团队文件删除", TeamPermissionCodes.TEAM_FILE_DELETE, "允许删除团队文件"),
            new PermissionDefinition("个人存储分配", TeamPermissionCodes.TEAM_STORAGE_ALLOCATE, "允许分配成员个人存储上限")
    );

    private static final List<RoleDefinition> BUILT_IN_ROLES = List.of(
            new RoleDefinition(TeamRoleCodes.OWNER, "团队所有者", "团队拥有者"),
            new RoleDefinition(TeamRoleCodes.ADMIN, "团队管理员", "团队管理员"),
            new RoleDefinition(TeamRoleCodes.MEMBER, "团队成员", "团队普通成员")
    );

    private static final Map<String, List<String>> BUILT_IN_ROLE_PERMISSION_CODES = Map.of(
            TeamRoleCodes.OWNER, List.of(
                    TeamPermissionCodes.TEAM_VIEW,
                    TeamPermissionCodes.TEAM_UPDATE,
                    TeamPermissionCodes.TEAM_MEMBER_VIEW,
                    TeamPermissionCodes.TEAM_MEMBER_CREATE,
                    TeamPermissionCodes.TEAM_MEMBER_INVITE,
                    TeamPermissionCodes.TEAM_MEMBER_ASSIGN_ROLE,
                    TeamPermissionCodes.TEAM_MEMBER_REMOVE,
                    TeamPermissionCodes.TEAM_ANNOUNCEMENT_PUBLISH,
                    TeamPermissionCodes.TEAM_MUTE_MANAGE,
                    TeamPermissionCodes.TEAM_INVITE_LINK_MANAGE,
                    TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW,
                    TeamPermissionCodes.TEAM_PROJECT_MANAGE,
                    TeamPermissionCodes.TEAM_ROLE_MANAGE,
                    TeamPermissionCodes.TEAM_PERMISSION_READ,
                    TeamPermissionCodes.TEAM_AUDIT_READ,
                    TeamPermissionCodes.TEAM_FILE_READ,
                    TeamPermissionCodes.TEAM_FILE_WRITE,
                    TeamPermissionCodes.TEAM_FILE_DELETE,
                    TeamPermissionCodes.TEAM_STORAGE_ALLOCATE
            ),
            TeamRoleCodes.ADMIN, List.of(
                    TeamPermissionCodes.TEAM_VIEW,
                    TeamPermissionCodes.TEAM_UPDATE,
                    TeamPermissionCodes.TEAM_MEMBER_VIEW,
                    TeamPermissionCodes.TEAM_MEMBER_CREATE,
                    TeamPermissionCodes.TEAM_MEMBER_INVITE,
                    TeamPermissionCodes.TEAM_MEMBER_ASSIGN_ROLE,
                    TeamPermissionCodes.TEAM_MEMBER_REMOVE,
                    TeamPermissionCodes.TEAM_ANNOUNCEMENT_PUBLISH,
                    TeamPermissionCodes.TEAM_MUTE_MANAGE,
                    TeamPermissionCodes.TEAM_INVITE_LINK_MANAGE,
                    TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW,
                    TeamPermissionCodes.TEAM_PROJECT_MANAGE,
                    TeamPermissionCodes.TEAM_PERMISSION_READ,
                    TeamPermissionCodes.TEAM_AUDIT_READ,
                    TeamPermissionCodes.TEAM_FILE_READ,
                    TeamPermissionCodes.TEAM_FILE_WRITE,
                    TeamPermissionCodes.TEAM_FILE_DELETE,
                    TeamPermissionCodes.TEAM_STORAGE_ALLOCATE
            ),
            TeamRoleCodes.MEMBER, List.of(
                    TeamPermissionCodes.TEAM_VIEW,
                    TeamPermissionCodes.TEAM_MEMBER_VIEW,
                    TeamPermissionCodes.TEAM_FILE_READ,
                    TeamPermissionCodes.TEAM_FILE_WRITE
            )
    );

    private TeamPermissionPolicy() {
    }

    public static List<PermissionDefinition> builtInPermissions() {
        return BUILT_IN_PERMISSIONS;
    }

    public static List<RoleDefinition> builtInRoles() {
        return BUILT_IN_ROLES;
    }

    public static List<String> permissionCodesForRole(String roleCode) {
        return BUILT_IN_ROLE_PERMISSION_CODES.getOrDefault(roleCode, List.of());
    }

    public record PermissionDefinition(String permissionName, String permissionCode, String description) {
    }

    public record RoleDefinition(String roleCode, String roleName, String description) {
    }
}
