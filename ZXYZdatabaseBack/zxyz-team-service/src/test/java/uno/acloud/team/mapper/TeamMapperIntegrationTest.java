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

        // 分页查询与计数必须同口径（t.status = 0）。这里刻意用「一页装得下」的 pageSize，
        // 验证 SQL 拼接顺序正确（LIMIT #{pageSize} OFFSET #{offset}）而不是拿分页把结果截断。
        long total = teamMapper.countAdminTeamOverviews();
        assertTrue(total >= 1L, "刚插入的团队必须被计入总数");
        List<AdminTeamOverviewVO> overviews = teamMapper.listAdminTeamOverviewsPaged(200, 0);
        assertNotNull(overviews);
        assertFalse(overviews.isEmpty());
        // 口径一致性：一页装得下时返回条数必须等于总数。两条 SQL 的 WHERE 一旦漂移，这里会红
        // （这正是「总数够但翻页越界」这类只在翻到底才暴露的错的第一道拦网）。
        assertEquals((int) Math.min(total, 200L), overviews.size(),
                "分页查询与计数的口径必须一致（同为 t.status = 0）");

        AdminTeamOverviewVO overview = overviews.stream()
                .filter(o -> o.getId().equals(team.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(overview, "Should find the inserted team in overviews");
        assertEquals(2, overview.getMemberCount(), "Only active members (status IN 0,1) should be counted");
        assertEquals(50, overview.getMemberLimit());
        assertEquals(107374182400L, overview.getStorageLimit());
    }

    /**
     * 单团队查询：必须只返回被请求的那一个。
     *
     * <p>该 SQL 与列表查询共用同一段 SELECT 常量，只有追加的 WHERE 片段不同 ——
     * 所以这里刻意同时落两个团队，验证「过滤真的生效」而不是「碰巧只查了一条」。</p>
     */
    @Test
    void getAdminTeamOverview_shouldReturnOnlyRequestedTeam() {
        LocalDateTime now = LocalDateTime.now();

        Team target = overviewTeam("Target Team", 5001L, now);
        teamMapper.insert(target);
        Team other = overviewTeam("Other Team", 5002L, now);
        teamMapper.insert(other);

        addTeamMember(target.getId(), 5001L, 0, now);
        addTeamMember(target.getId(), 5002L, 0, now);
        addTeamMember(target.getId(), 5003L, 2, now); // 已移除，不应计入
        upsertTeamQuota(target.getId(), 30, 2147483648L, now);

        AdminTeamOverviewVO overview = teamMapper.getAdminTeamOverview(target.getId());

        assertNotNull(overview, "应能查到被请求的团队");
        assertEquals(target.getId(), overview.getId());
        assertEquals("Target Team", overview.getName());
        assertEquals(5001L, overview.getOwnerUserId());
        assertEquals(2, overview.getMemberCount(), "只统计 status IN (0,1) 的成员");
        assertEquals(30, overview.getMemberLimit());
        assertEquals(2147483648L, overview.getStorageLimit());

        // 另一个团队不会因为共用 SELECT 而被顺带返回
        assertNotEquals(other.getId(), overview.getId());
        assertNull(teamMapper.getAdminTeamOverview(999999999L), "不存在的团队应返回 null");
    }

    private Team overviewTeam(String name, long ownerUserId, LocalDateTime now) {
        Team team = new Team();
        team.setName(name);
        team.setDescription("overview fixture");
        team.setOwnerUserId(ownerUserId);
        team.setStatus(0);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        return team;
    }

    private void addTeamMember(Long teamId, long userId, int status, LocalDateTime now) {
        TeamMember m = new TeamMember();
        m.setTeamId(teamId);
        m.setUserId(userId);
        m.setRoleCode("team_member");
        m.setStatus(status);
        m.setJoinTime(now);
        m.setUpdateTime(now);
        teamMapper.upsertMember(m);
    }

    private void upsertTeamQuota(Long teamId, int memberLimit, long storageLimit, LocalDateTime now) {
        TeamQuota quota = new TeamQuota();
        quota.setTeamId(teamId);
        quota.setMemberLimit(memberLimit);
        quota.setStorageLimit(storageLimit);
        quota.setCreateTime(now);
        quota.setUpdateTime(now);
        teamQuotaMapper.upsertQuota(quota);
    }
}
