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

        int maxAttempts = 5;

        userMapper.upsertContactVerificationCode(userId, "email", "111111");
        assertEquals(1, userMapper.consumeContactVerificationCode(userId, "email", "111111", maxAttempts),
                "刚写入的码应可被消费");
        assertEquals(0, userMapper.consumeContactVerificationCode(userId, "email", "111111", maxAttempts),
                "同一个码只能消费一次");

        // 再次 upsert 同一 (user_id, contact_type) 应替换 code，并把使用状态一并复位
        userMapper.upsertContactVerificationCode(userId, "email", "222222");
        assertEquals(0, userMapper.consumeContactVerificationCode(userId, "email", "111111", maxAttempts),
                "旧码应已失效");
        assertEquals(1, userMapper.consumeContactVerificationCode(userId, "email", "222222", maxAttempts),
                "新码应可用");
    }

    /**
     * 尝试次数上限必须真的能把码作废 —— 这是防 6 位码爆破的核心控制，
     * 光有 attempt_count 列而 SQL 不生效等于没做。
     *
     * <p>同时把「上限是<b>含</b>第 maxAttempts 次的」钉死：MySQL 单表 UPDATE 的 SET 子句
     * 从左到右求值，照抄 {@code attempt_count + 1 > maxAttempts} 会少给一次机会。</p>
     */
    @Test
    void contactVerificationCodeAttemptLimitShouldInvalidateCode() {
        User user = createUser("vcode_limit_" + System.nanoTime());
        userMapper.addByUsernameAndPassword(user);
        long userId = user.getId();

        int maxAttempts = 3;

        // ---------------- 场景 A：第 maxAttempts 次尝试仍然可用 ----------------
        userMapper.upsertContactVerificationCode(userId, "phone", "111111");
        for (int i = 1; i <= maxAttempts; i++) {
            assertEquals(1, userMapper.bumpContactVerificationAttempt(userId, "phone", maxAttempts),
                    "第 " + i + " 次尝试应命中存活码");
            // 前 maxAttempts-1 次用错误码，最后一次用正确码 —— 最后一次必须成功
            String guess = i < maxAttempts ? "000000" : "111111";
            assertEquals(i < maxAttempts ? 0 : 1,
                    userMapper.consumeContactVerificationCode(userId, "phone", guess, maxAttempts),
                    "第 " + i + " 次尝试的消费结果不符合预期");
        }

        // ---------------- 场景 B：第 maxAttempts+1 次把码作废 ----------------
        userMapper.upsertContactVerificationCode(userId, "phone", "123456");
        for (int i = 0; i < maxAttempts; i++) {
            assertEquals(1, userMapper.bumpContactVerificationAttempt(userId, "phone", maxAttempts),
                    "上限内的每次尝试都应命中存活码");
            assertEquals(0, userMapper.consumeContactVerificationCode(userId, "phone", "000000", maxAttempts),
                    "错误的码不应被消费");
        }
        // 这一次自增后已越过上限，正是它把码置为已使用（作废）
        assertEquals(1, userMapper.bumpContactVerificationAttempt(userId, "phone", maxAttempts),
                "第 maxAttempts+1 次仍会命中存活行，并由它完成作废");
        Integer attempt = userMapper.findContactVerificationAttempt(userId, "phone");
        assertNotNull(attempt);
        assertTrue(attempt > maxAttempts, "尝试次数应已越过上限");
        // 作废后即便拿到正确的码也救不回来 —— 这正是"作废"的意义
        assertEquals(0, userMapper.consumeContactVerificationCode(userId, "phone", "123456", maxAttempts),
                "作废后的码即使正确也不可用");
        assertEquals(0, userMapper.bumpContactVerificationAttempt(userId, "phone", maxAttempts),
                "作废后的码不应再被命中");

        // ---------------- 场景 C：重发复位，用户不会被永久锁死 ----------------
        userMapper.upsertContactVerificationCode(userId, "phone", "654321");
        assertEquals(0, userMapper.findContactVerificationAttempt(userId, "phone").intValue(),
                "重发应把尝试次数清零");
        assertEquals(1, userMapper.bumpContactVerificationAttempt(userId, "phone", maxAttempts));
        assertEquals(1, userMapper.consumeContactVerificationCode(userId, "phone", "654321", maxAttempts));
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}
