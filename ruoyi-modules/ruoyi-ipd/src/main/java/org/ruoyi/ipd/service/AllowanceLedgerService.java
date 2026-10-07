package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P3-3.1 月度津贴台账基础额、锁级与 2 倍封顶服务
 *
 * <p>AC：BR-INC-02/03；卡 P3-3 描述。
 * <p>核心规则：
 * <ul>
 *   <li>锁定评级：成员绑定时快照 lockedLevel（L1–L5）</li>
 *   <li>多项目叠加：单项目 baseAmount 累加，finalAmount ≤ baseAmount × capMultiplier（默认 2 倍）</li>
 *   <li>&lt;60 停发 / 无产出 60 天停发：见 P3-3.2（独立卡）</li>
 *   <li>L1–L5 基础额 = allowance.{level}，由 API 配置（与项目绩效得分不互相推导）</li>
 * </ul>
 *
 * <p>P3-3.1 单卡实现：锁定评级校验 + 多项目叠加 + 2 倍封顶 + 草稿。停发逻辑由 P3-3.2 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllowanceLedgerService implements IAllowanceLedgerService {

    /** W4-D：允许 SELECT/COUNT 的 Mapper（之前仅 AllowanceService 注入，本卡补齐）。 */
    private final AllowanceLedgerMapper allowanceLedgerMapper;

    /**
     * R219 卡④（ef20c06a）：auto-scan 的「先生成后计数」编排依赖（可选 setter 注入，
     * 不破既有 1 参构造）。缺失时 autoScan 退化为旧「只计数」行为，存量测试不受影响。
     */
    private AllowanceService allowanceService;

    @Autowired(required = false)
    public void setAllowanceService(AllowanceService allowanceService) {
        this.allowanceService = allowanceService;
    }

    /**
     * A4-63 数据范围过滤的人员-组映射数据源（可选 setter 注入，同上，不破既有 1 参构造）。
     *
     * <p><b>缺失时的退化方向是「更严」不是「更松」</b>：退化为「只看自己」（见
     * {@link #applyDataScope}），绝不退化成全量披露——安全过滤器缺数据源时默认放行，
     * 是本仓反复出现过的失效形态。
     */
    private PersonMapper personMapper;

    @Autowired(required = false)
    public void setPersonMapper(PersonMapper personMapper) {
        this.personMapper = personMapper;
    }


    /** W4-D：period 入参 YYYY-MM 校验，避免 list/pendingStop/autoScan 三个端点接受任意字符串。 */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private static void validateMonth(String period) {
        if (period == null || !MONTH_PATTERN.matcher(period).matches()) {
            throw new IpdBusinessException("period 必须为 YYYY-MM（当前=" + period + "）");
        }
    }

    /** 合法锁定评级 */
    public static final List<String> LOCKED_LEVELS = Arrays.asList("L1", "L2", "L3", "L4", "L5");

    /** 默认封顶倍数（多项目叠加 ≤ 2 倍） */
    public static final BigDecimal DEFAULT_CAP_MULTIPLIER = new BigDecimal("2.0");

    /**
     * 校验锁定评级。
     */
    public static void validateLockedLevel(String lockedLevel) {
        if (lockedLevel == null || !LOCKED_LEVELS.contains(lockedLevel)) {
            throw new IpdBusinessException("锁定评级必须为 L1..L5（当前=" + lockedLevel + "）");
        }
    }

    /**
     * 校验基础额：必须 ≥ 0。
     */
    public static void validateBaseAmount(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException("津贴基础额必须 ≥ 0");
        }
    }

    /**
     * 校验封顶倍数：必须 ≥ 1（默认 2.0）。
     */
    public static void validateCapMultiplier(BigDecimal capMultiplier) {
        if (capMultiplier == null) {
            return;
        }
        if (capMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IpdBusinessException("封顶倍数必须 ≥ 1.0");
        }
    }

    /**
     * P3-3.1 多项目叠加 2 倍封顶计算。
     * <pre>
     *   finalAmount = min(Σ baseAmount[i], baseAmount × capMultiplier)
     *   capApplied  = "1" if 触发封顶 else "0"
     * </pre>
     * 其中 baseAmount = 单项目基础额（锁定评级对应），capMultiplier 默认 2.0。
     *
     * @param draft          津贴台账草稿（就地填充 finalAmount 与 capApplied）
     * @param baseAmountList 单项目基础额列表（同一人员的不同项目）
     * @param capMultiplier  封顶倍数（可为 null，默认 2.0）
     * @return AllowanceLedger 草稿（finalAmount + capApplied 已填充）
     */
    public AllowanceLedger calcFinalAmount(AllowanceLedger draft,
                                           List<BigDecimal> baseAmountList,
                                           BigDecimal capMultiplier) {
        if (draft == null) {
            throw new IpdBusinessException("津贴草稿不能为空");
        }
        validateLockedLevel(draft.getLockedLevel());
        validateCapMultiplier(capMultiplier);

        BigDecimal cap = capMultiplier == null ? DEFAULT_CAP_MULTIPLIER : capMultiplier;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal b : baseAmountList) {
            validateBaseAmount(b);
            sum = sum.add(b);
        }
        BigDecimal capLine = baseAmountList.isEmpty() ? BigDecimal.ZERO
            : baseAmountList.get(0).multiply(cap);
        BigDecimal finalAmount = sum.min(capLine).setScale(2, RoundingMode.HALF_UP);

        // 恰好等于 capLine 不触发封顶（仅超额触发）
        String capApplied = sum.compareTo(capLine) > 0 ? "1" : "0";

        draft.setBaseAmount(baseAmountList.isEmpty() ? BigDecimal.ZERO : baseAmountList.get(0));
        draft.setFinalAmount(finalAmount);
        draft.setCapApplied(capApplied);
        log.debug("津贴封顶计算 personId={} sum={} capLine={} final={} capApplied={}",
            draft.getPersonId(), sum, capLine, finalAmount, capApplied);
        return draft;
    }

    /**
     * 草稿录入：绑定时锁定评级。
     */
    @Transactional(rollbackFor = Exception.class)
    public AllowanceLedger draftBinding(AllowanceLedger draft) {
        validateLockedLevel(draft.getLockedLevel());
        validateBaseAmount(draft.getBaseAmount());
        draft.setCapApplied("0");
        draft.setFinalAmount(draft.getBaseAmount());
        return draft;
    }

    // ============================================================
    // W4-D 件 2：list / pendingStop / autoScan 三方法（前端 allowance.ts 调用配套）
    // ============================================================

    /**
     * W5-E-2.3 件 1.1：actor 入口校验——service 层不信任 controller 必传（防御性兜底）。
     * actor == null 或 actor.id() == null → UNAUTHORIZED。
     */
    private static void requireAuthenticated(IpdActor actor) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    /** 超管角色字面量（与 IpdIdorGuard.requireSuperAdmin 同名常量同值，本类不引 security 包常量以免循环依赖）。 */
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * A4-63：津贴台账数据范围过滤。
     *
     * <p><b>缺口</b>：{@link #list} / {@link #pendingStop} 修复 W5-E-2.3 的 IDOR 时只补了
     * 「actor 非空」这一道，personId 为 null 时仍返回<b>该月全公司津贴明细</b>——
     * 任意一个双 PM 都能拉到全公司所有人的津贴金额。四角色都挂在读码
     * {@code ipd:kpi:query} 上，等于没有任何行级隔离。
     *
     * <p><b>口径</b>：超管全量（与 autoScan/requireAdmin 的既有口径一致）；
     * 其余角色可见集合 = 「自己」∪「与自己同组的人（{@code persons.group_id}）」。
     * 取组口径对齐 {@code IpdPermission.canAccessGroup}／{@code IpdPermissionCode} 注释里的
     * 「本组/空组/跨组」语义：不按项目主组反查——津贴台账的行归属人是<b>津贴领取人</b>，
     * 财务口径上「谁能看谁的工资」按人员所属组判定，与项目组是两套归属。
     *
     * <p><b>与调用方既有 personId 条件的关系</b>：本方法是 AND 追加，不是覆盖。
     * 调用方按 personId 精确查时，跨组 personId 会落到空集（返回空列表而非 403）——
     * 这是数据范围过滤的常规语义（不泄漏「该人是否存在」），与
     * {@code KpiSharedCollectionService.listSharedKpis} 的单项目 403 口径不同，
     * 因为本端点是<b>跨项目列表</b>，没有单一 projectId 可供鉴权。
     *
     * <p><b>退化方向</b>：{@code personMapper} 未装配 / {@code actor.groupId()} 为空
     * ⇒ 一律收敛到「只看自己」。安全过滤器在数据源缺失时默认放行是本仓已知失效形态，
     * 故此处显式取严。
     */
    private void applyDataScope(IpdActor actor, LambdaQueryWrapper<AllowanceLedger> q) {
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return; // 超管全量：不加任何 personId 约束
        }
        Set<Long> visible = new LinkedHashSet<>();
        visible.add(actor.id()); // requireAuthenticated 已保证 actor.id() 非空 ⇒ visible 永不为空集
        Long actorGroupId = actor.groupId();
        if (actorGroupId == null) {
            log.debug("津贴数据范围：actorId={} 无 groupId ⇒ 仅本人可见", actor.id());
        } else if (personMapper == null) {
            log.warn("津贴数据范围：PersonMapper 未装配 ⇒ 退化为仅本人可见（不放开为全量）");
        } else {
            List<Person> sameGroup = personMapper.selectList(
                new LambdaQueryWrapper<Person>().eq(Person::getGroupId, actorGroupId));
            if (sameGroup != null) {
                for (Person p : sameGroup) {
                    if (p != null && p.getId() != null) {
                        visible.add(p.getId());
                    }
                }
            }
        }
        q.in(AllowanceLedger::getPersonId, visible);
    }

    /**
     * W4-D 件 2 §1：月度津贴快照列表（按 period 必填 + 可选 personId 过滤）。
     * 端点 GET /api/v1/allowance/ledger 配套服务方法。
     *
     * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数，service 层入口校验 actor 非空（UNAUTHORIZED），
     * 消除「无 actor 可披露全公司津贴明细」IDOR 缺口。读操作范围保持宽松：
     * MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN 四角色由 Controller
     * {@code @SaCheckPermission(OPERATION_KPI_QUERY)} + {@code requireInternal()} 双重守门（件 1.5）。
     *
     * <p>A4-63 数据范围过滤：非超管只看「自己 + 自己组」，见 {@link #applyDataScope}。
     * 本方法此前只校验 actor 非空，personId 传 null 时返回该月<b>全公司</b>津贴明细。
     *
     * <p>排序：项目 ID 升序、人员 ID 升序；软删除（delFlag=0）由 MyBatis-Plus {@code @TableLogic} 自动过滤。
     * <p>DTO 字段对齐留作 W4-D' 单独任务；当前先以 AllowanceLedger 原样返回，前端 type 与后端 domain 字段名差异由前端适配层兜底。
     *
     * @param actor   当前会话身份（必填，由 Controller {@code permission.requireInternal()} 传入）
     * @param period  YYYY-MM（必填，违反格式抛 IpdBusinessException）
     * @param personId 可选；为 null 时返回该月全员记录
     * @return AllowanceLedger 列表（可能为空但不会为 null）
     */
    public List<AllowanceLedger> list(IpdActor actor, String period, Long personId) {
        requireAuthenticated(actor);
        validateMonth(period);
        LambdaQueryWrapper<AllowanceLedger> q = new LambdaQueryWrapper<>();
        q.eq(AllowanceLedger::getMonth, period);
        if (personId != null) {
            q.eq(AllowanceLedger::getPersonId, personId);
        }
        applyDataScope(actor, q);
        q.orderByAsc(AllowanceLedger::getProjectId, AllowanceLedger::getPersonId);
        List<AllowanceLedger> rows = allowanceLedgerMapper.selectList(q);
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * W4-D 件 2 §2：待停发津贴列表（按 period 过滤；stopReason IS NOT NULL）。
     * 端点 GET /api/v1/allowance/pending-stop 配套服务方法。
     *
     * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数，service 层入口校验 actor 非空（UNAUTHORIZED），
     * 消除「无 actor 可披露全公司停发明细」IDOR 缺口。读操作范围与 {@link #list} 同款
     * （四角色由 Controller 双重守门，件 1.5）。
     *
     * <p>A4-63 数据范围过滤：同 {@link #list}，非超管只看「自己 + 自己组」，
     * 见 {@link #applyDataScope}。停发原因属薪酬敏感信息，跨组披露风险高于快照列表。
     *
     * <p>判定：{@code stopReason} 非空即视为「待停发」（P3-3.2 触发：SCORE_BELOW_60 / NO_OUTPUT_60_DAYS）。
     * <p>不输出已经 freeze 的台账（{@code finalAmount=0} 且 {@code stopReason} 非空 ⇒ 已停发确认）。
     *
     * @param actor  当前会话身份（必填，由 Controller {@code permission.requireInternal()} 传入）
     * @param period YYYY-MM
     * @return 停发原因非空的 AllowanceLedger 列表
     */
    public List<AllowanceLedger> pendingStop(IpdActor actor, String period) {
        requireAuthenticated(actor);
        validateMonth(period);
        LambdaQueryWrapper<AllowanceLedger> q = new LambdaQueryWrapper<>();
        q.eq(AllowanceLedger::getMonth, period)
            .isNotNull(AllowanceLedger::getStopReason);
        applyDataScope(actor, q);
        q.orderByAsc(AllowanceLedger::getProjectId, AllowanceLedger::getPersonId);
        List<AllowanceLedger> rows = allowanceLedgerMapper.selectList(q);
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * 确认待停发台账：终额置 0。<b>仅超管</b>。已是 0 时原样返回。
     *
     * <p>A4-60 收紧：原口径为 {@code GROUP_LEADER | SUPER_ADMIN}。收紧理由与
     * {@code IpdRolePermissionCatalog} 的 {@code DELETION_FIRST_REVIEW} 拆集同款——
     * 「把一笔钱减到 0」是终局性的资金动作，不应与「发现该停发」的日常职责同层；
     * 双 PM 之外，组长在本仓其他域确有合法写权（负反馈认定、贡献度确认），
     * 但那些都不直接改 final_amount。同时与本类 {@link #autoScan}（同属津贴资金域，
     * 原本就是「仅超管」）对齐，两层口径一致。
     *
     * <p><b>行为变更</b>：产品组长调用本方法由 200 变 403 FORBIDDEN。
     *
     * <p><b>未完成部分（需 owner 批 allowlist 扩权后另卡接）</b>：本端点目前仍挂读码
     * {@code ipd:kpi:query}（见 {@code AllowanceLedgerController#confirmStop}），
     * 专用权限码 {@code ipd:allowance:confirm-stop} 需要同时改
     * {@code IpdPermissionCode}（常量唯一真源）与 Controller 注解，
     * 并登记进 {@code IpdRolePermissionCatalog.ADMIN_WRITE}。
     * 在那之前，<b>实际生效的门禁是本方法体的超管判定，不是权限码</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public AllowanceLedger confirmStop(IpdActor actor, Long ledgerId) {
        requireAuthenticated(actor);
        if (!ROLE_SUPER_ADMIN.equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅超管可确认停发");
        }
        AllowanceLedger row = allowanceLedgerMapper.selectById(ledgerId);
        if (row == null || row.getStopReason() == null || row.getStopReason().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "待停发台账不存在");
        }
        if (row.getFinalAmount() != null && BigDecimal.ZERO.compareTo(row.getFinalAmount()) == 0) {
            return row;
        }
        row.setFinalAmount(BigDecimal.ZERO);
        allowanceLedgerMapper.updateById(row);
        return row;
    }

    /**
     * W4-D 件 2 §3：月度自动扫描（按 period；返回当月所有台账记录数）。
     * 端点 POST /api/v1/allowance/auto-scan 配套服务方法（仅超管）。
     *
     * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数 + service 层两重守卫——
     * actor 非空（UNAUTHORIZED）与仅 SUPER_ADMIN（FORBIDDEN）。原注释明言「仅超管」但守卫只在
     * Controller（W4-D {@code requireAdmin()}），service 层裸奔（W5-E P0 #3 原话「controller 决定」）；
     * 此处方法内兜底与 Controller 注解/requireAdmin 同严，防资金域操作失防（与 BonusPool
     * compute/freeze/distribute 方法内兜底同款治理，SEC-06）。
     *
     * <p>当前为「扫描 + 计数」简单委派实现，复杂扫描（绩效分 <60 / 60 天无产出判定）留待后续任务，调用方 AllowanceService.determineStopReasonP332 已就绪。
     *
     * @param actor  当前会话身份（必填，须 SUPER_ADMIN；由 Controller {@code permission.requireAdmin()} 传入）
     * @param period YYYY-MM
     * @return 当月 AllowanceLedger 行数（Int 范围；>2^31 抛 IpdBusinessException）
     */
    public Integer autoScan(IpdActor actor, String period) {
        requireAuthenticated(actor);
        if (!ROLE_SUPER_ADMIN.equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅超管可执行月度扫描");
        }
        validateMonth(period);
        // R219 卡④：先全量生成当月台账（幂等，已成账不重复），再计数——补齐长期
        // 「只 selectCount、无任何生产者」的零触发缺口；生成失败直接抛出，不静默降级。
        if (allowanceService != null) {
            int created = allowanceService.generateMonthlyLedgers(period);
            log.info("autoScan: generateMonthlyLedgers period={} created={}", period, created);
        }
        LambdaQueryWrapper<AllowanceLedger> q = new LambdaQueryWrapper<>();
        q.eq(AllowanceLedger::getMonth, period);
        long cnt = allowanceLedgerMapper.selectCount(q);
        if (cnt > Integer.MAX_VALUE) {
            throw new IpdBusinessException("扫描结果超过 Integer.MAX_VALUE，请分页处理（cnt=" + cnt + "）");
        }
        return (int) cnt;
    }
}
