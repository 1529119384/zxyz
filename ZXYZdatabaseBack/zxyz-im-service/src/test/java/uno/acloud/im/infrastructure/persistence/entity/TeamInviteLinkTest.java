package uno.acloud.im.infrastructure.persistence.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 60 号 🟡-4 防回归：TeamInviteLink 的 @ToString 不得包含 token（B-13 口径，
 * 与 email 侧 EmailServerConfig 对齐）——@JsonProperty(WRITE_ONLY) 只管 JSON 序列化。
 */
class TeamInviteLinkTest {

    @Test
    void toStringShouldNotLeakInviteToken() {
        TeamInviteLink link = new TeamInviteLink();
        link.setId(1L);
        link.setTeamId(6L);
        link.setToken("abcdef0123456789abcdef0123456789");
        link.setMaxUses(50);
        link.setUsedCount(0);

        String text = link.toString();

        assertFalse(text.contains("abcdef0123456789"), "toString 不得包含邀请 token：" + text);
        assertTrue(text.contains("teamId=6"));
    }
}
