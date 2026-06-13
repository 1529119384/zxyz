package uno.acloud.project.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.project.service.impl.EmailServiceMailClient;
import uno.acloud.project.service.impl.EmailServiceRestClient;
import uno.acloud.project.service.impl.FileServiceClient;
import uno.acloud.project.service.impl.ImCollaborationClient;
import uno.acloud.project.service.impl.TeamServiceClient;
import uno.acloud.project.service.impl.UserQuotaClient;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectQuotaMapper 集成测试 — 验证 MyBatis 注解 SQL 在真实 MySQL 上的行为。
 *
 * <p>使用 Testcontainers 启动 MySQL 8.0 + Redis 7，
 * Flyway 自动执行 V1__init_schema.sql 建表。</p>
 */
class ProjectQuotaMapperIntegrationTest extends AbstractIntegrationTest {

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
    private ProjectQuotaMapper projectQuotaMapper;

    // ---- tests ----

    /**
     * Insert a quota with storageLimit=1024, then upsert again with storageLimit=2048.
     * Retrieve via getByProjectId() and assert storageLimit was updated to 2048.
     */
    @Test
    void upsertQuotaOnDuplicateKey() {
        LocalDateTime now = LocalDateTime.now();

        // Insert initial quota with storageLimit=1024
        ProjectQuota quota1 = new ProjectQuota();
        quota1.setProjectId(1L);
        quota1.setStorageLimit(1024L);
        quota1.setCreateTime(now);
        quota1.setUpdateTime(now);

        int rows1 = projectQuotaMapper.upsertQuota(quota1);
        assertEquals(1, rows1, "First upsert should insert 1 row");

        // Verify initial insert
        ProjectQuota retrieved1 = projectQuotaMapper.getByProjectId(1L);
        assertNotNull(retrieved1, "Quota should exist after insert");
        assertEquals(1024L, retrieved1.getStorageLimit(), "Initial storageLimit should be 1024");

        // Upsert same projectId with storageLimit=2048
        LocalDateTime later = LocalDateTime.now();
        ProjectQuota quota2 = new ProjectQuota();
        quota2.setProjectId(1L);
        quota2.setStorageLimit(2048L);
        quota2.setCreateTime(now);
        quota2.setUpdateTime(later);

        int rows2 = projectQuotaMapper.upsertQuota(quota2);
        assertEquals(1, rows2, "Second upsert should update 1 row (ON DUPLICATE KEY)");

        // Verify storageLimit was updated
        ProjectQuota retrieved2 = projectQuotaMapper.getByProjectId(1L);
        assertNotNull(retrieved2, "Quota should still exist after upsert");
        assertEquals(2048L, retrieved2.getStorageLimit(),
                "storageLimit should be updated to 2048 after upsert on duplicate key");
    }
}
