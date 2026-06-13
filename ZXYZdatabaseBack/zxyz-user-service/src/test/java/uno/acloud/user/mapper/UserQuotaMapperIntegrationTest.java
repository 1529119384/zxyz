package uno.acloud.user.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.user.ZxyzUserApplication;
import uno.acloud.user.entity.UserQuota;
import uno.acloud.user.infrastructure.client.EmailServiceMailClient;
import uno.acloud.user.infrastructure.client.TeamServiceMemberClient;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserQuotaMapper 集成测试 — 验证 user_quota 表的查询逻辑。
 */
@SpringBootTest(classes = ZxyzUserApplication.class)
class UserQuotaMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_user"; }

    @MockitoBean
    private TeamServicePermissionClient teamServicePermissionClient;

    @MockitoBean
    private TeamServiceMemberClient teamServiceMemberClient;

    @MockitoBean
    private EmailServiceMailClient emailServiceMailClient;

    @Autowired
    private UserQuotaMapper userQuotaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void getQuotaByUserId() {
        // 先在 user 表插入一条记录
        long ts = System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO `user` (username, password, create_time) VALUES (?, ?, ?)",
                "quota_user_" + ts, "password", LocalDateTime.now());
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, "quota_user_" + ts);

        jdbcTemplate.update(
                "INSERT INTO user_quota (user_id, storage_limit, create_time, update_time) VALUES (?, ?, ?, ?)",
                userId, 1024L, LocalDateTime.now(), LocalDateTime.now());

        UserQuota quota = userQuotaMapper.getByUserId(userId);
        assertNotNull(quota);
        assertEquals(1024L, quota.getStorageLimit());
        assertEquals(userId, quota.getUserId());
    }

    @Test
    void getQuotaReturnsNull() {
        UserQuota quota = userQuotaMapper.getByUserId(9999999L);
        assertNull(quota);
    }
}
