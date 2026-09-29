package org.ruoyi.ipd.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P1-3（D 轮 D3 §2.1 真红 → D4 N-7，2026-09-29）：分页入参校验回归（B-2 契约）。
 *
 * <ul>
 *   <li>D3 判据重放：{@code GET /api/v1/products?pageNum=-1&pageSize=99999} 修复前 200 code=0，
 *       修复后必须 400/10001（经 IpdBusinessException(PARAM_INVALID) → IpdServiceExceptionAdvice）；</li>
 *   <li>B-2 契约：pageNum|pageNo ≥ 1、pageSize 1~200（@Min(1)/@Max(200)）；</li>
 *   <li>域：不消费分页参数的端点（products）同样拒绝非法值，不静默忽略；</li>
 *   <li>未提供/空白分页参数不干预（缺省语义由端点自定）。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("P1-3 分页入参校验：pageNum|pageNo ≥ 1、pageSize 1~200、超限 400/10001")
class IpdPageParamGuardTest {

    private final IpdPageParamGuardInterceptor guard = new IpdPageParamGuardInterceptor();

    private void run(String uri, String... paramsKv) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        for (int i = 0; i < paramsKv.length; i += 2) {
            request.setParameter(paramsKv[i], paramsKv[i + 1]);
        }
        assertThatCode(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
            .doesNotThrowAnyException();
    }

    private IpdBusinessException runExpectReject(String uri, String... paramsKv) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        for (int i = 0; i < paramsKv.length; i += 2) {
            request.setParameter(paramsKv[i], paramsKv[i + 1]);
        }
        IpdBusinessException ex = catchThrowableOfType(
            () -> guard.preHandle(request, new MockHttpServletResponse(), new Object()),
            IpdBusinessException.class);
        assertThat(ex).as("非法分页参数必须抛 IpdBusinessException").isNotNull();
        return ex;
    }

    // ==================== D3 判据重放 ====================

    @Test
    @DisplayName("D3 N-7 判据重放：/api/v1/products?pageNum=-1&pageSize=99999 → PARAM_INVALID（修复前 200 code=0）")
    void d3NegativeCaseRejected() {
        IpdBusinessException ex = runExpectReject("/api/v1/products", "pageNum", "-1", "pageSize", "99999");
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).contains("分页参数非法");
    }

    // ==================== pageNum / pageNo 边界 ====================

    @Test
    @DisplayName("pageNum=0 / pageNum=-1 拒绝（@Min(1)）")
    void pageNumBelowOneRejected() {
        assertThat(runExpectReject("/api/v1/projects", "pageNum", "0").getMessage()).contains("pageNum");
        assertThat(runExpectReject("/api/v1/projects", "pageNum", "-1").getMessage()).contains("pageNum");
    }

    @Test
    @DisplayName("pageNo 别名同口径：pageNo=-5 拒绝")
    void pageNoAliasRejected() {
        assertThat(runExpectReject("/api/v1/audit-logs", "pageNo", "-5").getMessage()).contains("pageNo");
    }

    @Test
    @DisplayName("pageNum 超 int 范围拒绝（Long 溢出/越界不落绑定层 500）")
    void pageNumOverflowRejected() {
        IpdBusinessException ex = runExpectReject("/api/v1/projects", "pageNum", "99999999999999");
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    // ==================== pageSize 边界 ====================

    @Test
    @DisplayName("pageSize=99999 拒绝（D3 场景）、pageSize=0 拒绝")
    void pageSizeOutOfRangeRejected() {
        assertThat(runExpectReject("/api/v1/audit-logs", "pageSize", "99999").getMessage()).contains("pageSize");
        assertThat(runExpectReject("/api/v1/audit-logs", "pageSize", "0").getMessage()).contains("pageSize");
    }

    @Test
    @DisplayName("边界：pageSize=200 放行（@Max(200) 上限含界），pageSize=201 拒绝")
    void pageSizeBoundary() {
        run("/api/v1/audit-logs", "pageSize", "200");
        assertThat(runExpectReject("/api/v1/audit-logs", "pageSize", "201").getMessage()).contains("1~200");
    }

    @Test
    @DisplayName("非整数拒绝且不回显用户输入（与 handleTypeMismatch 口径一致）")
    void nonIntegerRejectedWithoutEcho() {
        IpdBusinessException ex = runExpectReject("/api/v1/projects", "pageSize", "<script>alert(1)</script>");
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        assertThat(ex.getMessage()).doesNotContain("<script>");
    }

    // ==================== 放行面 ====================

    @Test
    @DisplayName("合法分页放行：pageNum=1&pageSize=20")
    void validPagingPasses() {
        run("/api/v1/projects", "pageNum", "1", "pageSize", "20");
    }

    @Test
    @DisplayName("未提供分页参数不干预（缺省语义由端点自定）")
    void absentParamsPass() {
        run("/api/v1/products", "keyword", "camera");
    }

    @Test
    @DisplayName("空白值视为未提供（与 @RequestParam defaultValue 对空串的绑定语义一致）")
    void blankValuePasses() {
        run("/api/v1/products", "pageNum", "", "pageSize", " ");
    }
}
