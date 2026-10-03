package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditChainHead;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AuditChainVerifyResult;
import org.ruoyi.ipd.mapper.AuditChainHeadMapper;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.util.AuditHashChain;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 审计日志服务（只追加 + hash 链，v3 TS-08；AC-AUD-01 篡改可检）
 * ⚠️ 只追加：业务路径不提供任何 update/delete（G-02 配套证据链）。唯一例外：
 * {@link #rebuildChain()} 修复工具（DEF-4）——仅重算哈希列、业务字段只读，超管专属且动作本身落审计。
 *
 * <p>DEF-4 链自洽三修复（2026-09-05）：
 * <ol>
 *   <li>时间戳秒级对称：create_time 列为 datetime(0)，MySQL 对其毫秒是「四舍五入」（≥.500
 *       进位到下一秒）而哈希入参是截断——因此写库前必须先把 {@code createTime} 毫秒归零，
 *       否则约半数新行读回时间 +1s → 重算哈希失配（实测 seq=406/407 断裂）；</li>
 *   <li>verifyChain 升序遍历＋锚定库内首行（原实现误用降序 wrapper 且硬编码 GENESIS/seq=1，
 *       结构性全行断判）；</li>
 *   <li>append 竞态防护（2026-09-05 晚升级为 ①②③ P 变体，owner 拍板）：由 uk_audit_seq
 *       冲突自愈重试改为 audit_log_chain_heads 单行锚悲观锁原子分配——SELECT ... FOR UPDATE
 *       锁 GLOBAL 锚行 → seq=next_seq、prevHash=last_hash → advance 前移锚行 → insert
 *      （NEVER 已去，seq 显式入 INSERT）。全局串行、零重试零 CAS 竞态；DEF-4「禁锁定读」指
 *       旧 audit_logs 路径，chain_heads 表级 SELECT,UPDATE 已授、锁定读合法（Q6 REVOKE 后亦然）。
 *       旧重试循环/catch(DuplicateKeyException)/selectLast()/orderBySeq() 已删；陈旧 seed 撞
 *       uk 时响亮失败不自愈（防线 = 停写窗口 sync-seed + 部署后 verifyChain 冒烟）。</li>
 * </ol>
 *
 * <p>DEF-6 载荷列往返对称（2026-09-05，owner 选定方案 A + 护栏配套）：
 * {@code before_data/after_data} 原为 MySQL {@code json} 列，读回时被服务端规范化渲染
 * （键排序按 UTF-8 字节长度→字典序、成员间插 {@code ", "}、{@code 1e3}→{@code 1000.0}），
 * 与写入侧用于算 {@code curr_hash} 的 Jackson 紧凑串永不相等 → 带载荷审计行写完即被
 * {@link #verifyChain()} 判断裂（实证 seq 466/485/502，断裂行 100% 携带载荷）。
 * 修复 = DDL 把两列改 {@code longtext}（{@code docs/script/sql/update/2026-09-05-ipd-audit-payload-longtext.sql}）
 * 保字节精确往返，不动已冻结哈希协议 v1。代价：{@code json} 列类型原本同时是 DEF-1 的 DB 层
 * fail-fast 护栏，故 {@link #append} 入口补上 {@link AuditEventData#requireJson} 应用层校验，
 * 非法载荷仍立即抛出并回滚（校验必须在重试循环之外——它是 {@code DuplicateKeyException} 的父类）。
 *
 * <p>P0-5.4 补范围查询/导出：{@link #listByOperatorIds} / {@link #countByOperatorIds}
 * 均为「接受预先解析好的 operatorIds」——角色→范围（本人/本组/全局）的判定由 Controller 层
 * 依据 {@code IpdPermission}（SEC-02）与 {@code PersonMapper}（本组人员）完成，Service 不感知角色。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements IAuditLogService {

    /** ①②③：审计链分配器锚行键（audit_log_chain_heads 单行 GLOBAL）。 */
    private static final String CHAIN_KEY_GLOBAL = "GLOBAL";

    private final AuditLogMapper auditLogMapper;
    private final AuditChainHeadMapper chainHeadMapper;
    private final PersonMapper personMapper;

    // ==================== 锚表判据（SEC-AUD-01 补：验链必须读锚行）====================
    // 缺陷（2026-10-03）：三个验链出口（verifyChain / verifyChainStrict / verifyChainDetailed）
    // 共用 verifyChainDetailed 的邻接哈希实现，全程不读 audit_log_chain_heads。锚表只在
    // append / rebuildChain 被维护，验链侧一次都没读过 ⇒ 锚表对篡改完全无效，两类攻击可
    // 静默通过「链完整」：
    //   ① 删链尾 N 行：剩余行首尾仍邻接自洽（首行 prev 无前驱可验、末行 curr 无后继可验）；
    //   ② 整表重排 / 尾部改写：攻击者按「prev=前驱 curr」重算全表哈希后逐行仍自洽。
    // 修复 = 验链时同一次调用内读锚行，断言「锚表记录的链尾 == 表内实际链尾」。
    // 事务语义（读取侧）：append 的锚 advance 与 audit_logs insert 在同一事务内提交，
    // InnoDB MVCC 保证验链只可能看到「行与锚都已提交」或「两者都未提交」，读不到未提交锚。

    /** 锚表判据结论码：锚行缺失，无法提供链尾证据（退化为邻接自洽，不编造断裂行）。 */
    public static final String ANCHOR_MISSING = "ANCHOR_MISSING";
    /** 锚表判据结论码：锚行存在但 last_seq 为空/0，且表内为空——空库病态，两侧一致故判通过。 */
    public static final String ANCHOR_OK = "ANCHOR_OK";
    /** 锚表判据结论码：锚表链尾 seq 大于表内实际链尾 seq——链尾被删（DELETE TAIL）。 */
    public static final String ANCHOR_TRUNCATED = "ANCHOR_TRUNCATED";
    /**
     * 锚表判据结论码：表内链尾与锚表链尾不符——链尾被改写 / 整表重排 / 绕过 append 直写表。
     * 两种形态：同 seq 不同 curr_hash，或表内链尾 seq 已超过锚表（锚被回退或未随写入推进）。
     */
    public static final String ANCHOR_TAIL_REWRITTEN = "ANCHOR_TAIL_REWRITTEN";
    /** 锚表判据结论码：表内一行不剩而锚表仍记录着链——整表被清空。 */
    public static final String ANCHOR_CLEARED = "ANCHOR_CLEARED";

    /**
     * 锚表判据快照（可区分两类篡改的结论码 + 证据四元组）。
     *
     * @param code           上列结论码之一
     * @param anchorLastSeq  锚表 last_seq（锚行缺失时 null）
     * @param anchorLastHash 锚表 last_hash（锚行缺失时 null）
     * @param tableLastSeq   表内实际最大 seq（表空时 null）
     * @param tableLastHash  表内实际最后一行的 curr_hash（表空时 null）
     */
    public record AnchorVerdict(String code, Long anchorLastSeq, String anchorLastHash,
                                Long tableLastSeq, String tableLastHash) {
        /** 锚证据与表内链尾是否一致（仅 {@link #ANCHOR_OK} 为真；{@code ANCHOR_MISSING} 不算一致，
         *  但也不构成篡改证据——见 {@link #tampered()}）。 */
        public boolean ok() {
            return ANCHOR_OK.equals(code);
        }

        /** 锚表是否给出了「篡改」结论（截断 / 链尾被改写 / 整表被清空）。 */
        public boolean tampered() {
            return ANCHOR_TRUNCATED.equals(code)
                || ANCHOR_TAIL_REWRITTEN.equals(code)
                || ANCHOR_CLEARED.equals(code);
        }
    }

    /**
     * 带锚表判据的验链结果：行级判据（沿用 {@link AuditChainVerifyResult} 三条判据）
     * ＋ 锚表判据（链尾一致性）。{@link #verdict()} 优先返回锚表结论码，使
     * 「删链尾」与「整表重排」在结论上可区分，而非都退化成一个笼统的 BROKEN。
     */
    public record AnchoredChainVerifyResult(AuditChainVerifyResult chain, AnchorVerdict anchor) {
        /** 行级判据结论（OK / HASH_BROKEN / GAP / BROKEN）。 */
        public String chainVerdict() {
            return chain.verdict();
        }

        /**
         * 合并结论：锚表判据优先（锚给出篡改结论 ⇒ 一定是篡改，行级判据此时可能全绿——
         * 这正是本缺陷的形态）；锚一致或锚缺失（无证据、非篡改）时退回行级三态结论。
         */
        public String verdict() {
            return anchor.tampered() ? anchor.code() : chain.verdict();
        }

        /** 断裂行（行级 hashBroken + 锚不一致指向的链尾位，去重升序）。 */
        public List<Long> mergedBroken() {
            List<Long> merged = new ArrayList<>(chain.hashBroken());
            merged.addAll(anchorBrokenSeqs(anchor));
            return merged.stream().distinct().sorted().toList();
        }
    }

    /**
     * 锚表判据（SEC-AUD-01 补，2026-10-03）：锚表记录的链尾必须等于表内实际链尾。
     *
     * <p>注意「结尾方向」——判据不是「相等即通过」：
     * <ul>
     *   <li>锚 seq &gt; 表内最大 seq ⇒ {@code ANCHOR_TRUNCATED}。删链尾时锚表保留的是
     *       <b>已不存在的旧尾巴</b>，其 seq 指向表内查无此行的位置，这是删尾的唯一指纹；</li>
     *   <li>锚 seq == 表内最大 seq 但 last_hash != 链尾 curr_hash ⇒ {@code ANCHOR_TAIL_REWRITTEN}
     *       （尾部被改写 / 整表重排后重算哈希）；</li>
     *   <li>表内最大 seq &gt; 锚 seq ⇒ 同为 {@code ANCHOR_TAIL_REWRITTEN}（绕开 append 直写表、
     *       或锚行被回拨）；</li>
     *   <li>表空而锚有链 ⇒ {@code ANCHOR_CLEARED}（整表被清空）。</li>
     * </ul>
     *
     * <p>与 {@link #rebuildChain()} 的关系：rebuild 按表内数据重算并把锚推到表内链尾，
     * 故 rebuild 之后锚与表重新一致、验链判通过（本判据每次都现读锚与表，逻辑一致）。
     * 反过来说 rebuild 是超管修复工具，能把「已被篡改的表」重新洗成自洽——本判据负责在
     * rebuild <b>之前</b>把篡改喊出来，rebuild 的洗白属性属设计取舍，不由本处改动。
     */
    private AnchorVerdict checkAnchor(List<AuditLog> all) {
        // mapper 缺失只可能出现在未注入的单元测试/裁剪装配里，容错退化为「无锚证据」，不抛 NPE
        AuditChainHead head = chainHeadMapper == null ? null : chainHeadMapper.selectById(CHAIN_KEY_GLOBAL);
        Long anchorSeq = head == null ? null : head.getLastSeq();
        String anchorHash = head == null ? null : head.getLastHash();
        AuditLog tail = all.isEmpty() ? null : all.get(all.size() - 1);
        Long tableSeq = tail == null ? null : tail.getSeq();
        String tableHash = tail == null ? null : tail.getCurrHash();
        // 锚行缺失（未 sync-seed / 被清）：无链尾证据可断言，退化为邻接自洽判据，不编造断裂行
        if (head == null || anchorSeq == null) {
            return new AnchorVerdict(ANCHOR_MISSING, anchorSeq, anchorHash, tableSeq, tableHash);
        }
        if (all.isEmpty()) {
            // 表空：锚 last_seq=0（未写过）算空链一致；锚有链尾则表被整表清空
            return new AnchorVerdict(anchorSeq <= 0L ? ANCHOR_OK : ANCHOR_CLEARED,
                anchorSeq, anchorHash, null, null);
        }
        if (anchorSeq > tableSeq) {
            // 锚指向表内不存在的 seq —— 链尾被删（DELETE TAIL）的指纹
            return new AnchorVerdict(ANCHOR_TRUNCATED, anchorSeq, anchorHash, tableSeq, tableHash);
        }
        if (anchorSeq < tableSeq) {
            // 表比锚还长：绕开 append 直写表，或锚行被回拨（两者都算链尾被改写）
            return new AnchorVerdict(ANCHOR_TAIL_REWRITTEN, anchorSeq, anchorHash, tableSeq, tableHash);
        }
        boolean hashMatch = nvl(anchorHash).equals(nvl(tableHash));
        return new AnchorVerdict(hashMatch ? ANCHOR_OK : ANCHOR_TAIL_REWRITTEN,
            anchorSeq, anchorHash, tableSeq, tableHash);
    }

    /**
     * 带锚表判据的全链校验（2026-10-03 补，SEC-AUD-01）。
     *
     * <p>{@link #verifyChain()} / {@link #verifyChainStrict()} / {@link #verifyChainDetailed()}
     * 三个历史出口全部委派到本方法，故三者一并获得「删链尾 / 整表重排不可绕过」的判据。
     * 需要区分篡改类型时直接调本方法读 {@link AnchoredChainVerifyResult#verdict()}。
     *
     * <p><b>偏离既有约定之处（刻意）</b>：锚不一致的行被计入 {@code hashBroken}（而非 gaps），
     * 因为锚证据与表内链尾不符就是篡改，且默认出口 {@link #verifyChain()} 只回报 hashBroken——
     * 若归入 gaps，A 方案对 GAP 的宽容会让「删链尾」在默认出口上依旧显示「链完整」，即本缺陷原样。
     * 代价是 {@code verdict()} 可能对不可 rebuild 修复的截断报 {@code HASH_BROKEN}，
     * 故以 {@link AnchoredChainVerifyResult#verdict()} 的锚表结论码为准。
     *
     * <p><b>读快照一致性（2026-10-03 补）</b>：本方法的两次读（{@code selectList(audit_logs)}
     * 与 {@code chainHeadMapper.selectById}）必须落在同一一致性快照内。否则若这两次读夹在一次并发
     * {@link #append} 提交中间（先读表、后读锚），会看到「append 前的表 + append 后的锚」而误报
     * {@link #ANCHOR_TRUNCATED}——假告警。{@code append} 的推锚与插行同属一个
     * {@link Propagation#REQUIRES_NEW} 事务，故只加事务边界（不改任何写入侧语义）即可让两次读同快照。
     * 注意：注解必须落在<b>被外部调用的最外层方法</b>上——三个历史出口之间是 self-invocation，
     * 不走 Spring 代理，注解加在内部方法上对本调用链无效。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnchoredChainVerifyResult verifyChainAnchored() {
        List<AuditLog> all = auditLogMapper.selectList(orderBySeqAsc());
        AuditChainVerifyResult chain = verifyRows(all);
        AnchorVerdict anchor = checkAnchor(all);
        if (anchor.ok()) {
            return new AnchoredChainVerifyResult(chain, anchor);
        }
        // 锚不一致：把受影响的链尾行并入 hashBroken 汇入旧出口（见上方「偏离既有约定」说明）
        List<Long> hashBroken = new ArrayList<>(chain.hashBroken());
        for (Long seq : anchorBrokenSeqs(anchor)) {
            if (!hashBroken.contains(seq)) {
                hashBroken.add(seq);
            }
        }
        hashBroken.sort(Long::compareTo);
        return new AnchoredChainVerifyResult(
            new AuditChainVerifyResult(List.copyOf(hashBroken), chain.gaps(), chain.total()), anchor);
    }

    /** 追加一条审计（独立事务：业务失败不回滚审计；①②③ P 变体：锚行悲观锁原子分配 seq/prevHash） */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public AuditLog append(AuditLog draft) {
        // DEF-6 护栏：列类型改 longtext 后 DB 不再校验 JSON 合法性，在此复刻原 fail-fast。
        // 必须位于锚行锁之前：畸形载荷须立即抛出回滚，不得进入任何锁/推进路径。
        AuditEventData.requireJson(draft.getBeforeData(), "before_data");
        AuditEventData.requireJson(draft.getAfterData(), "after_data");
        // AI-P1-3 留痕门禁（2026-09-10）：aiAssisted=true 的载荷必须同时携带 aiModel/aiRole，
        // 单点接线全量调用点（44 文件/81 处）自动受益；同样位于锚行锁之前（同 DEF-6 理由）。
        AuditEventData.requireAiTrail(draft.getBeforeData(), "before_data");
        AuditEventData.requireAiTrail(draft.getAfterData(), "after_data");
        // P1-6 审计合并框架单点（R22 2026-09-09）：operator 三元组缺失时按 operatorId 查 persons
        // 补齐 name/personType——设计文档 §1.3 实测 operatorName 仅 51%/operatorRole 仅 34%，
        // 历史行哈希已冻结不可回填，但新行在此单点补齐后全量调用点（44 文件/81 处）自动受益，
        // 逐点注解化不必再为三元组而做。特殊操作人（operatorId=0 系统扫描/匿名）不查；
        // 查无此人（已删号/外部 ID）留空不抛——审计行不因被删操作人丟失。必须在锚行锁前查（缩短锁持有）。
        if (draft.getOperatorId() != null && draft.getOperatorId() != 0L
            && (isBlank(draft.getOperatorName()) || isBlank(draft.getOperatorRole()))) {
            Person person = personMapper.selectById(draft.getOperatorId());
            if (person != null) {
                if (isBlank(draft.getOperatorName())) {
                    draft.setOperatorName(person.getName());
                }
                if (isBlank(draft.getOperatorRole())) {
                    draft.setOperatorRole(person.getPersonType());
                }
            }
        }
        // DEF-4：先定时间再哈希——写入与验链共用同一 Date，且毫秒必须归零后再写库：
        // datetime(0) 对毫秒四舍五入（≥.500 进位），而 secondMillis 是截断，不归零则读回 +1s 哈希失配
        Date base = draft.getCreateTime() == null ? new Date() : draft.getCreateTime();
        draft.setCreateTime(new Date(secondMillis(base)));
        if (draft.getTenantId() == null) {
            draft.setTenantId("000000");
        }
        if (isBlank(draft.getTraceId())) {
            String traceId = MDC.get("traceId");
            draft.setTraceId(isBlank(traceId) ? null : traceId);
        }
        try {
            // ①②③ P 变体：锚行悲观锁 → 原子分配 seq/prevHash（全局串行，零重试零 CAS 竞态；锁序单一无死锁环）
            AuditChainHead head = chainHeadMapper.selectForUpdate(CHAIN_KEY_GLOBAL);
            if (head == null) {
                // 锚行缺失 = seed 未初始化/被清：fail-fast，禁止代码自举（自举会与并发方竞态；修复走停写窗口 sync-seed runbook）
                throw new IllegalStateException(
                    "audit_log_chain_heads missing GLOBAL anchor — run seed-sync (PR就绪包 §4.3) before appending");
            }
            long seq = head.getNextSeq();
            // GENESIS 兕底而非 nvl 空串：锚行 last_hash=NULL（清库后未 sync-seed 的病态）时，
            // 链首 prevHash 必须是 64×'0'（外部验链工具硬编码 GENESIS 起验）
            String prevHash = head.getLastHash() == null ? AuditHashChain.GENESIS : head.getLastHash();
            draft.setSeq(seq);                                   // NEVER 已去：显式值真正进入 INSERT
            draft.setPrevHash(prevHash);
            draft.setCurrHash(AuditHashChain.computeCurrHash(prevHash, canonicalOf(draft, seq)));
            // advance=1 防御断言（锁保护下正常必 1；0 = schema/chain_key 漂移，静默继续会劣化为
            // 撞 uk 或错链——与 head==null fail-fast 对称）
            if (chainHeadMapper.advance(CHAIN_KEY_GLOBAL, seq, draft.getCurrHash(), seq + 1) != 1) {
                throw new IllegalStateException("audit chain anchor advance missed — schema/config drift suspected");
            }
            auditLogMapper.insert(draft);
            return draft;
        } catch (RuntimeException | Error ex) {
            // 失败审计保留原异常和独立事务回滚；marker 供 OPS-06 监测定位。
            log.error("[IPD] 未捕获异常 traceId={} 审计追加失败 seq={}",
                draft.getTraceId(), draft.getSeq(), ex);
            throw ex;
        }
    }

    /**
     * Controller 友好重载（AOP P0 批消重目标位，2026-09-09 治理轮 R21）：
     * 四份 Controller 原各自拷贝同构 private audit() 方法（actor null 静默跳过 + builder 拼装），
     * 统一收到 Service 层。语义与原拷贝完全一致：actor == null 时静默跳过（不落审计不抛错）。
     * <p>兄弟在途 Controller（HrSync/Person/PersonSync）commit 后可一行切换到本重载。
     *
     * @param actor      当前操作人（null = 静默跳过，与原四份拷贝一致）
     * @param action     动作码（如 WITHDRAW / SYNC_PULL）
     * @param entityType 实体类型（如 receipt_ledger / person / hr_sync）
     * @param entityId   实体 ID（可空）
     * @param reason     理由（可空）
     */
    // 事务边界与 append(AuditLog) 同（REQUIRES_NEW）：本重载自调用 this.append(draft) 不走 Spring 代理，
    // 重载自身不带注解时独立事务会被静默降级为随业务回滚（失败审计独立落库红线），
    // 由 AuditLogAppendTransactionContractTest 反射断言锁死。
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void append(IpdActor actor, String action, String entityType, Long entityId, String reason) {
        if (actor == null) {
            return;
        }
        append(AuditLog.builder()
            .operatorId(actor.id())
            .operatorName(actor.name())
            .operatorRole(actor.role())
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .reason(reason)
            .build());
    }

    /**
     * Service 层消重重载（R239）：CoefficientChange/LaunchDateChange 两份逐字同构 private audit()
     * （builder 拼装仅 entityType 不同）统一收到 Service 层。与 {@link #append(IpdActor, ...)} 同型：
     * 均只拼语义字段后委派 {@link #append(AuditLog)}，createTime 与 operator 姓名/角色由底层统一补
     * （createTime=null→now 且毫秒归零；name/role 空→Person 回填），语义与原拷贝一致、无并行实现。
     * <p>事务边界同 {@link #append(IpdActor, ...)}：REQUIRES_NEW 显式标注（自调用不走代理的坑同源）。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void append(Long operatorId, String action, String entityType, Long entityId, String reason) {
        append(AuditLog.builder()
            .operatorId(operatorId)
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .reason(reason)
            .build());
    }

    /**
     * 全链校验（默认出口）：返回断裂/缺行的 seq 合并列表（空 = 链完整）——「GAP 即告警」严态。
     *
     * <p><b>语义变更（2026-10-03 审计链收口）</b>：P0-17 A 方案（2026-09-11 R30 owner 拍板，ADR-0076
     * 登记）原默认对 GAP 宽容、只报 hashBroken——「删整段中段审计行」在默认出口静默显示「链完整」。
     * 本次收口前核实：主源码在役调用方为零（HTTP {@code GET /api/v1/audit-logs/verify} 走
     * {@link #verifyChainDetailed()} 分列报告，{@code broken}/{@code gaps}/{@code chain} 三字段本就
     * 含 GAP；{@code scripts/verify-prod.sh} 只数行数不调本方法），严化默认不破坏任何在役消费者，
     * 故默认改回与 {@link #verifyChainStrict()} 同口径。ADR-0076 登记的 17 处遗留 GAP 基线不受影响，
     * 监控口径继续走 HTTP 端点分列字段（新增 GAP 仍须单独归因）。旧宽容口径保留为显式开关：
     * {@link #verifyChain(boolean)} 传 {@code true}。
     *
     * <p><b>SEC-AUD-01（2026-10-03）</b>：本出口含锚表判据（删链尾 / 整表重排 / 链尾被改写 /
     * 整表被清空），锚不一致的结论位计入 {@code hashBroken}；需区分篡改类型时调
     * {@link #verifyChainAnchored()} 读其结论码。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Long> verifyChain() {
        return verifyChainDetailed().mergedBroken();
    }

    /**
     * 全链校验（显式宽容开关）：{@code tolerateGap=true} 只报哈希断裂行（ADR-0076 A 方案旧口径，
     * 接受「seq 缺行 = 删行/事务回滚/InnoDB 自增值不回填」类空洞不告警）；{@code false} 等价
     * {@link #verifyChain()}。GAP 明细（含数量）无论开关与否都可在
     * {@link #verifyChainDetailed()} 的 {@code gaps} 字段读出，不存在静默宽容。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Long> verifyChain(boolean tolerateGap) {
        return tolerateGap ? verifyChainDetailed().hashBroken() : verifyChain();
    }

    /**
     * 全链校验（严态出口，兼容历史）：返回断裂/缺行的 seq 合并列表（空 = 链完整）。
     *
     * <p>2026-10-03 收口后与默认出口 {@link #verifyChain()} 语义一致（默认已改严态）；保留本方法
     * 供历史引用与显式语义自文档化。需区分「哈希不符」与「seq 缺行」时请用
     * {@link #verifyChainDetailed()}。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Long> verifyChainStrict() {
        return verifyChainDetailed().mergedBroken();
    }

    /**
     * 全链校验（分列出口，DEF-9 / 设计稿 G5）：把三条判据按**成因**拆为 HASH 与 GAP 两类。
     *
     * <p>判据③（seq 严格连续）单独归 {@code gaps}：它由缺行触发（删行 / 事务回滚 /
     * InnoDB 自增值不回填），{@link #rebuildChain()} 治不了；而判据①②归 {@code hashBroken}，
     * 可由 rebuild 重算修复。三类判据混在一个 {@code broken} 里时，「篡改」与「缺行」无法区分：
     * 实测 seq 1309（{@code prev_hash} 与 seq 609 的 {@code curr_hash} 相符、无载荷，纯因
     * 609→1309 空洞触发③）曾被归因为「{@code curr_hash} 由旧算法 jar 生成」，
     * 导致连跑两次 rebuild 仍无法消除。分列后该场景直报 {@code verdict()=GAP}。
     *
     * <p>注：同一行可同时入两类（例如缺行且哈希也不符），故 {@code mergedBroken()}
     * 需去重才能等价于原 {@code broken}。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditChainVerifyResult verifyChainDetailed() {
        return verifyChainAnchored().chain();
    }

    /**
     * 行级判据（原先 {@code verifyChainDetailed} 的全部内容，原样搬出；DEF-9 三条判据不变）。
     * 与锚表判据分离，使 {@link #verifyChainAnchored()} 能「行级 + 锚表」两级分别报告。
     */
    private static AuditChainVerifyResult verifyRows(List<AuditLog> all) {
        if (all.isEmpty()) {
            return new AuditChainVerifyResult(List.of(), List.of(), 0);
        }
        List<Long> hashBroken = new ArrayList<>();
        List<Long> gaps = new ArrayList<>();
        // DEF-4：锚点=库内实际首行（原实现硬编码 GENESIS/seq=1，历史首行缺失即误判；且误用降序遍历致结构性全断）
        String expectPrev = nvl(all.get(0).getPrevHash());
        Long expectSeq = all.get(0).getSeq();
        for (AuditLog log : all) {
            // P0-17 A 方案（2026-09-11 owner 拍板）：GAP 行缺失前驱，prev_hash↔前驱 curr_hash 不可验；
            // 但 curr_hash↔行内载荷仍可验（以行内 prev_hash 为锚）。修正前 GAP 首行必入 hashBroken
            // （期望 prev_hash 对比必败），致 hashBroken 与 gaps 重合（真活 16/16 重合实测）。
            boolean isGap = !expectSeq.equals(log.getSeq());
            String anchor = isGap ? nvl(log.getPrevHash()) : expectPrev;
            String expectHash = AuditHashChain.computeCurrHash(anchor, canonicalOf(log, log.getSeq()));
            if (isGap) {
                gaps.add(log.getSeq());
            }
            // DEF-9：载荷自洽判据（curr_hash）；prev_hash 连续性仅在前驱存在时才可信
            if (!expectHash.equals(log.getCurrHash())
                || (!isGap && !expectPrev.equals(nvl(log.getPrevHash())))) {
                hashBroken.add(log.getSeq());
            }
            expectPrev = log.getCurrHash();
            expectSeq = log.getSeq() + 1;
        }
        return new AuditChainVerifyResult(hashBroken, gaps, all.size());
    }

    /** 锚表判据指向的「受影响的链尾位」：改写类指表内链尾，截断/清空类指锚表记录的链尾 seq。 */
    private static List<Long> anchorBrokenSeqs(AnchorVerdict anchor) {
        if (ANCHOR_TAIL_REWRITTEN.equals(anchor.code()) && anchor.tableLastSeq() != null) {
            return List.of(anchor.tableLastSeq());
        }
        if ((ANCHOR_TRUNCATED.equals(anchor.code()) || ANCHOR_CLEARED.equals(anchor.code()))
            && anchor.anchorLastSeq() != null) {
            // 截断缺失的正是「表内最大 seq+1 .. 锚 seq」这一段，表内无行可指；以锚 seq 作代表位
            return List.of(anchor.anchorLastSeq());
        }
        return List.of();
    }

    /**
     * DEF-4 链重建：按现行 v1 秒级对称语义重算全链 prev/curr 哈希。
     * <p>仅触碰哈希两列，业务字段只读；幂等可重复执行——多实例旧 jar 仍可能写入毫秒污染行，
     * 全实例切新 jar 后终验前需重跑一次。调用方（Controller）负责超管门禁并为动作本身落审计。
     * <p>MED-2（2026-09-09 治理轮 R21）：锚行悲观锁互斥 + 重算后推锚——与 append 同锁序
     * （selectForUpdate GLOBAL → 读全链 → advance），消除并发 rebuild+append 人为断链与
     * 「重建后锚行 last_hash 陈旧 → 下次 append 用旧哈希起链」两类风险。
     *
     * @return 修正哈希的行数
     */
    @Transactional(rollbackFor = Exception.class)
    public long rebuildChain() {
        // MED-2：锁序与 append 一致（锁内全链读+逐行修正+推锚）；无锁并发 rebuild+append 会互踩
        AuditChainHead head = chainHeadMapper.selectForUpdate(CHAIN_KEY_GLOBAL);
        if (head == null) {
            throw new IllegalStateException(
                "audit_log_chain_heads missing GLOBAL anchor — run seed-sync before rebuild");
        }
        List<AuditLog> all = auditLogMapper.selectList(orderBySeqAsc());
        if (all.isEmpty()) {
            return 0L;
        }
        String prev = nvl(all.get(0).getPrevHash());
        long fixed = 0L;
        for (AuditLog log : all) {
            String curr = AuditHashChain.computeCurrHash(prev, canonicalOf(log, log.getSeq()));
            if (!curr.equals(log.getCurrHash()) || !prev.equals(nvl(log.getPrevHash()))) {
                auditLogMapper.updateChainHash(log.getId(), prev, curr);
                fixed++;
            }
            prev = curr;
        }
        // MED-2：推锚（last_seq/last_hash/next_seq 与重算后的链尾对齐）——不推则锚行陈旧，
        // 下次 append 会用旧 last_hash 起链导致新行 prev_hash 与链尾 curr_hash 不接。
        // advance=1 防御断言（锁保护下正常必 1；0 = schema/chain_key 漂移，与 append 同 fail-fast）。
        AuditLog last = all.get(all.size() - 1);
        if (chainHeadMapper.advance(CHAIN_KEY_GLOBAL, last.getSeq(), prev, last.getSeq() + 1) != 1) {
            throw new IllegalStateException("audit chain anchor advance missed after rebuild — schema/config drift suspected");
        }
        return fixed;
    }

    /** 审计导出硬上限（QA-05-P3 / PERF-AUD P2-2 防全量物化 OOM）。 */
    public static final long EXPORT_HARD_LIMIT = 50_000L;

    /**
     * 按 operatorIds 范围分页查询（P0-5.4 / AC-AUD-04、AC-AUD-05）。
     *
     * <p>QA-05-P3 游标分页重载：{@code beforeSeq} 非 null 时走 {@code seq < beforeSeq ORDER BY seq DESC LIMIT n}
     * 窗口扫描（命中 {@code idx_al_operator_seq} 覆盖索引），消除深页 OFFSET + filesort。
     * {@code beforeSeq} 为 null 时保持旧 OFFSET 行为完全兼容（不传 = 旧调用路径）。
     *
     * @param operatorIds 允许看到的操作人 id 集合；{@code null} 或空 = 全局（仅 SUPER_ADMIN 之路）。
     * @param pageNo       1-based
     * @param pageSize     上限 200
     * @param beforeSeq    游标（仅取 seq 严格小于此值，按 seq DESC）；null = 旧 OFFSET 行为
     * @return 倒序分页
     */
    public IPage<AuditLog> listByOperatorIds(List<Long> operatorIds, int pageNo, int pageSize, Long beforeSeq) {
        int capped = Math.min(Math.max(pageSize, 1), 200);
        Page<AuditLog> page = new Page<>(Math.max(pageNo, 1), capped);
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<>();
        if (operatorIds != null && !operatorIds.isEmpty()) {
            w.in(AuditLog::getOperatorId, operatorIds);
        }
        // QA-05-P3：beforeSeq 非 null 走游标窗口（旧 OFFSET 调用方传 null 走全兼容旧路径）
        if (beforeSeq != null) {
            w.lt(AuditLog::getSeq, beforeSeq);
        }
        w.orderByDesc(AuditLog::getSeq);
        return auditLogMapper.selectPage(page, w);
    }

    /** 兼容旧调用方：未传 beforeSeq 走旧 OFFSET 路径（AC-AUD-04/05 历史消费者）。 */
    public IPage<AuditLog> listByOperatorIds(List<Long> operatorIds, int pageNo, int pageSize) {
        return listByOperatorIds(operatorIds, pageNo, pageSize, null);
    }

    /**
     * 与 {@link #listByOperatorIds} 相同范围条件的计数（导出写审计前统计覆盖行数）。
     *
     * <p>QA-05-P3：超 {@link #EXPORT_HARD_LIMIT} 抛业务异常（中文文案），禁止全量物化 OOM。
     * 上限 5 万行覆盖 50 万行库的全量导出场景，超出强制客户端缩小范围或按 seq 窗口分批导出。
     */
    public long countByOperatorIds(List<Long> operatorIds) {
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<>();
        if (operatorIds != null && !operatorIds.isEmpty()) {
            w.in(AuditLog::getOperatorId, operatorIds);
        }
        long count = auditLogMapper.selectCount(w);
        if (count > EXPORT_HARD_LIMIT) {
            throw new ServiceException("审计导出行数 " + count + " 超过硬上限 " + EXPORT_HARD_LIMIT
                + " 行，请缩小时间/人员范围后重试（QA-05-P3 防 OOM）");
        }
        return count;
    }

    /** DEF-4：写读两侧共用的 canonical 构造（时间戳一律截秒，与 datetime(0) 列精度对称）。 */
    private static String canonicalOf(AuditLog log, long seq) {
        return AuditHashChain.canonical(seq, log.getOperatorId(), log.getOperatorName(),
            log.getOperatorRole(), log.getAction(), log.getEntityType(), log.getEntityId(),
            log.getBeforeData(), log.getAfterData(), log.getReason(), secondMillis(log.getCreateTime()));
    }

    /** DEF-4：毫秒→整秒截断（写库前归零用，避开 MySQL datetime(0) 的四舍五入进位）。 */
    private static long secondMillis(Date d) {
        return d == null ? 0L : d.getTime() / 1000L * 1000L;
    }

    /** DEF-4：验链/重建必须升序遍历（原 verifyChain 误用降序 wrapper 致结构性全断）。 */
    private LambdaQueryWrapper<AuditLog> orderBySeqAsc() {
        return new LambdaQueryWrapper<AuditLog>().orderByAsc(AuditLog::getSeq);
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    /** P1-6 三元组补齐用：null/纯空白视为缺失（与 canonicalOf 的空串语义一致） */
    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
