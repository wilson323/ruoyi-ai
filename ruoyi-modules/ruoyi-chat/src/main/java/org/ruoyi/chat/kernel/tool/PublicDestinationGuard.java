package org.ruoyi.chat.kernel.tool;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 聊天内核 web_fetch 的目标地址守卫：只放行公网地址。
 *
 * <p>官方 web_fetch 由模型给 URL、在服务器本机发起请求，SDK 自身没有任何目标检查。聊天内核放行
 * 官方联网工具后，必须自己拦住 回环 / 链路本地（含云元数据 169.254.169.254）/ RFC1918 私网 /
 * CGN / IPv6 唯一本地 等内网目标，否则模型（或被提示词注入的内容）可借服务器访问内网。
 * 这不是审批：公网地址仍直接放行。
 *
 * <p>判定走 {@link InetAddress#getAllByName}，与 JDK HttpClient 出站连接同一解析器，十进制/十六进制
 * 等 IP 字面量变体被解析成同一地址，不存在“校验器看不懂、连接器连得上”的差异。解析失败按拒绝处理
 * （fail-closed）。与 IPD 内核的同类检查口径一致（IPD 的实现在 ruoyi-ipd，聊天模块不能反向依赖）。
 * 已知局限：先检查后连接之间存在 DNS 重绑定时间窗；JDK HttpClient 默认不跟随重定向，故无重定向绕过。
 */
final class PublicDestinationGuard {
    /** 对外统一文案：不泄露具体命中的网段。 */
    static final String BLOCKED_REASON = "Web destination is outside authorized public endpoints";

    private PublicDestinationGuard() { }

    /** @return null 表示放行；非空为拒绝原因（对外安全文案）。 */
    static String blockReason(Object urlValue) {
        return blockReason(urlValue, InetAddress::getAllByName);
    }

    /** 解析器可注入，仅为测试提供确定性；生产恒走 JDK 解析器（与 HttpClient 出站同一解析器）。 */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static String blockReason(Object urlValue, Resolver resolver) {
        if (!(urlValue instanceof String url) || url.isBlank()) {
            return BLOCKED_REASON;
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (RuntimeException invalid) {
            return BLOCKED_REASON;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return BLOCKED_REASON;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null) {
            return BLOCKED_REASON;
        }
        try {
            for (InetAddress address : resolver.resolve(host)) {
                if (isBlocked(address.getAddress())) {
                    return BLOCKED_REASON;
                }
            }
            return null;
        } catch (UnknownHostException | SecurityException unresolved) {
            return BLOCKED_REASON;
        }
    }

    static boolean isBlocked(byte[] raw) {
        if (raw == null) {
            return true;
        }
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            raw = new byte[] {raw[12], raw[13], raw[14], raw[15]};
        }
        if (raw.length != 4 && raw.length != 16) {
            return true;
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(raw);
        } catch (UnknownHostException invalid) {
            return true;
        }
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isMulticastAddress() || addr.isSiteLocalAddress()) {
            return true;
        }
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10 运营商级 NAT
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return true;
            }
        }
        if (raw.length == 16) {
            // fc00::/7 唯一本地地址；fe80::/10 显式断言，不依赖 JDK 谓词语义
            if ((raw[0] & 0xFE) == 0xFC) {
                return true;
            }
            if (raw[0] == (byte) 0xFE && (raw[1] & 0xC0) == 0x80) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }
}
