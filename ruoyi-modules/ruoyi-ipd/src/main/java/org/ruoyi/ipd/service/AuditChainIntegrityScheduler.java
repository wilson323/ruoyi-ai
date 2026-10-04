package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.service.AuditLogServiceImpl.AnchorVerdict;
import org.ruoyi.ipd.service.AuditLogServiceImpl.AnchoredChainVerifyResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审计链完整性定时自检（2026-10-03 新增，补「验链没有自动调用方」缺口）。
 *
 * <p><b>为什么要有它</b>：验链出口此前唯一真实调用方是
 * {@link org.ruoyi.ipd.controller.AuditLogController#verify()}（超管手工点 HTTP）。
 * 全仓定时任务没有一个调它——链尾被删、哈希被改、整表被清空之后，没人去看就没人会知道。
 * 本类把这双「自动的眼睛」补上：每日跑一次验链，异常落 {@code log.error} + 站内通知在任超管。
 *
 * <p><b>为什么调 {@code verifyChainAnchored()}</b>：它是四个出口里唯一同时给出「行级判据」
 * 与「锚表结论码」的。锚表判据能区分四种形态——删链尾
 * {@link AuditLogServiceImpl#ANCHOR_TRUNCATED} / 链尾被改写
 * {@link AuditLogServiceImpl#ANCHOR_TAIL_REWRITTEN} / 整表被清空
 * {@link AuditLogServiceImpl#ANCHOR_CLEARED} / 锚行缺失
 * {@link AuditLogServiceImpl#ANCHOR_MISSING}；另外三个出口只回一个笼统的四态 verdict
 * （{@code OK / HASH_BROKEN / GAP / BROKEN}），告警里说不清到底是哪一种。
 *
 * <p><b>已知基线噪音的处置（本任务的关键风险）</b>：ADR-0076 登记了 17 处遗留 seq GAP
 * （2026-09-06~09-22 迁移窗口的删行 / 事务回滚 / InnoDB 自增不回填），owner 已按 DEF-9 A 方案
 * 接受为基线事实。自检若把这固定的 17 处当新问题每天报警，这双眼睛会被噪音淹没，等于没有。
 * 故告警判据分三档：
 * <ol>
 *   <li><b>行级哈希断裂：零基线，出现即报</b>。ADR-0076 §1 实测 {@code hashBroken=[]}——
 *       遗留问题全部是 GAP 而非哈希断裂，所以「非空即新」，不需要任何配置；</li>
 *   <li><b>锚表判据：结构性判据，同样零基线</b>。遗留 GAP 是链中段的空洞，既不触碰链尾也不触碰
 *       锚行，故锚表结论码与那 17 处基线天然不相交；</li>
 *   <li><b>seq GAP：只报基线之外的增量</b>。基线取自
 *       {@code ipd.audit.chain-verify.baseline-gaps}，默认值 = ADR-0076 §1 登记的 17 个 seq
 *       （2026-10-03 用 SQL {@code lead()} 在 {@code ipd_dev} 现查复测，17 值逐值一致，
 *       且 {@code hashBroken} 与锚表结论码均未见异常）。</li>
 * </ol>
 *
 * <p><b>为什么不「首次运行自动落基线快照」</b>：自学习基线可以被「先篡改、再重启」绕过——
 * 重启后第一次运行会把被篡改后的状态本身当成基线，且没有任何告警。用固定登记值而非运行时快照，
 * 代价是换库 / 清库后默认基线不匹配会「响亮地多报一次」，换来的是绝不静默地重新基线化。
 *
 * <p><b>会漏报的场景（明确写下，不假装完备）</b>：
 * <ul>
 *   <li><b>删除「已知基线空洞的前一行」</b>：GAP 判据记的是「空洞之后的首行」，若删行后新的
 *       空洞首行 seq 恰好与基线里已有的某个值重合，GAP 集合不变——行级哈希与锚表也都不会变，
 *       因此这一处漏报。影响面窄（只限紧邻那 17 处遗留空洞的行）；链上任何其它位置删行都会
 *       产生基线之外的新 GAP，会被抓到。</li>
 *   <li><b>中段整段「改字段 + 按新值重算哈希」</b>：这属于持有写权限者对全链的完整重写。锚表只能
 *       守链尾（{@code ANCHOR_TAIL_REWRITTEN}）；中段重写要发现，需要链外的可信锚
 *       （例如异地归档的链尾哈希），不在本类能力范围内。</li>
 * </ul>
 *
 * <p><b>只读承诺</b>：本类只做验链读取 + 写通知 outbox，绝不触碰 audit_logs 的
 * INSERT/UPDATE/DELETE（G-02 只追加不变量）。验链异常（如数据库不可用）只落 {@code log.error}
 * 不抛，避免把这个日任务打死。
 *
 * <p><b>调度与开关</b>：每日 03:30（夜间低谷，与 09:00~10:05 晨间家族及每月 1 日 10:00 均不撞），
 * 错峰表已登记于 {@link org.ruoyi.ipd.config.IpdSchedulingConfig}。开关
 * {@code ipd.audit.chain-verify.enabled} 默认 {@code true}——本 job 存在的意义就是无人值守，
 * 默认关等于把「验链没有自动调用方」的缺口原样留着。
 */
@Slf4j
@Component
public class AuditChainIntegrityScheduler {

    /** 通知去重源类型（dedup_key = sourceType:eventType:sourceId:receiverId:day）。 */
    static final String SRC_AUDIT_CHAIN_VERIFY = "audit_chain_verify";

    /** 通知跳转位：审计列表页（超管可自助复验 / 触发重建）。 */
    static final String ACTION_URL = "/ipd/audit-logs";

    /**
     * 已知遗留 GAP 基线默认值 = ADR-0076 §1 登记的 17 个 seq（GAP 判据记的是「空洞后首行」）。
     * 2026-10-03 在 {@code ipd_dev} 用 SQL {@code lead()} 现查复测，与 ADR 逐值一致。
     * 换库 / 清库 / 正式基线变更后须同步改配置，否则会按「基线外新增 GAP」告警（刻意的响亮失败）。
     */
    static final String DEFAULT_BASELINE_GAPS =
        "1889,1892,1901,1903,1906,1910,2469,2473,2475,2477,2479,2481,2486,2550,2554,2559,3074";

    /** 通知去重的 sourceId：本次自检的最细粒度是「一天一条汇总」，故固定 0（同日内不重复轰炸）。 */
    static final long NOTIFY_SOURCE_ID = 0L;

    private final IAuditLogService auditLogService;
    private final PersonMapper personMapper;
    private final NotificationService notificationService;
    private final boolean enabled;
    private final Set<Long> baselineGaps;

    /** 可注入时钟（仿 AuditAnomalyScanScheduler / P0EscalationScanScheduler；ServiceBareClockGuardTest 禁裸时钟）。 */
    private Clock clock = Clock.systemDefaultZone();

    public AuditChainIntegrityScheduler(
            IAuditLogService auditLogService,
            PersonMapper personMapper,
            NotificationService notificationService,
            @Value("${ipd.audit.chain-verify.enabled:true}") boolean enabled,
            @Value("${ipd.audit.chain-verify.baseline-gaps:" + DEFAULT_BASELINE_GAPS + "}") String baselineGaps) {
        this.auditLogService = auditLogService;
        this.personMapper = personMapper;
        this.notificationService = notificationService;
        this.enabled = enabled;
        this.baselineGaps = parseBaseline(baselineGaps);
        log.info("[IPD] 审计链完整性自检装配：enabled={} 基线 GAP {} 个（配置 ipd.audit.chain-verify.*）",
            enabled, this.baselineGaps.size());
    }

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /** 每日 03:30 全链完整性自检（错峰表已登记；只读验链 + 通知 outbox，不碰 audit_logs 写路径）。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void dailyChainIntegrityScan() {
        if (!enabled) {
            return;
        }
        try {
            Finding finding = scanAndNotify(Date.from(clock.instant()));
            if (finding.alarm()) {
                log.error("[IPD] 审计链完整性告警：{} | 断裂 seq={} 基线外新增 GAP={} 锚表结论={} "
                        + "锚尾 seq={} 期望哈希={} / 表内尾 seq={} 实际哈希={} 总行数={}",
                    finding.summary(), finding.hashBroken(), finding.newGaps(), finding.anchor().code(),
                    finding.anchor().anchorLastSeq(), finding.anchor().anchorLastHash(),
                    finding.anchor().tableLastSeq(), finding.anchor().tableLastHash(), finding.total());
            } else {
                log.info("[IPD] 审计链完整性自检通过：总行数={} 锚表结论={} 基线内 GAP={} 处（不计入告警）",
                    finding.total(), finding.anchor().code(), finding.baselineGapCount());
            }
        } catch (Exception e) {
            // 自检自身失败（数据库不可用 / 装配异常）不得打死日任务，也不能被当成「链完好」——
            // 用 error 级别显式留痕，与「验链通过」的 info 在日志级别上可区分。
            log.error("[IPD] 审计链完整性自检执行失败（本轮无结论，不等于链完好）", e);
        }
    }

    /**
     * 单轮自检：验链 → 判定 → 命中则通知在任超管。返回判定结论（供调度日志与验收断言）。
     *
     * <p>通知走 {@link NotificationService#publishDaily}（dedup key 追加自然日）：同日不重发、
     * 次日若问题仍在再提醒一次——即「持续未修的问题每天一条」，而非每次扫描一条。
     */
    public Finding scanAndNotify(Date now) {
        AnchoredChainVerifyResult result = auditLogService.verifyChainAnchored();
        Finding finding = evaluate(result, baselineGaps);
        if (!finding.alarm()) {
            return finding;
        }
        notifySuperAdmins(finding, now);
        return finding;
    }

    /**
     * 告警判定（纯函数，包内可见供单测直喂）：
     * <ol>
     *   <li>行级哈希断裂非空（零基线，出现即新）；</li>
     *   <li>锚表结论码为篡改类（{@code ANCHOR_TRUNCATED / ANCHOR_TAIL_REWRITTEN / ANCHOR_CLEARED}）；</li>
     *   <li>锚行缺失（{@code ANCHOR_MISSING}）——链尾篡改证据不可用，属降级态；
     *       注意行级哈希此时可能全绿，正是「锚表对篡改无效」那类缺陷的形态；</li>
     *   <li>seq GAP 存在基线之外的新值。</li>
     * </ol>
     * 基线内 GAP 一律静默：那是 ADR-0076 已接受为事实的遗留空洞，报它等于制造噪音。
     */
    static Finding evaluate(AnchoredChainVerifyResult result, Set<Long> baselineGaps) {
        List<Long> gaps = result.chain().gaps();
        List<Long> newGaps = gaps.stream()
            .filter(seq -> !baselineGaps.contains(seq))
            .toList();
        // 「命中基线的处数」= 总 GAP 数 − 基线外新增数（不能直接拿 gaps.size()：那会把新增也算进基线）
        int baselineGapCount = gaps.size() - newGaps.size();
        return new Finding(result.chain().hashBroken(), newGaps, result.anchor(),
            result.chain().total(), baselineGapCount);
    }

    /**
     * 一次自检的判定结论。
     *
     * @param hashBroken      行级哈希断裂的 seq（升序）
     * @param newGaps         基线之外的新增 GAP seq（升序）
     * @param anchor          锚表判据快照（含结论码与「期望 / 实际」链尾哈希四元组）
     * @param total           参与校验的总行数
     * @param baselineGapCount 命中基线（已知、不告警）的 GAP 处数，供日志区分「真干净」与「基线内不干净」
     */
    record Finding(List<Long> hashBroken, List<Long> newGaps, AnchorVerdict anchor,
                   int total, int baselineGapCount) {

        /**
         * 是否告警。四类判据任一命中即报（与 {@link #summary()} 同源，改一处必须改另一处）：
         * <ol>
         *   <li><b>锚表判据为篡改类</b>（{@link AnchorVerdict#tampered()}：链尾被删 / 链尾被改写 /
         *       整表被清空）——这三形态恰恰<b>不触碰行级判据</b>，{@code hashBroken} 与 {@code gaps}
         *       全绿，不单列就会静默放过，而它们正是本类存在的理由；</li>
         *   <li>锚行缺失（{@link AuditLogServiceImpl#ANCHOR_MISSING}）——链尾证据不可用，属降级态；
         *       {@code tampered()} 刻意不含它，故此处必须单列；</li>
         *   <li>行级哈希断裂非空（零基线，出现即新）；</li>
         *   <li>基线之外的新增 seq 空洞非空。</li>
         * </ol>
         */
        boolean alarm() {
            return anchor.tampered()
                || AuditLogServiceImpl.ANCHOR_MISSING.equals(anchor.code())
                || !hashBroken.isEmpty()
                || !newGaps.isEmpty();
        }

        /** 一句话归因（日志 / 通知标题共用）。 */
        String summary() {
            List<String> parts = new ArrayList<>();
            if (anchor.tampered()) {
                parts.add("锚表判据=" + anchor.code() + "（链尾被删/被改写/整表被清空）");
            }
            if (AuditLogServiceImpl.ANCHOR_MISSING.equals(anchor.code())) {
                parts.add("锚行缺失（链尾篡改证据不可用，且审计写入会失败）");
            }
            if (!hashBroken.isEmpty()) {
                parts.add("行级哈希断裂 " + hashBroken.size() + " 处");
            }
            if (!newGaps.isEmpty()) {
                parts.add("基线外新增 seq 空洞 " + newGaps.size() + " 处");
            }
            return String.join("、", parts);
        }

        /** 明细（含足以定位的字段；hashBroken 行级的期望/实际哈希需 HTTP 端点逐行复算，本类不重算验链）。 */
        String detail() {
            return "审计链完整性自检（每日 03:30）命中：" + summary() + "。"
                + "断裂 seq=" + hashBroken + "；"
                + "基线外新增 GAP seq=" + newGaps + "；"
                + "锚表结论码=" + anchor.code()
                + "（锚表链尾 seq=" + anchor.anchorLastSeq() + " 期望哈希=" + anchor.anchorLastHash()
                + " / 表内实际链尾 seq=" + anchor.tableLastSeq() + " 实际哈希=" + anchor.tableLastHash() + "）；"
                + "总行数=" + total + "；基线内已知 GAP " + baselineGapCount + " 处（ADR-0076，不计入告警）。"
                + "只报不拦，请人工复核审计流水。";
        }
    }

    /** 命中后通知全体在任超管（无在任超管只 WARN，检测结论照常返回供日志观察）。 */
    private void notifySuperAdmins(Finding finding, Date now) {
        List<Person> admins = activeSuperAdmins();
        if (admins.isEmpty()) {
            log.warn("[IPD] 审计链完整性告警命中但无在任超管可通知（persons 表 SUPER_ADMIN 全不 ACTIVE？）：{}",
                finding.summary());
            return;
        }
        String title = "审计链完整性告警：" + finding.summary();
        String content = finding.detail();
        for (Person admin : admins) {
            notificationService.publishDaily(admin.getId(), NotificationService.Types.AUDIT_CHAIN_BROKEN,
                NotificationService.KIND_FYI, SRC_AUDIT_CHAIN_VERIFY, NOTIFY_SOURCE_ID,
                title, content, ACTION_URL, now);
        }
        log.info("[IPD] 审计链完整性告警已通知超管 {} 人：{}", admins.size(), finding.summary());
    }

    /** 在任超管（与 AuditAnomalyScanService.activeSuperAdmins 同口径：ACTIVE 双状态 + 未软删）。 */
    private List<Person> activeSuperAdmins() {
        return personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getPersonType, "SUPER_ADMIN")
            .eq(Person::getEmploymentStatus, "ACTIVE")
            .eq(Person::getAccountStatus, "ACTIVE")
            .eq(Person::getDelFlag, "0"));
    }

    /** 解析逗号分隔的基线 seq 集合：空串 / null / 非法项一律跳过（配置写错不炸启动，最多多报一次）。 */
    private static Set<Long> parseBaseline(String raw) {
        Set<Long> parsed = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                log.warn("[IPD] 审计链基线 GAP 配置含非法项，已跳过：'{}'", trimmed);
            }
        }
        return parsed;
    }
}
