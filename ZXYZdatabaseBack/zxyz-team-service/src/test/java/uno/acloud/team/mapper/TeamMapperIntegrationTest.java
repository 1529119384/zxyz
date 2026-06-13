package uno.acloud.team.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamMapperIntegrationTest extends AbstractIntegrationTest {

    static {
        DB_NAME = "zxyz_team";
    }

    @Autowired
    private TeamMapper teamMapper;

    @Autowired
    private TeamQuotaMapper teamQuotaMapper;

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
    void insertAndRetrieveTeam() {
        LocalDateTime now = LocalDateTime.now();

        Team team = new Team();
        team.setName("Test Team");
        team.setAvatar("https://example.com/avatar.png");
        team.setDescription("A test team");
        team.setOwnerUserId(1001L);
        team.setStatus(0);
        team.setCreateTime(now);
        team.setUpdateTime(now);

        int rows = teamMapper.insert(team);
        assertEquals(1, rows);
        assertNotNull(team.getId());

        Team retrieved = teamMapper.selectById(team.getId());
        assertNotNull(retrieved);
        assertEquals("Test Team", retrieved.getName());
        assertEquals("https://example.com/avatar.png", retrieved.getAvatar());
        assertEquals("A test team", retrieved.getDescription());
        assertEquals(1001L, retrieved.getOwnerUserId());
        assertEquals(0, retrieved.getStatus());
    }

    @Test
    void upsertMemberOnDuplicateKey() {
        LocalDateTime now = LocalDateTime.now();

        // Insert a team first
        Team team = new Team();
        team.setName("Upsert Test Team");
        team.setOwnerUserId(2001L);
        team.setStatus(0);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        teamMapper.insert(team);

        // Insert member with role "team_member"
        TeamMember member = new TeamMember();
        member.setTeamId(team.getId());
        member.setUserId(3001L);
        member.setRoleCode("team_member");
        member.setStatus(0);
        member.setJoinTime(now);
        member.setUpdateTime(now);
        teamMapper.upsertMember(member);

        TeamMember first = teamMapper.getActiveMember(team.getId(), 3001L);
        assertNotNull(first);
        assertEquals("team_member", first.getRoleCode());

        // Upsert same member with role "team_admin"
        member.setRoleCode("team_admin");
        member.setUpdateTime(LocalDateTime.now());
        teamMapper.upsertMember(member);

        TeamMember updated = teamMapper.getActiveMember(team.getId(), 3001L);
        assertNotNull(updated);
        assertEquals("team_admin", updated.getRoleCode());
    }

    @Test
    void listAdminTeamOverviews() {
        LocalDateTime now = LocalDateTime.now();

        // Insert team
        Team team = new Team();
        team.setName("Overview Team");
        team.setDescription("Team for overview test");
        team.setOwnerUserId(4001L);
        team.setStatus(0);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        teamMapper.insert(team);

        // Insert 2 active members (status=0) and 1 removed member (status=2)
        for (long userId : List.of(4001L, 4002L)) {
            TeamMember m = new TeamMember();
            m.setTeamId(team.getId());
            m.setUserId(userId);
            m.setRoleCode("team_member");
            m.setStatus(0);
            m.setJoinTime(now);
            m.setUpdateTime(now);
            teamMapper.upsertMember(m);
        }
        TeamMember removed = new TeamMember();
        removed.setTeamId(team.getId());
        removed.setUserId(4003L);
        removed.setRoleCode("team_member");
        removed.setStatus(2);
        removed.setJoinTime(now);
        removed.setUpdateTime(now);
        teamMapper.upsertMember(removed);

        // Insert team quota
        TeamQuota quota = new TeamQuota();
        quota.setTeamId(team.getId());
        quota.setMemberLimit(50);
        quota.setStorageLimit(107374182400L);
        quota.setCreateTime(now);
        quota.setUpdateTime(now);
        teamQuotaMapper.upsertQuota(quota);

        List<AdminTeamOverviewVO> overviews = teamMapper.listAdminTeamOverviews();
        assertNotNull(overviews);
        assertFalse(overviews.isEmpty());

        AdminTeamOverviewVO overview = overviews.stream()
                .filter(o -> o.getId().equals(team.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(overview, "Should find the inserted team in overviews");
        assertEquals(2, overview.getMemberCount(), "Only active members (status IN 0,1) should be counted");
        assertEquals(50, overview.getMemberLimit());
        assertEquals(107374182400L, overview.getStorageLimit());
    }
}
