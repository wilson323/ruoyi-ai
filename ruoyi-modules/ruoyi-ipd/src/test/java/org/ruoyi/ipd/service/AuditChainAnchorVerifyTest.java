package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AuditChainHead;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AuditChainHeadMapper;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.util.AuditHashChain;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SEC-AUD-01（2026-10-03）：验链必须读锚表 {@code audit_log_chain_heads}。
 *
 * <p>缺陷：三个验链出口（verifyChain / verifyChainStrict / verifyChainDetailed）共用邻接哈希
 * 实现，全程不读锚表。锚表只在 append / rebuildChain 被写，验链侧一次都没读过 ⇒ 锚表对篡改无效：
 * <ul>
 *   <li>删链尾 N 行 → 剩余行首尾仍邻接自洽，验链返回「链完整」；</li>
 *   <li>整表重排并按「prev=前驱 curr」重算哈希 → 每行邻接自洽，验链同样返回「链完整」。</li>
 * </ul>
 *
 * <p>本测试锁修复后语义：正常链通过 / 删尾部检出 / 整表重排检出 / 锚行缺失与空链的退化处理。
 * 后两类篡改的结论码必须可区分（ANCHOR_TRUNCATED vs ANCHOR_TAIL_REWRITTEN）。
 */
@Tag("dev")
@DisplayName("SEC-AUD-01 验链锚表判据（删尾部 / 整表重排不可绕过）")
@ExtendWith(MockitoExtension.class)
class AuditChainAnchorVerifyTest {

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private AuditChainHeadMapper chainHeadMapper;
    @Mock
    private PersonMapper personMapper;

    @InjectMocks
    private AuditLogServiceImpl service;

    private static final Date T0 = new Date(1788700100000L);

    private static AuditChainHead anchor(Long lastSeq, String lastHash, long nextSeq) {
        AuditChainHead head = new AuditChainHead();
        head.setChainKey("GLOBAL");
        head.setLastSeq(lastSeq);
        head.setLastHash(lastHash);
        head.setNextSeq(nextSeq);
        return head;
    }

    private static String canonicalSecond(AuditLog log) {
        return AuditHashChain.canonical(log.getSeq(), log.getOperatorId(), log.getOperatorName(),
            log.getOperatorRole(), log.getAction(), log.getEntityType(), log.getEntityId(),
            log.getBeforeData(), log.getAfterData(), log.getReason(),
            log.getCreateTime().getTime() / 1000L * 1000L);
    }

    /** 构造一行自洽链行（哈希按「prev=前驱 curr」重算，攻击者可完全复现此能力）。 */
    private static AuditLog row(long id, long seq, String prevHash, String action, Date createTime) {
        AuditLog log = AuditLog.builder()
            .id(id).seq(seq).prevHash(prevHash)
            .operatorId(9L).operatorName("管理员").operatorRole("SUPER_ADMIN")
            .action(action).entityType("projects").entityId(1L)
            .createTime(createTime)
            .build();
        log.setCurrHash(AuditHashChain.computeCurrHash(prevHash, canonicalSecond(log)));
        return log;
    }

    /** 建一条 n 行自洽链（seq 从 fromSeq 起连续），返回行列表。 */
    private static List<AuditLog> chain(int n, long fromSeq, String firstPrev) {
        List<AuditLog> rows = new ArrayList<>();
        String prev = firstPrev;
        for (int i = 0; i < n; i++) {
            long seq = fromSeq + i;
            AuditLog r = row(100L + seq, seq, prev, "ACTION_" + seq, new Date(T0.getTime() + seq * 1000L));
            rows.add(r);
            prev = r.getCurrHash();
        }
        return rows;
    }

    private static AuditChainHead anchorOf(List<AuditLog> rows) {
        AuditLog tail = rows.get(rows.size() - 1);
        return anchor(tail.getSeq(), tail.getCurrHash(), tail.getSeq() + 1);
    }

    private void givenRows(List<AuditLog> rows) {
        when(auditLogMapper.selectList(any())).thenReturn(rows);
    }

    private void givenAnchor(AuditChainHead head) {
        when(chainHeadMapper.selectById("GLOBAL")).thenReturn(head);
    }

