package uno.acloud.gateway.filter;

import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Set;

/**
 * 真实客户端 IP 解析。
 * <p>部署拓扑 {@code frontend-nginx → gateway → service}，网关限流/安全需基于真实源 IP
 * 而非上一级代理 IP。本工具只在配置了可信代理（{@code app.gateway.trusted-proxies}）后
 * 才信任 {@code X-Forwarded-For}，从右向左跳过可信代理，取第一个非可信 IP 即真实客户端；
 * 未配置时默认关闭该能力，直接返回远程地址，杜绝客户端伪造源 IP。</p>
 */
public final class ClientIpResolver {

    public static final String X_REAL_IP_HEADER = "X-Real-IP";
    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /**
     * 解析真实客户端 IP。
     *
     * @param remoteHost    网关直接收到的连接远程地址（即上一级代理，如 nginx）
     * @param xForwardedFor 请求携带的 XFF（原始值，可能含客户端伪造的前缀）
     * @param trustedProxies 可信代理 IP 集合（支持单 IP 与 CIDR 网段），空 = 功能关闭
     * @return 真实客户端 IP；无法可靠解析时回退到 remoteHost
     */
    public static String resolve(String remoteHost, String xForwardedFor, Set<String> trustedProxies) {
        String normalizedRemote = normalize(remoteHost);
        // 未配置可信代理 → 默认关闭，不信任任何 forwarded 头，仅用真实远程地址
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return normalizedRemote;
        }
        // 远程地址不在可信代理内 → 请求未经过可信代理直连，不得信任客户端自传的 XFF
        if (!isTrustedProxy(normalizedRemote, trustedProxies)) {
            return normalizedRemote;
        }
        if (!StringUtils.hasText(xForwardedFor)) {
            return normalizedRemote;
        }
        // 从右往左取，跳过可信代理，第一个不可信 IP 即真实客户端
        String[] hops = xForwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalize(hops[i]);
            if (!StringUtils.hasText(hop)) {
                continue;
            }
            if (isTrustedProxy(hop, trustedProxies)) {
                continue;
            }
            return hop;
        }
        return normalizedRemote;
    }

    /**
     * 判断指定 IP 是否命中可信代理集合中的任意 CIDR 网段或单 IP。
     */
    private static boolean isTrustedProxy(String ip, Set<String> trustedProxies) {
        return trustedProxies.stream().anyMatch(tp -> ipInCidrOrEqual(ip, tp));
    }

    private static boolean ipInCidrOrEqual(String ip, String cidr) {
        String trimmed = cidr.trim();
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            return ip != null && ip.equals(normalize(trimmed));
        }
        return ipInCidr(ip, trimmed);
    }

    private static boolean ipInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkIp = normalize(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            InetAddress targetAddr = InetAddress.getByName(ip);
            InetAddress networkAddr = InetAddress.getByName(networkIp);

            byte[] target = targetAddr.getAddress();
            byte[] network = networkAddr.getAddress();
            if (target.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes && i < target.length; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < target.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((target[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // 非法 CIDR/IP 按不匹配处理，静默回退
            return false;
        }
    }

    /**
     * 移除 IPv6 作用域（如 {@code fe80::1%eth0} → {@code fe80::1}）。
     */
    private static String normalize(String ip) {
        if (ip == null) {
            return null;
        }
        String clean = ip.trim();
        int zoneIdx = clean.indexOf('%');
        if (zoneIdx >= 0) {
            clean = clean.substring(0, zoneIdx);
        }
        return clean;
    }
}