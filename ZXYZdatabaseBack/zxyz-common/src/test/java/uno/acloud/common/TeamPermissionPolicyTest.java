package uno.acloud.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamPermissionPolicyTest {

    @Test
    void builtInPermissionsShouldContainUnifiedPermissionSet() {
        Set<String> permissionCodes = TeamPermissionPolicy.builtInPermissions().stream()
                .map(TeamPermissionPolicy.PermissionDefinition::permissionCode)
                .collect(Collectors.toSet());

        assertEquals(19, permissionCodes.size());
        assertTrue(permissionCodes.containsAll(Set.of(
                TeamPermissionCodes.TEAM_MEMBER_CREATE,
                TeamPermissionCodes.TEAM_PROJECT_MANAGE,
                TeamPermissionCodes.TEAM_ROLE_MANAGE,
                TeamPermissionCodes.TEAM_AUDIT_READ
        )));
    }

    @Test
    void builtInRolesShouldUseUnifiedDisplayNames() {
        assertEquals(List.of(
                new TeamPermissionPolicy.RoleDefinition(TeamRoleCodes.OWNER, "团队所有者", "团队拥有者"),
                new TeamPermissionPolicy.RoleDefinition(TeamRoleCodes.ADMIN, "团队管理员", "团队管理员"),
                new TeamPermissionPolicy.RoleDefinition(TeamRoleCodes.MEMBER, "团队成员", "团队普通成员")
        ), TeamPermissionPolicy.builtInRoles());
    }

    @Test
    void builtInRolePermissionsShouldMatchMainServiceDefaults() {
        Set<String> allPermissionCodes = TeamPermissionPolicy.builtInPermissions().stream()
                .map(TeamPermissionPolicy.PermissionDefinition::permissionCode)
                .collect(Collectors.toSet());
        List<String> ownerPermissions = TeamPermissionPolicy.permissionCodesForRole(TeamRoleCodes.OWNER);
        List<String> adminPermissions = TeamPermissionPolicy.permissionCodesForRole(TeamRoleCodes.ADMIN);
        List<String> memberPermissions = TeamPermissionPolicy.permissionCodesForRole(TeamRoleCodes.MEMBER);

        assertEquals(allPermissionCodes, Set.copyOf(ownerPermissions));
        assertTrue(adminPermissions.containsAll(List.of(
                TeamPermissionCodes.TEAM_MEMBER_CREATE,
                TeamPermissionCodes.TEAM_PROJECT_MANAGE,
                TeamPermissionCodes.TEAM_AUDIT_READ
        )));
        assertFalse(adminPermissions.contains(TeamPermissionCodes.TEAM_ROLE_MANAGE));
        assertEquals(List.of(
                TeamPermissionCodes.TEAM_VIEW,
                TeamPermissionCodes.TEAM_MEMBER_VIEW,
                TeamPermissionCodes.TEAM_FILE_READ,
                TeamPermissionCodes.TEAM_FILE_WRITE
        ), memberPermissions);
    }
}
