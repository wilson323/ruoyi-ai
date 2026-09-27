package org.ruoyi.ipd.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.dto.GuestDemandSubmitReq;
import org.ruoyi.ipd.dto.GuestDemandSubmittedView;
import org.ruoyi.ipd.dto.GuestDemandUpdateReq;
import org.ruoyi.ipd.dto.GuestDemandView;
import org.ruoyi.ipd.dto.PortalDemandTraceView;
import org.ruoyi.ipd.dto.PublicProductView;
import org.ruoyi.ipd.service.GuestDemandService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P4-1.1 需求门户公开端点（免登录；由 IpdWebSecurityConfig 放行 /api/v1/public/**）。
 * <ul>
 *   <li>POST /api/v1/public/demands——游客提交，返回 8 位查询码（页38）。</li>
 *   <li>GET /api/v1/public/products——三情形选择源（在售/在研/其他），仅返回 ACTIVE 产品。</li>
 *   <li>GET /api/v1/public/demands/{code}——凭 8 位查询码查脱敏进度（页39；BR-REQ-09）。</li>
 *   <li>POST /api/v1/public/demands/{code}/supplement——受理前补登 functionalRequirement/contact（页39 用例1；AC-REQ-04；BR-REQ-03a）。</li>
 *   <li>POST /api/v1/public/demands/{code}/withdraw——受理前置 WITHDRAWN 终态撤回（页39 用例2；AC-REQ-04b；BR-REQ-03b）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicPortalController {

    private final GuestDemandService guestDemandService;

    public PublicPortalController(GuestDemandService guestDemandService) {
        this.guestDemandService = guestDemandService;
    }

    @PostMapping("/demands")
    public ApiV1Response<GuestDemandSubmittedView> submit(@Valid @RequestBody GuestDemandSubmitReq req,
                                                          HttpServletRequest http) {
        return ApiV1Response.ok(guestDemandService.submit(req, clientIp(http), http.getHeader("User-Agent")));
    }

    @GetMapping("/products")
    public ApiV1Response<List<PublicProductView>> products() {
        return ApiV1Response.ok(guestDemandService.publicProducts());
    }

    /** 页39：凭 8 位查询码查脱敏进度；同 submit 走 clientIp 限流与审计（不信任 XFF）。 */
    @GetMapping("/demands/{code}")
    public ApiV1Response<PortalDemandTraceView> trace(@PathVariable("code") String code,
                                                      HttpServletRequest http) {
        return ApiV1Response.ok(guestDemandService.traceByCode(code, clientIp(http)));
    }

    /**
     * 页39 用例1：受理前补登 functionalRequirement / contact（AC-REQ-04；BR-REQ-03a 受理后原文锁定）。
     * <p>业务与校验收口在 {@link GuestDemandService#supplement}（action=SUPPLEMENT、字段长度、
     * 状态机 SUBMITTED 门槛、审计），此处仅做 HTTP 接线；@Valid 走 DTO 的 @Size 上限。
     */
    @PostMapping("/demands/{code}/supplement")
    public ApiV1Response<GuestDemandView> supplement(@PathVariable("code") String code,
                                                     @Valid @RequestBody GuestDemandUpdateReq req,
                                                     HttpServletRequest http) {
        return ApiV1Response.ok(guestDemandService.supplement(code, req, clientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 页39 用例2：受理前置 WITHDRAWN 终态撤回（AC-REQ-04b；BR-REQ-03b 仅 SUBMITTED 可撤）。
     * <p>body 与 service 签名对齐：{@link GuestDemandService#withdraw} 强校验 action=WITHDRAW
     * （缺失 body / 空对象 / 错配 action 由 service 统一 PARAM_INVALID 收口），故 body 须为
     * {@code {"action":"WITHDRAW"}}。此处不加 {@code @Valid}：DTO 的 {@code @Size} 约束是补登
     * 字段语义（withdraw 不读 functionalRequirement/contact），加上会拒绝 service 本会忽略的载荷；
     * 且 {@code @RequestBody(required = false)} 让缺 body 以 null 交 service 收口（同 10001）。
     */
    @PostMapping("/demands/{code}/withdraw")
    public ApiV1Response<GuestDemandView> withdraw(@PathVariable("code") String code,
                                                   @RequestBody(required = false) GuestDemandUpdateReq req,
                                                   HttpServletRequest http) {
        return ApiV1Response.ok(guestDemandService.withdraw(code, req, clientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * SEC-REV-05：客户端 IP 仅取 servlet 远端地址，不信任 X-Forwarded-For。
     *
     * <p>原因：公开端点位于 IPD 单企业私有部署，前面没有反向代理。直接信任 XFF
     * 会被任意客户端伪造以绕过限流（10 次/小时）。若日后挂上反向代理，应通过网关
     * 设置专用 token 头或 mTLS 标识信任，再单独接入白名单头。当前仅信任 servlet
     * 远端地址，与 Tomcat/Undertow 的 access_log 字段一致。
     */
    private static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }
}
