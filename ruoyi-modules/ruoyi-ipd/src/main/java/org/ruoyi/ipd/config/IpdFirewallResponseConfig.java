package org.ruoyi.ipd.config;

import cn.dev33.satoken.exception.FirewallCheckException;
import cn.dev33.satoken.strategy.SaFirewallStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.springframework.context.annotation.Configuration;

/**
 * QA-07 缺口③（filter 层）：sa-token 1.44 防火墙对 URL 危险字符（//、%2e、%00 等）的拦截
 * 默认经 SaJakartaServletOperateUtil.writeResult 输出 <b>text/plain 纯文本</b>（HTTP 200），
 * IPD 前端拿到非 JSON 响应无法解析。
 *
 * <p>根因定位：/api/v1/products//1 在进 DispatcherServlet <b>之前</b>即被
 * SaFirewallCheckFilterForJakartaServlet 拦截（SaFirewallCheckHookForPathDangerCharacter
 * 默认危险字符表首项即 //），MVC 层 advice 无法承接，只能在防火墙失败回调上接管。
 *
 * <p>作用域：仅 IPD 域（/api/v1/**）转 400/10001 JSON 包络（固定文案，不回显请求路径）；
 * 其余路径逐字保持 sa-token 默认输出（text/plain + 原始消息），不误伤框架既有通道。
 */
@Slf4j
@Configuration
public class IpdFirewallResponseConfig {

    private static final String IPD_API_PREFIX = "/api/v1/";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void routeFirewallFailToIpdEnvelope() {
        SaFirewallStrategy.instance.checkFailHandle = (e, request, response, handler) -> {
            String path = request.getRequestPath();
            if (path != null && path.startsWith(IPD_API_PREFIX)
                && response.getSource() instanceof HttpServletResponse servletResponse) {
                writeIpdEnvelope(servletResponse, e);
                return;
            }
            // 非 IPD 域：与 sa-token 默认 failFast 行为逐字一致（writeResult：text/plain + print）
            if (response.getSource() instanceof HttpServletResponse servletResponse) {
                try {
                    if (servletResponse.getContentType() == null) {
                        servletResponse.setContentType("text/plain; charset=utf-8");
                    }
                    servletResponse.getWriter().print(e.getMessage());
                    servletResponse.getWriter().flush();
                } catch (Exception ex) {
                    log.error("[IPD] failed to write default firewall response", ex);
                }
            }
        };
        log.info("[IPD] sa-token firewall failHandle routed: /api/v1/** -> 400/10001 JSON envelope");
    }

    private void writeIpdEnvelope(HttpServletResponse servletResponse, FirewallCheckException e) {
        log.warn("[IPD] firewall check rejected (IPD domain): {}", e.getMessage());
        try {
            servletResponse.setStatus(ApiV1ErrorCode.PARAM_INVALID.getHttpStatus());
            servletResponse.setContentType("application/json;charset=utf-8");
            servletResponse.getWriter().print(objectMapper.writeValueAsString(
                ApiV1Response.fail(ApiV1ErrorCode.PARAM_INVALID, "非法请求路径")));
            servletResponse.getWriter().flush();
        } catch (Exception ex) {
            log.error("[IPD] failed to write firewall envelope response", ex);
        }
    }
}
