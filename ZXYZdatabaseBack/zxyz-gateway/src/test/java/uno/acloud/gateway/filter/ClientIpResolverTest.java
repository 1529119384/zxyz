package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private static final Set<String> TRUSTED = Set.of("172.18.0.0/16", "10.0.0.1");

    @Test
    void resolve_whenTrustedProxyConfigured_picksLastNonTrustedHop() {
        // 网关上一跳是可信 nginx(172.18.0.5)，XFF 为 [真实客户端, nginx]，取最右非可信 IP
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve("172.18.0.5", "203.0.113.7, 172.18.0.5", TRUSTED));
    }

    @Test
    void resolve_whenFeatureOff_ignoresXffAndReturnsRemote() {
        // 未配置可信代理 → 默认关闭，反伪造：只用真实远程地址，忽略客户端自传 XFF
        assertEquals("172.18.0.5",
                ClientIpResolver.resolve("172.18.0.5", "203.0.113.7, 8.8.8.8", Set.of()));
    }

    @Test
    void resolve_remoteNotTrusted_refusesToTrustClientSuppliedXff() {
        // 远程地址不在可信代理内（直连/被绕过）→ 不得信任客户端自传的 XFF
        assertEquals("198.51.100.9",
                ClientIpResolver.resolve("198.51.100.9", "203.0.113.7, 8.8.8.8", TRUSTED));
    }

    @Test
    void resolve_skipsTrustedProxyHopsFromRight() {
        // 多级代理：[客户端, 代理A, nginx]，从右跳过所有可信代理取第一个不可信 IP
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve("172.18.0.5", "203.0.113.7, 172.18.1.2, 172.18.0.5", TRUSTED));
    }

    @Test
    void resolve_allHopsAreTrusted_fallsBackToRemote() {
        // XFF 全为可信代理（客户端伪造不出不可信 IP）→ 回退远程地址
        assertEquals("172.18.0.5",
                ClientIpResolver.resolve("172.18.0.5", "172.18.3.9, 172.18.0.5", TRUSTED));
    }

    @Test
    void resolve_singleIpTrustedProxy_matchesExact() {
        assertEquals("203.0.113.7",
                ClientIpResolver.resolve("10.0.0.1", "203.0.113.7, 10.0.0.1", TRUSTED));
    }

    @Test
    void resolve_handlesIpv6ScopeId_andBlankHops() {
        // IPv6 zone 剥离 + 空值跳过
        assertEquals("203.0.113.9",
                ClientIpResolver.resolve("172.18.0.5", "203.0.113.9, , 172.18.0.5", TRUSTED));
    }
}