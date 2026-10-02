package uno.acloud.user.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-13：审计切面（AbstractLogAspect）经 {@code Arrays.toString(joinPoint.getArgs())}
 * 记录入参，走的是 Lombok {@code @ToString}。含 password 字段的 DTO 若不
 * {@code exclude}，明文密码会持久化进 operate_log.method_params。
 */
class DtoToStringMaskingTest {

    @Test
    void 应在toString中排除LinkedAccountTrustRequest的密码() {
        LinkedAccountTrustRequest request = new LinkedAccountTrustRequest();
        request.setPassword("plain-secret-123");

        String text = request.toString();

        assertThat(text).doesNotContain("plain-secret-123");
    }

    @Test
    void 应在toString中排除InternalCreateTeamUserRequest的密码() {
        InternalCreateTeamUserRequest request = new InternalCreateTeamUserRequest();
        request.setUsername("zhangsan");
        request.setPassword("plain-secret-456");

        String text = request.toString();

        assertThat(text).doesNotContain("plain-secret-456");
        // 字段名本身可以出现（其余字段仍可读），只断言明文值不出现
        assertThat(text).contains("zhangsan");
    }
}
