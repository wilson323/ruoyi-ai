package org.ruoyi.ipd.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** 出站端点安全校验与脱敏指纹；实际模型调用统一由 AiGateway 使用 AgentScope 执行。 */
@Slf4j
@Component
public class AiChatClient {
    private final String allowedHosts;

    public AiChatClient(@Value("${ai.allowed-hosts:}") String allowedHosts) {
        this.allowedHosts = allowedHosts == null ? "" : allowedHosts;
    }

    /**
     * AI-STRAT-2：SSRF 校验复用口（package-private）——AiGateway 发起 AgentScope 调用前
     * 必须过本方法（与原生成链同一道防御，含 DNS rebinding 双解析 + allowlist）。
     * 校验失败抛 IpdBusinessException（与原链同语义，由 controller 层统一处理）。
     */
    void ssrfCheck(String baseUrl) {
        validateEndpoint(baseUrl);
    }

    /**
     * SSRF 防御（SEC P1-3/P1-15 + R-NEW S-6）：
     * <ol>
     *   <li>解析 host →拿到首个 InetAddress → 字节级黑名单（含 IPv6 fe80::/10 显式断言）</li>
     *   <li><b>DNS rebinding 防御</b>：再解析一次 → 与首次结果比对；任一 IP 落入黑名单或两次解析不一致 → 拒。
     *       Why：JVM 的 networkaddress.cache.ttl 默认 30s（成功）/10s（失败）；攻击者把 TTL=0，
     *       首解析 1.2.3.4 公网放行 → 缓存过期重解析到 fe80:: → 不双解析即被绕过。</li>
     *   <li>allowlist 留空时仅做内网黑名单（向后兼容）</li>
     * </ol>
     */
    private void validateEndpoint(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (host == null) {
            throw new IpdBusinessException("endpoint host missing");
        }
        // R184-A（2026-09-23）：allowlist 优先检查——本机 mock/集成场景需走 127.0.0.1，
        // 避免黑名单一票否决；allowlist 命中后跳过黑名单 + DNS rebinding 检查，
        // 但仍保留 host 字面量记录（不允许泛匹配如 “.” 或空字符串）。
        // 安全契约：不接受「公网域名返回 127.0.0.1」这类情形——allowlist 走 host 字符串等值，
        // DNS rebinding 攻击者必须控制 allowlist 域名本身才能利用，等于「他已拿到合法控制权」。
        String allowList = allowedHosts.trim();
        if (!allowList.isEmpty()) {
            boolean inAllow = Arrays.stream(allowList.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(h -> host.equalsIgnoreCase(h));
            if (inAllow) {
                log.warn("[AI] SSRF allowlist hit host={} (skip blacklist+DNS rebinding; dev/mock only)", host);
                return;
            }
        }
        InetAddress[] firstAddrs;
        InetAddress[] secondAddrs;
        try {
            firstAddrs  = InetAddress.getAllByName(host);
            secondAddrs = InetAddress.getAllByName(host); // S-6 增量：二次解析防 DNS rebinding
        } catch (UnknownHostException e) {
            throw new IpdBusinessException("endpoint host unresolvable: " + host);
        }
        // S-6 增量：任一解析序列含黑名单 IP 即拒
        for (InetAddress a : firstAddrs) {
            if (isBlockedIp(a.getAddress())) {
                log.warn("[AI] SSRF blocked endpoint host={} ip={} (first resolve)", host, a.getHostAddress());
                throw new IpdBusinessException("SSRF blocked: private/loopback/link-local endpoint " + host);
            }
        }
        for (InetAddress a : secondAddrs) {
            if (isBlockedIp(a.getAddress())) {
                log.warn("[AI] SSRF blocked endpoint host={} ip={} (second resolve - DNS rebinding)", host, a.getHostAddress());
                throw new IpdBusinessException("SSRF blocked: private/loopback/link-local endpoint " + host);
            }
        }
        // S-6 增量：DNS rebinding 防御——两次解析 IP 集合不相等即拒
        if (!ipSet(firstAddrs).equals(ipSet(secondAddrs))) {
            log.warn("[AI] SSRF DNS-rebinding suspected host={} first={} second={}", host, ipSet(firstAddrs), ipSet(secondAddrs));
            throw new IpdBusinessException("SSRF blocked: DNS rebinding suspected for " + host);
        }
        if (!allowList.isEmpty()) {
            boolean ok = Arrays.stream(allowList.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(h -> host.equalsIgnoreCase(h) || host.endsWith("." + h));
            if (!ok) {
                log.warn("[AI] host not in allowlist host={}", host);
                throw new IpdBusinessException("host not in allowlist: " + host);
            }
        }
    }

    /** S-6 增量 helper：InetAddress[] → IP 字符串集合（去重）。 */
    private static Set<String> ipSet(InetAddress[] addrs) {
        Set<String> set = new HashSet<>(addrs.length * 2);
        for (InetAddress a : addrs) set.add(a.getHostAddress());
        return set;
    }

    /**
     * 内网 / loopback / link-local IP 黑名单，覆盖 IPv4 + IPv6（含 fe80::/10 显式断言）。
     * P0-3（2026-09-29）：判定委托 {@link EndpointUrlValidator#isBlockedIp} 唯一实现
     * （黑名单不复制——保存入口 B 路与出站 C 路同一份字节级判定，防两套名单漂移）。
     */
    private static boolean isBlockedIp(byte[] ip) {
        return EndpointUrlValidator.isBlockedIp(ip);
    }

    /** SHA-256 短指纹（16 hex chars ≈ 64 bit），用于日志中请求/响应配对，不暴露原文。 */
    static String shortHash(String s) {
        if (s == null || s.isEmpty()) return "0";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "na";
        }
    }
}
