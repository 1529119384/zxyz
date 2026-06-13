package uno.acloud.user.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.user.ZxyzUserApplication;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.EmailServiceMailClient;
import uno.acloud.user.infrastructure.client.TeamServiceMemberClient;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserMapper 集成测试 — 使用 Testcontainers（MySQL + Redis）运行真实 SQL。
 *
 * <p>覆盖核心 CRUD 和 upsert 场景，验证 MyBatis 注解 SQL 正确性。</p>
 */
@SpringBootTest(classes = ZxyzUserApplication.class)
class UserMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_user"; }

    @MockitoBean
    private TeamServicePermissionClient teamServicePermissionClient;

    @MockitoBean
    private TeamServiceMemberClient teamServiceMemberClient;

    @MockitoBean
    private EmailServiceMailClient emailServiceMailClient;

    @Autowired
    private UserMapper userMapper;

    @Test
    void insertAndRetrieveByUsername() {
        User user = new User();
        user.setUsername("testuser_" + System.nanoTime());
        user.setPassword("hashed_password");
        user.setCreateTime(LocalDateTime.now());

        int rows = userMapper.addByUsernameAndPassword(user);
        assertEquals(1, rows);
        assertNotNull(user.getId());

        User found = userMapper.getByUsername(user.getUsername());
        assertNotNull(found);
        assertEquals(user.getId(), found.getId());
        assertEquals(user.getUsername(), found.getUsername());
    }

    @Test
    void searchUsersByPrefix() {
        long ts = System.nanoTime();
        User alice = createUser("alice_" + ts);
        User alex = createUser("alex_" + ts);
        User bob = createUser("bob_" + ts);
        userMapper.addByUsernameAndPassword(alice);
        userMapper.addByUsernameAndPassword(alex);
        userMapper.addByUsernameAndPassword(bob);

        List<User> results = userMapper.searchUsers("alice_" + ts, null, 10);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(u -> u.getUsername().equals("alice_" + ts)));
    }

    @Test
    void upsertVerificationCodeOnDuplicateKey() {
        // 先创建一个用户，避免外键约束问题（如有）
        User user = createUser("vcode_user_" + System.nanoTime());
        userMapper.addByUsernameAndPassword(user);
        long userId = user.getId();

        userMapper.upsertContactVerificationCode(userId, "email", "111111");
        int count1 = userMapper.countValidContactVerificationCode(userId, "email", "111111");
        assertEquals(1, count1);

        // 再次 upsert 同一 (user_id, contact_type) 应替换 code
        userMapper.upsertContactVerificationCode(userId, "email", "222222");
        int countOld = userMapper.countValidContactVerificationCode(userId, "email", "111111");
        assertEquals(0, countOld);
        int countNew = userMapper.countValidContactVerificationCode(userId, "email", "222222");
        assertEquals(1, countNew);
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}
