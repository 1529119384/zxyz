package uno.acloud.project.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectMember;
import uno.acloud.project.service.impl.EmailServiceMailClient;
import uno.acloud.project.service.impl.EmailServiceRestClient;
import uno.acloud.project.service.impl.FileServiceClient;
import uno.acloud.project.service.impl.ImCollaborationClient;
import uno.acloud.project.service.impl.TeamServiceClient;
import uno.acloud.project.service.impl.UserQuotaClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectMapper 集成测试 — 验证 MyBatis 注解 SQL 在真实 MySQL 上的行为。
 *
 * <p>使用 Testcontainers 启动 MySQL 8.0 + Redis 7，
 * Flyway 自动执行 V1__init_schema.sql 建表。</p>
 */
class ProjectMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_project"; }

    @MockitoBean
    private RabbitTemplate rabbitTemplate;
    @MockitoBean
    private FileServiceClient fileServiceClient;
    @MockitoBean
    private TeamServiceClient teamServiceClient;
    @MockitoBean
    private ImCollaborationClient imCollaborationClient;
    @MockitoBean
    private UserQuotaClient userQuotaClient;
    @MockitoBean
    private EmailServiceRestClient emailServiceRestClient;
    @MockitoBean
    private EmailServiceMailClient emailServiceMailClient;

    @Autowired
    private ProjectMapper projectMapper;

    // ---- helper methods ----

    private Project buildProject(String name, Long teamId, Long leaderUserId) {
        Project project = new Project();
        project.setTeamId(teamId);
        project.setName(name);
        project.setDescription("Test project: " + name);
        project.setLeaderUserId(leaderUserId);
        project.setConversationId(null);
        project.setStatus(0);
        LocalDateTime now = LocalDateTime.now();
        project.setCreateTime(now);
        project.setUpdateTime(now);
        return project;
    }

    private ProjectMember buildMember(Long projectId, Long userId, String roleCode) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRoleCode(roleCode);
        member.setJoinTime(LocalDateTime.now());
        return member;
    }

    // ---- tests ----

    /**
     * Insert a project, upsert a member with role "leader",
     * upsert the same (projectId, userId) with role "member",
     * then listMembers() and assert the role was updated.
     */
    @Test
    void insertAndUpsertMember() {
        // Insert project
        Project project = buildProject("Upsert Project", 1L, 100L);
        projectMapper.insert(project);
        assertNotNull(project.getId(), "Project should get auto-generated ID");

        // Insert member with role "leader"
        ProjectMember member = buildMember(project.getId(), 200L, "leader");
        int rows1 = projectMapper.upsertMember(member);
        assertEquals(1, rows1, "First upsert should insert 1 row");

        // Verify member was inserted with role "leader"
        List<ProjectMember> membersAfterFirst = projectMapper.listMembers(project.getId());
        assertEquals(1, membersAfterFirst.size());
        assertEquals("leader", membersAfterFirst.get(0).getRoleCode());
        assertEquals(200L, membersAfterFirst.get(0).getUserId());

        // Upsert same (projectId, userId) with role "member"
        ProjectMember updatedMember = buildMember(project.getId(), 200L, "member");
        int rows2 = projectMapper.upsertMember(updatedMember);
        assertEquals(1, rows2, "Second upsert should update 1 row (ON DUPLICATE KEY)");

        // Verify role was updated to "member"
        List<ProjectMember> membersAfterSecond = projectMapper.listMembers(project.getId());
        assertEquals(1, membersAfterSecond.size(), "Should still have exactly 1 member");
        assertEquals("member", membersAfterSecond.get(0).getRoleCode(),
                "Role should be updated to 'member' after upsert");
        assertEquals(200L, membersAfterSecond.get(0).getUserId());
    }

    /**
     * Insert 3 projects in the same team (2 active with same name, 1 archived),
     * call countActiveByTeamIdAndName() and assert correct count.
     */
    @Test
    void countActiveByTeamIdAndName() {
        Long teamId = 10L;
        String projectName = "Shared Name";

        // Active project 1 with the target name
        Project active1 = buildProject(projectName, teamId, 100L);
        active1.setStatus(0);
        projectMapper.insert(active1);

        // Active project 2 with the same name
        Project active2 = buildProject(projectName, teamId, 101L);
        active2.setStatus(0);
        projectMapper.insert(active2);

        // Archived project with the same name
        Project archived = buildProject(projectName, teamId, 102L);
        archived.setStatus(1);
        projectMapper.insert(archived);

        // Count active projects with the same name in the team
        int count = projectMapper.countActiveByTeamIdAndName(teamId, projectName);
        assertEquals(2, count, "Should count only active (status=0) projects with matching name");
    }

    /**
     * Insert a project with status=0, call archiveProject(),
     * retrieve the project and assert status changed to 1.
     */
    @Test
    void archiveProjectSetsStatus() {
        // Insert active project
        Project project = buildProject("Archive Me", 5L, 50L);
        project.setStatus(0);
        projectMapper.insert(project);
        assertNotNull(project.getId());

        // Verify initial status
        Project before = projectMapper.selectById(project.getId());
        assertNotNull(before);
        assertEquals(0, before.getStatus(), "Initial status should be 0 (active)");

        // Archive the project
        int rows = projectMapper.archiveProject(project.getId());
        assertEquals(1, rows, "archiveProject should affect 1 row");

        // Verify status changed to 1 (archived)
        Project after = projectMapper.selectById(project.getId());
        assertNotNull(after);
        assertEquals(1, after.getStatus(), "Status should be 1 (archived) after archiveProject");
    }
}
