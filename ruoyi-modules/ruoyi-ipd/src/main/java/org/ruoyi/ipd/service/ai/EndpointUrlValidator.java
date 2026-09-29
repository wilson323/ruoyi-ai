package org.ruoyi.ipd.service.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * P0-3（D 轮 D2 SSRF-01 / D4 N-3，2026-09-29）：模型端点 SSRF 黑名单<b>唯一实现</b>。
 * <ul>
 *   <li><b>B 路（保存入口）</b>：{@link #blockReason(String, String)} 供
 *       {@code AiModelConfigService.validate()} 在 create/update 时前置拒绝
 *       RFC1918 / 链路本地 / 回环 / 云元数据（169.254.169.254）/ CGN / ULA 等内网目标，
 *       堵死「注册 169.254.169.254 模型配置 → is_active=1 → AI 调用链出站」的 SSRF 注册面；</li>
 *   <li><b>C 路（出站调用点）</b>：{@link #isBlockedIp(byte[])} 供
 *       {@link AiChatClient#ssrfCheck}（AiGateway chat/embed/stream 唯一前置）继续做
 *       出站前 fail-closed 判定——D4 要求「必须在建立出站调用前校验（防 DNS 重绑定），
 *       不可只在保存时校验」，B 路是纵深防御，C 路才是权威关口；</li>
 *   <li><b>解析器一致性</b>：黑名单判定走 {@link InetAddress#getAllByName}，与
 *       HttpURLConnection/HttpClient 出站连接同一解析器——十进制（{@code 2130706433}）、
 *       十六进制（{@code 0x7f000001}）等 IP 字面量变体会被两边解析成同一个地址
 *       （2026-09-29 jshell 实证：两者均解析到 127.0.0.1），不存在「校验器看不懂、
 *       连接器连得上」的字面量差异绕过；</li>
 *   <li><b>allowlist 豁免</b>（R184-A 语义）：{@code ai.allowed-hosts} 命中的 host 字符串
 *       等值豁免黑名单（dev/mock 场景走 127.0.0.1）；默认空串 = 严格拒绝，
 *       与 R212 门禁「生产默认空串 ⇒ loopback 仍被拒」双向契约一致。</li>
 * </ul>
 */
public final class EndpointUrlValidator {

    private EndpointUrlValidator() {
    }

    /**
     * B 路（保存入口）全量校验：URL 形态 + allowlist 豁免 + host 黑名单。
     *
     * @param endpointUrl  待校验的完整端点 URL（http/https）
     * @param allowedHosts {@code ai.allowed-hosts} 原始串（逗号分隔；null/空 = 严格模式）
     * @return null = 放行；非空 = 拦截原因（调用方拼进 PARAM_INVALID 定向文案）
     */
    public static String blockReason(String endpointUrl, String allowedHosts) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return "空接口地址";
        }
        URI uri;
        try {
            uri = URI.create(endpointUrl.trim());
        } catch (Exception e) {
            return "URL 无法解析";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "协议必须是 http/https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "缺少主机名";
        }
        if (uri.getUserInfo() != null) {
            // 模型端点不需要 userinfo；拒绝以防「http://内网@外网/」类解析器差异型混淆
            return "禁止 URL 内嵌用户信息";
        }
        if (allowListed(host, allowedHosts)) {
            return null;
        }
        return blockReasonForHost(host);
    }

    /**
     * host 黑名单判定（host = 域名或 IP 字面量）。
     * <p>保存侧语义：<b>解析失败 fail-open</b>（UnknownHostException 返回 null）——保存不依赖
     * DNS 可用性，解析不到的域名交由 C 路出站前 fail-closed（AiChatClient 抛
     * "endpoint host unresolvable"）；一旦解析成功且任一地址落黑名单即拒。</p>
     */
    public static String blockReasonForHost(String host) {
        if (host == null || host.isBlank()) {
            return "空主机名";
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
        for (InetAddress addr : addrs) {
            String reason = blockReasonOf(addr.getAddress());
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /**
     * 字节级判定（fail-closed：null/非法长度一律拒）——{@link AiChatClient} 出站前置唯一实现。
     */
    public static boolean isBlockedIp(byte[] ip) {
        return ip == null || blockReasonOf(ip) != null;
    }

    /**
     * 黑名单核心（IPv4 + IPv6）：
     * <ul>
     *   <li>loopback 127.0.0.0/8、::1；链路本地 169.254.0.0/16（含云元数据 169.254.169.254）、fe80::/10</li>
     *   <li>RFC1918 私网 10/8、172.16/12、192.168/16；fec0::/10（JDK isSiteLocalAddress 语义）</li>
     *   <li>通配 0.0.0.0/8、::；组播 224.0.0.0/4、ff00::/8</li>
     *   <li>CGN 运营商级 NAT 100.64.0.0/10；唯一本地 fc00::/7 ULA（JDK 谓词不覆盖，显式字节断言）</li>
     *   <li>IPv4-mapped IPv6（::ffff:a.b.c.d）解包后按 IPv4 判定，防 mapped 形态绕过</li>
     * </ul>
     */
    static String blockReasonOf(byte[] raw) {
        if (raw == null) {
            return "空地址";
        }
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            raw = new byte[] {raw[12], raw[13], raw[14], raw[15]};
        }
        if (raw.length != 4 && raw.length != 16) {
            return "非法地址";
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            return "非法地址";
        }
        if (addr.isAnyLocalAddress()) return "通配地址";
        if (addr.isLoopbackAddress()) return "loopback";
        if (addr.isLinkLocalAddress()) return "链路本地";
        if (addr.isMulticastAddress()) return "组播";
        if (addr.isSiteLocalAddress()) return "RFC1918 私网";
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 0.0.0.1/8 等：JDK isAnyLocalAddress 只认 0.0.0.0，0/8 其余地址字节级兜住
            if (b0 == 0) return "通配地址";
            // 100.64.0.0/10（运营商级 NAT，CGN）按私网处理
            if (b0 == 100 && (b1 & 0xC0) == 64) return "CGN 私网";
        }
        if (raw.length == 16) {
            // fc00::/7 ULA（fd00::/8 最常见）：JDK isSiteLocalAddress 只认 fec0::/10，显式断言
            if ((raw[0] & 0xFE) == 0xFC) return "唯一本地地址";
            // fe80::/10 显式断言（不依赖 JDK isLinkLocalAddress 语义；抗 JDK 升级漂移）
            if (raw[0] == (byte) 0xFE && (raw[1] & 0xC0) == 0x80) return "链路本地";
        }
        return null;
    }

    /** IPv4-mapped IPv6 判定：::ffff:0:0/96（前 10 字节 0 + 0xFF 0xFF）。 */
    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }

    /** R184-A 语义：host 字符串等值命中 allowlist 即豁免（不接受泛匹配/空串条目）。 */
    private static boolean allowListed(String host, String allowedHosts) {
        if (allowedHosts == null || allowedHosts.isBlank()) {
            return false;
        }
        return Arrays.stream(allowedHosts.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .anyMatch(h -> host.equalsIgnoreCase(h));
    }
}