    @Test
    @DisplayName("正常链：锚表链尾 == 表内链尾 → 三个出口全通过，锚结论码 ANCHOR_OK")
    void healthyChainPassesAllExits() {
        List<AuditLog> rows = chain(5, 1L, AuditHashChain.GENESIS);
        givenRows(rows);
        givenAnchor(anchorOf(rows));

        assertThat(service.verifyChain()).isEmpty();
        assertThat(service.verifyChainStrict()).isEmpty();
        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.anchor().ok()).isTrue();
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_OK);
        assertThat(r.verdict()).isEqualTo("OK");
        assertThat(r.anchor().anchorLastSeq()).isEqualTo(5L);
        assertThat(r.anchor().tableLastSeq()).isEqualTo(5L);
    }

    @Test
    @DisplayName("删链尾 2 行：行级判据全绿（剩余行邻接自洽），锚判据检出 ANCHOR_TRUNCATED 且默认出口不再报「链完整」")
    void deleteTailIsDetected() {
        List<AuditLog> full = chain(5, 1L, AuditHashChain.GENESIS);
        AuditChainHead head = anchorOf(full);                 // 锚停在 seq=5
        List<AuditLog> truncated = new ArrayList<>(full.subList(0, 3)); // 攻击者删掉 seq 4/5
        givenRows(truncated);
        givenAnchor(head);

        // 前提：若只查邻接哈希，剩余 3 行逐行自洽且 seq 连续 → 行级判据零断裂（原缺陷的形态）
        assertThat(service.verifyChainStrict()).containsExactly(5L);   // 断裂位来自锚判据而非行级
        assertThat(service.verifyChain()).containsExactly(5L);         // 默认出口必须响，不再是「链完整」

        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_TRUNCATED);
        assertThat(r.verdict()).isEqualTo(AuditLogServiceImpl.ANCHOR_TRUNCATED);
        // 锚指向的是表内已不存在的 seq（删尾方向：锚在「旧尾巴」侧）
        assertThat(r.anchor().anchorLastSeq()).isEqualTo(5L);
        assertThat(r.anchor().tableLastSeq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("整表重排并按前驱重算哈希：每行邻接自洽 → 锚判据检出 ANCHOR_TAIL_REWRITTEN（与截断可区分）")
    void wholeTableReorderIsDetected() {
        List<AuditLog> original = chain(4, 1L, AuditHashChain.GENESIS);
        AuditChainHead head = anchorOf(original);              // 锚 seq=4 + 原链尾哈希

        // 攻击者把 4 行倒序重插，seq 重新按 1..4 连续分配，并按「prev=前驱 curr」重算全表哈希
        List<AuditLog> shuffled = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long newSeq = i + 1L;
            AuditLog src = original.get(3 - i);                 // 载荷倒序
            shuffled.add(row(100L + newSeq, newSeq,
                i == 0 ? AuditHashChain.GENESIS : shuffled.get(i - 1).getCurrHash(),
                src.getAction(), new Date(T0.getTime() + newSeq * 1000L)));
        }
        givenRows(shuffled);

        // 前提①（阳性对照）：若把锚换成「与重排后表内链尾一致」，验链全绿——证明重排后行级判据
        // （seq 连续 + 逐行 prev=前驱 curr 自洽）确实挑不出毛病，即原缺陷的形态。
        givenAnchor(anchorOf(shuffled));
        assertThat(service.verifyChainAnchored().verdict()).isEqualTo("OK");
        assertThat(service.verifyChain()).isEmpty();

        // 前提②：换回真实锚表（仍是重排前的链尾）→ 锚判据把「行级 OK + 锚不符」判为篡改
        givenAnchor(head);
        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.chain().gaps()).isEmpty();                // 行级既无缺行也无（除锚位外）断裂
        assertThat(r.chain().hashBroken()).containsExactly(4L); // 唯一断裂位来自锚判据
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_TAIL_REWRITTEN);
        assertThat(r.verdict()).isEqualTo(AuditLogServiceImpl.ANCHOR_TAIL_REWRITTEN);
        // 结论码与截断可区分
        assertThat(r.anchor().code()).isNotEqualTo(AuditLogServiceImpl.ANCHOR_TRUNCATED);
        assertThat(service.verifyChain()).containsExactly(4L);
    }

    @Test
    @DisplayName("同 seq 不同哈希（尾部内容被改写后重算）：锚判据检出 ANCHOR_TAIL_REWRITTEN")
    void sameSeqDifferentHashIsDetected() {
        List<AuditLog> rows = chain(3, 1L, AuditHashChain.GENESIS);
        AuditChainHead head = anchorOf(rows);                 // 锚 seq=3 + 原链尾哈希
        // 攻击者改了末行业务字段并把整链重算自洽，但没同步锚表
        AuditLog tamperedTail = row(100L + 3L, 3L, rows.get(1).getCurrHash(), "TAMPERED",
            new Date(T0.getTime() + 3000L));
        List<AuditLog> tampered = new ArrayList<>(List.of(rows.get(0), rows.get(1), tamperedTail));
        givenRows(tampered);

        // 前提①（阳性对照）：锚与「改写后表内链尾」一致时验链全绿——改写后行级判据挑不出毛病
        givenAnchor(anchorOf(tampered));
        assertThat(service.verifyChainAnchored().verdict()).isEqualTo("OK");

        // 前提②：换回真实锚表（仍是改写前的链尾哈希）→ 只有锚知道末行动作被改
        givenAnchor(head);
        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.chain().gaps()).isEmpty();
        assertThat(r.chain().hashBroken()).containsExactly(3L);
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_TAIL_REWRITTEN);
        assertThat(r.anchor().anchorLastSeq()).isEqualTo(3L);
        assertThat(r.anchor().tableLastSeq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("锚行缺失（未 seed / 被清）：退化为邻接自洽，不编造断裂行，结论码 ANCHOR_MISSING")
    void missingAnchorDegradesGracefully() {
        List<AuditLog> rows = chain(3, 1L, AuditHashChain.GENESIS);
        givenRows(rows);
        givenAnchor(null);

        assertThat(service.verifyChain()).isEmpty();
        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_MISSING);
        assertThat(r.anchor().ok()).isFalse();
        assertThat(r.anchor().anchorLastSeq()).isNull();
        // 锚无结论时退回行级三态结论（不是把结论码当 verdict）
        assertThat(r.verdict()).isEqualTo("OK");
    }

    @Test
    @DisplayName("空链 + 锚 last_seq=0（未写过）：两侧一致判 ANCHOR_OK，不误报清空")
    void emptyChainWithZeroAnchorPasses() {
        givenRows(List.of());
        givenAnchor(anchor(0L, null, 1L));

        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_OK);
        assertThat(r.verdict()).isEqualTo("OK");
        assertThat(service.verifyChain()).isEmpty();
    }

    @Test
    @DisplayName("整表被清空（表空而锚仍有链）：检出 ANCHOR_CLEARED")
    void wholeTableClearedIsDetected() {
        givenRows(List.of());
        givenAnchor(anchor(7L, "a".repeat(64), 8L));

        AuditLogServiceImpl.AnchoredChainVerifyResult r = service.verifyChainAnchored();
        assertThat(r.anchor().code()).isEqualTo(AuditLogServiceImpl.ANCHOR_CLEARED);
        assertThat(r.verdict()).isEqualTo(AuditLogServiceImpl.ANCHOR_CLEARED);
        assertThat(service.verifyChain()).containsExactly(7L);
    }

    @Test
    @DisplayName("rebuild 后锚被推到表内链尾 → 验链判通过（判据每次现读锚与表，与 rebuild 语义一致，不误报）")
    void afterRebuildAnchorRefreshVerificationPasses() {
        List<AuditLog> rows = chain(3, 1L, AuditHashChain.GENESIS);
        // rebuild 推锚后的状态：锚 = 表内链尾（seq 3 + 链尾哈希）
        givenRows(rows);
        givenAnchor(anchorOf(rows));

        assertThat(service.verifyChain()).isEmpty();
        assertThat(service.verifyChainAnchored().anchor().ok()).isTrue();
    }
}
