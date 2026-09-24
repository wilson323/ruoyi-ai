package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R212-⑦（看板卡 dbe1b6a7 · B 组）：游客进度查询端点限流【复查结论：生产已覆盖，只补装配断言】。
 *
 * <p>现查证据（因此本卡不再引入第二套限流机制，避免过度设计）：
 * <ul>
 *   <li>限流器本体：{@code P411AcceptanceTest#inMemoryRateLimiterWindow} —— 真实
 *       {@code InMemoryHourRateLimiter} 第 11 次拒 + 不同 IP 互不影响</li>
 *   <li>提交侧：{@code P411AcceptanceTest#eleventhSubmitRateLimited} —— 40011 且查库前拦截</li>
 *   <li>查询侧：{@code PortalDemandTraceTest} 反例4 —— 40011 且 never selectOne，
 *       <b>但注入的是 mock 限流器</b>，没有证明生产装配的是真限流器</li>
 *   <li>游客 IP 不可伪造：SEC-REV-05 不信任 X-Forwarded-For，取 servlet
 *       {@code getRemoteAddr()}（{@code P411AcceptanceTest} 已锁契约）</li>
 * </ul>
 *
 * <p>唯一缺口 = 「Spring 生产 4 参 {@code @Autowired} 构造器 → 真实 InMemoryHourRateLimiter
 * → trace 读路径」这条装配链从未被执行过。本类只钉这一条：装配的确实是真限流器，
 * 10/小时额度耗尽后第 11 次 40011→HTTP 429 且不再查库；换 IP 立即恢复（按 IP 分池，非全局熔断）。
 */
@Tag("dev")
@DisplayName("R212-⑦ 游客查询端点：生产装配的真限流器在 trace 路径生效（10/h，第11次 40011→429）")
class R212PublicDemandTraceRateLimitWiringTest {

    private static final String CODE = "AB12CD34";
    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ = new java.util.concurrent.atomic.AtomicInteger(200);

    /** 限流窗口是 JVM 级 static 且不清零 ⇒ 每个用例取独占 TEST-NET-1 IP，杜绝用例间配额互踩（真库不可能同 IP 并发耗尽两次额度）。 */
    private static String freshIp() { return "192.0.2." + IP_SEQ.incrementAndGet(); }

    private RequirementMapper requirementMapper;
    private GuestDemandService service;

    @BeforeAll
    static void initMybatisMeta() {
        // trace 路径会真的构造 LambdaQueryWrapper<Requirement>，纯 Mockito 环境需预置 lambda cache
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "R212-B"),
            Requirement.class);
    }

    @BeforeEach
    void setUp() {
        requirementMapper = mock(RequirementMapper.class);
        IAuditLogService auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        // 关键：Spring 生产装配的就是这个 4 参 @Autowired 构造器，限流器由它自己 new（没有 mock 口子）
        service = new GuestDemandService(requirementMapper, mock(ProductMapper.class),
            mock(ProjectMemberMapper.class), auditLogService);
        // 查无此码 → 50001：限流之后的一步，用它区分「放行到查库」与「被限流挡住」
        when(requirementMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    @DisplayName("正例：生产装配下同一 IP 前 10 次均穿过限流器抵达查库（限流不误伤正常游客）")
    void productionLimiter_allowsFirstTenTraces() {
        String ip = freshIp();
        for (int i = 1; i <= GuestDemandService.RATE_LIMIT_PER_HOUR; i++) {
            final int n = i;
            IpdBusinessException ex = assertThrows(IpdBusinessException.class,
                () -> service.traceByCode(CODE, ip), "第" + n + "次应抵达查库");
            assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode(), "第" + n + "次不得被限流拦截");
        }
        verify(requirementMapper, times(GuestDemandService.RATE_LIMIT_PER_HOUR)).selectOne(any());
    }

    @Test
    @DisplayName("反例：第 11 次 40011→HTTP 429 且不再查库；换 IP 立即可查（按 IP 分池，非全局熔断）")
    void productionLimiter_blocksEleventhTrace() {
        String ip = freshIp();
        for (int i = 0; i < GuestDemandService.RATE_LIMIT_PER_HOUR; i++) {
            assertThrows(IpdBusinessException.class, () -> service.traceByCode(CODE, ip));
        }
        IpdBusinessException blocked = assertThrows(IpdBusinessException.class,
            () -> service.traceByCode(CODE, ip));
        assertEquals(ApiV1ErrorCode.RATE_LIMITED, blocked.getErrorCode());
        assertEquals(429, blocked.getErrorCode().getHttpStatus(), "40011 必须映射 429");
        // 10 次放行 = 10 次查库，第 11 次不增 ⇒ 限流确实挡在查库之前（不被打库）
        verify(requirementMapper, times(GuestDemandService.RATE_LIMIT_PER_HOUR)).selectOne(any());

        String otherIp = freshIp();
        IpdBusinessException reached = assertThrows(IpdBusinessException.class,
            () -> service.traceByCode(CODE, otherIp));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, reached.getErrorCode(), "他 IP 应有独立额度");
        verify(requirementMapper, times(GuestDemandService.RATE_LIMIT_PER_HOUR + 1)).selectOne(any());
    }
}
