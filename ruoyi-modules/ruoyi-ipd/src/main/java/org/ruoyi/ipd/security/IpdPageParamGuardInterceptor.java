package org.ruoyi.ipd.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * P1-3（D 轮 D3 §2.1 真红 → D4 N-7，2026-09-29）：/api/v1/** 分页入参统一校验（B-2 分页契约）。
 *
 * <p>修复前：{@code GET /api/v1/products?pageNum=-1&pageSize=99999} → 200 code=0
 * （非法分页参数被接受并正常返回）；对 stage_actions（17,838 行）/audit_logs（6,552 行）等大表
 * 可构成成本放大 DoS（D3 推论，未在共享环境实跑）。
 *
 * <p>契约（B-2，2026-09-29 本会话拍板）：
 * <ul>
 *   <li>参数名：{@code pageNum|pageNo}（当前页）≥ 1；{@code pageSize}（每页条数）1~200
 *       （判据 @Min(1)/@Max(200)，200 与 AuditLogController/BonusPoolController 既有硬上限对齐）；</li>
 *   <li>域：/api/v1/** 全域——含不消费分页参数的端点（如 products），非法值一律拒绝而非静默忽略；</li>
 *   <li>超限/非法 → IpdBusinessException(PARAM_INVALID) → 400 + code=10001（经 IpdServiceExceptionAdvice）；</li>
 *   <li>未提供分页参数不干预（缺省语义由各端点自定）；空白值视为未提供（与 @RequestParam defaultValue
 *       对空串套用缺省值的绑定语义一致）；</li>
 *   <li>非整数 / Long 溢出同判 PARAM_INVALID，文案只回显固定参数名、不回显用户输入（与
 *       handleTypeMismatch 不回显口径一致）。</li>
 * </ul>
 *
 * <p>注册位置：{@code IpdWebSecurityConfig.addInterceptors}（order HIGHEST+2，登录/注解鉴权之后，
 * 未登录请求先撞 401，不向匿名方泄漏校验行为）。
 */
public class IpdPageParamGuardInterceptor implements HandlerInterceptor {

    /** 每页条数上限（B-2 @Max(200)，与既有 service 侧硬约束同值） */
    public static final int PAGE_SIZE_MAX = 200;

    private static final String[] PAGE_NUM_KEYS = {"pageNum", "pageNo"};

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        for (String key : PAGE_NUM_KEYS) {
            Long pageNum = parseOrNull(key, request.getParameter(key));
            if (pageNum != null && (pageNum < 1 || pageNum > Integer.MAX_VALUE)) {
                throw reject(key + " 必须在 1~" + Integer.MAX_VALUE + " 之间");
            }
        }
        Long pageSize = parseOrNull("pageSize", request.getParameter("pageSize"));
        if (pageSize != null && (pageSize < 1 || pageSize > PAGE_SIZE_MAX)) {
            throw reject("pageSize 必须在 1~" + PAGE_SIZE_MAX + " 之间");
        }
        return true;
    }

    private static Long parseOrNull(String key, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw reject(key + " 必须为整数");
        }
    }

    private static IpdBusinessException reject(String detail) {
        return new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "分页参数非法：" + detail);
    }
}
