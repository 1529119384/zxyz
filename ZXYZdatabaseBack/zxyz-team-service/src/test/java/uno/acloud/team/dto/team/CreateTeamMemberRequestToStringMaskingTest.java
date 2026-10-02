package uno.acloud.team.dto.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-13(team侧)：审计切面（AbstractLogAspect）经 {@code Arrays.toString(joinPoint.getArgs())}
 * 记录入参，走的是 Lombok {@code @ToString}。含 password 字段的 DTO 若不 {@code exclude}，
 * 明文密码会持久化进 operate_log.method_params。
 * <p>注：JSON 形态正则对 {@code password=secret}（无引号）完全无效，DTO 侧 exclude 是根因防线。</p>
 */
class CreateTeamMemberRequestToStringMaskingTest {

    @Test
    void toString应排除密码明文() {
        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setUsername("zhangsan");
        request.setPassword("plain-secret-789");
        request.setName("张三");
        request.setRoleCode("member");

        String text = request.toString();

        assertFalse(text.contains("plain-secret-789"), "toString 不得包含明文密码：" + text);
        // 其余字段仍可读，只排除密码明文值
        assertTrue(text.contains("zhangsan"));
        assertTrue(text.contains("roleCode=member"));
    }

    /** CreateTeamRequest.ownerPassword 是同根因泄露面（大管理员初始密码）。 */
    @Test
    void createTeamRequest的ToString应排除OwnerPassword明文() {
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName("我的团队");
        request.setOwnerUsername("admin");
        request.setOwnerPassword("plain-owner-secret-001");

        String text = request.toString();

        assertFalse(text.contains("plain-owner-secret-001"), "toString 不得包含 ownerPassword 明文：" + text);
        assertTrue(text.contains("admin"));
    }
}
