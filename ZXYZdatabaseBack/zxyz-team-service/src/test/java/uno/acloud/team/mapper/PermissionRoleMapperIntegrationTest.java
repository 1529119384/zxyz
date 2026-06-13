package uno.acloud.team.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.team.entity.PermissionEntity;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRoleMapperIntegrationTest extends AbstractIntegrationTest {

    static {
        DB_NAME = "zxyz_team";
    }

    @Autowired
    private PermissionRoleMapper permissionRoleMapper;

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private TeamPermissionMapper teamPermissionMapper;

    @MockitoBean
    private FileServiceClient fileServiceClient;

    @MockitoBean
    private UserServiceClient userServiceClient;

    @MockitoBean
    private ProjectServiceClient projectServiceClient;

    @MockitoBean
    private EmailServiceClient emailServiceClient;

    @MockitoBean
    private ImSystemNotificationClient imSystemNotificationClient;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void permissionRoleJoinQuery() {
        // Insert a system-level permission
        permissionRoleMapper.insertPermissionIgnore("File Read", "file:read", "Read files");

        PermissionEntity perm = permissionRoleMapper.getPermissionByCode("file:read");
        assertNotNull(perm, "Permission should be retrievable by code");
        assertEquals("file:read", perm.getPermissionCode());

        // Insert a system-level role
        RoleEntity role = new RoleEntity();
        role.setRoleName("Test Role");
        role.setRoleCode("test_role");
        role.setDescription("A role for testing");
        permissionRoleMapper.insertRole(role);
        assertNotNull(role.getId());

        // Link role to permission
        int linked = permissionRoleMapper.insertRolePermission(role.getId(), perm.getId());
        assertEquals(1, linked);

        // Assign user to role
        long userId = 5001L;
        permissionRoleMapper.insertUserRole(userId, role.getId());

        // Query permissions by user ID
        List<String> codes = permissionRoleMapper.getPermissionByUserID(userId);
        assertNotNull(codes);
        assertTrue(codes.contains("file:read"), "User should have the linked permission");
    }

    @Test
    void teamPermissionCodesMultiJoin() {
        LocalDateTime now = LocalDateTime.now();

        // 1. Insert a team
        Team team = new Team();
        team.setName("Perm Test Team");
        team.setOwnerUserId(6001L);
        team.setStatus(0);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        teamMapper.insert(team);
        Long teamId = team.getId();

        // 2. Insert team_permission
        TeamPermissionMapper.TeamPermissionSeed permSeed = new TeamPermissionMapper.TeamPermissionSeed();
        permSeed.permissionName = "Team File Read";
        permSeed.permissionCode = "team:file:read";
        permSeed.description = "Read files in team";
        teamPermissionMapper.upsertPermission(permSeed);
        Integer teamPermId = teamPermissionMapper.getPermissionId("team:file:read");
        assertNotNull(teamPermId, "Team permission should be inserted");

        // 3. Insert team_role
        TeamPermissionMapper.TeamRoleSeed roleSeed = new TeamPermissionMapper.TeamRoleSeed();
        roleSeed.teamId = teamId;
        roleSeed.roleName = "Team Member";
        roleSeed.roleCode = "team_member";
        roleSeed.description = "Standard team member";
        teamPermissionMapper.upsertRole(roleSeed);
        Long teamRoleId = teamPermissionMapper.getRoleId(teamId, "team_member");
        assertNotNull(teamRoleId, "Team role should be inserted");

        // 4. Link team_role to team_permission
        int rpRows = teamPermissionMapper.insertRolePermission(teamId, teamRoleId, teamPermId);
        assertEquals(1, rpRows);

        // 5. Insert team_member (status=0 for active)
        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setUserId(6002L);
        member.setRoleCode("team_member");
        member.setStatus(0);
        member.setJoinTime(now);
        member.setUpdateTime(now);
        teamMapper.upsertMember(member);

        // 6. Insert team_member_role
        teamPermissionMapper.insertMemberRole(teamId, 6002L, teamRoleId);

        // 7. Query team permission codes
        List<String> codes = permissionRoleMapper.getTeamPermissionCodes(6002L, teamId);
        assertNotNull(codes);
        assertTrue(codes.contains("team:file:read"),
                "User should have team:file:read permission through team role assignment");
    }
}
