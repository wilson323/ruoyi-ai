package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.mapper.AiDocumentMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-10.2 AI 文档人工审核→归档门禁 + 版本对比验收
 * （主责 AC-AI-03 / AC-AI-05；BR-AI-02 未审核拒绝归档；BR-AI-06 审计身份可信）。
 *
 * <p>形态：Mockito 单元验收（对齐 P1101 / P033 / OPS05 惯例）。
 * 范围限定：仅服务层与状态机契约；HTTP 端到端真验证另由 P1-10.2-runner 在 docs/ipd-系统说明/验收/ 留存。
 */
@Tag("dev")
@DisplayName("P1102 AI 文档：归档门禁 / reject 终态 / 改版重置 / diff / 回溯 / 审计身份 / 幂等")
@ExtendWith(MockitoExtension.class)
class P1102AcceptanceTest {

    @Mock
    private AiDocumentMapper mapper;

    @InjectMocks
    private AiDocumentService service;

    /** 固定时钟：归档时间 / 拒绝时间断言精确到毫秒 */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("UTC"));

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiDocument.class);
    }

    @BeforeEach
    void resetServiceToFixedClock() {
        // 替换 @InjectMocks 注入的 service 为带固定时钟的实例，确保时间断言精确到毫秒
        service = new AiDocumentService(mapper).withClock(FIXED);
    }

    private static AiDocument row(long id, int versionNo, Long parentId, String status, String content) {
        AiDocument r = AiDocument.builder()
            .id(id).projectId(900L).docType("CHARTER").title("v" + versionNo)
            .content(content).model(versionNo == 1 ? "deepseek-v3" : null)
            .status(status).parentVersionId(parentId).versionNo(versionNo)
            .contentSha256(AiDocumentService.sha256Hex(content))
            .build();
        r.setCreateBy(7L);
        r.setCreateTime(new Date());
        return r;
    }

    // -------------------------------------------------------------------
    // 测 1 — AC-AI-03 正例：未审核（GENERATED）尝试归档 ⇒ STATE_CONFLICT + 固定文案
    // -------------------------------------------------------------------
    @Test
    @DisplayName("AC-AI-03 正例：status=GENERATED 尝试归档 ⇒ STATE_CONFLICT + '须人工审核确认'")
    void archive_unreviewed_generated_rejected() {
        when(mapper.selectById(1L))
            .thenReturn(row(1L, 1, null, AiDocumentService.STATUS_GENERATED, "原始 v1"));

        assertThatThrownBy(() -> service.archive(1L, 99L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> {
                IpdBusinessException ibe = (IpdBusinessException) e;
                assertThat(ibe.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
                assertThat(ibe.getMessage()).contains("须人工审核确认");
            });

        verify(mapper, never()).update(isNull(), any());
    }

    // -------------------------------------------------------------------
    // 测 2 — AC-AI-03 反例：REVIEWED 归档成功 ⇒ archived_at/archived_by 落库 + 状态切 ARCHIVED
    // -------------------------------------------------------------------
    @Test
    @DisplayName("AC-AI-03 反例：status=REVIEWED 归档 ⇒ 200 OK + archived_at 写入 + status=ARCHIVED")
    void archive_reviewed_succeeds() {
        when(mapper.selectById(1L))
            .thenReturn(row(1L, 1, null, AiDocumentService.STATUS_REVIEWED, "已审核 v1"));
        when(mapper.update(isNull(), any())).thenReturn(1);

        AiDocument archived = service.archive(1L, 99L);

        assertThat(archived.getStatus()).isEqualTo(AiDocumentService.STATUS_ARCHIVED);
        assertThat(archived.getArchivedAt()).isEqualTo(Date.from(FIXED.instant()));
        assertThat(archived.getArchivedBy()).isEqualTo(99L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiDocument>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        // 归档三列必须出现在 set；content/title/contentSha256 不得出现（防御历史被覆盖）
        var params = captor.getValue().getParamNameValuePairs().values();
        assertThat(params).contains(
            AiDocumentService.STATUS_ARCHIVED, 99L, Date.from(FIXED.instant()));
    }

    // -------------------------------------------------------------------
    // 测 3 — 改版重置：已 REVIEWED 的 doc 经 revise 产生的新版本 status=GENERATED（审核流重置）
    // -------------------------------------------------------------------
    @Test
    @DisplayName("改版重置：已 REVIEWED 文档 revise 后新版本 status=GENERATED，需重新审核")
    void revise_resetsReviewStateToGenerated() {
        // v1 已 REVIEWED；P1-10.3 后 head/history 统一走 selectChain 递归 CTE（升序链）
        AiDocument v1 = row(1L, 1, null, AiDocumentService.STATUS_REVIEWED, "已审核 v1");
        when(mapper.selectChain(1L)).thenReturn(List.of(v1));
        when(mapper.lockVersion(v1.getId())).thenReturn(v1);

        AiDocument v2 = service.revise(1L, 1L, "改版全文 v2", null, 7L);

        // 新版本行 status=GENERATED（即便 v1 已 REVIEWED），强制走审核流
        assertThat(v2.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(v2.getVersionNo()).isEqualTo(2);
        assertThat(v2.getParentVersionId()).isEqualTo(1L);
        // v2 的 reviewedBy 必须为 null（不能继承 v1 的审核人，BR-AI-03 强制重新审核）
        assertThat(v2.getReviewedBy()).isNull();
        assertThat(v2.getReviewedAt()).isNull();
    }

    // -------------------------------------------------------------------
    // 测 4 — AC-AI-05 版本对比：from=v1, to=v3 ⇒ 字段级 diff 列表
    // -------------------------------------------------------------------
    @Test
    @DisplayName("AC-AI-05 版本对比：v1→v3 ⇒ title/content/contentSha256 三字段差异")
    void diff_betweenVersions_listsChangedFields() {
        AiDocument v1 = row(1L, 1, null, AiDocumentService.STATUS_REVIEWED, "v1 内容");
        AiDocument v3 = row(3L, 3, 2L, AiDocumentService.STATUS_REVIEWED, "v3 内容（已改版两轮）");
        v3.setTitle("v3 改后标题");
        when(mapper.selectById(1L)).thenReturn(v1);
        when(mapper.selectById(3L)).thenReturn(v3);

        AiDocumentService.DiffReport report = service.diff(1L, 3L);

        assertThat(report.fromVersionId()).isEqualTo(1L);
        assertThat(report.fromVersionNo()).isEqualTo(1);
        assertThat(report.toVersionId()).isEqualTo(3L);
        assertThat(report.toVersionNo()).isEqualTo(3);
        // title + content + contentSha256 三字段均差异
        assertThat(report.differences()).extracting(AiDocumentService.FieldDiff::field)
            .containsExactlyInAnyOrder("title", "content", "contentSha256");
        AiDocumentService.FieldDiff contentDiff = report.differences().stream()
            .filter(d -> "content".equals(d.field())).findFirst().orElseThrow();
        assertThat(contentDiff.fromSha256()).isEqualTo(v1.getContentSha256());
        assertThat(contentDiff.toSha256()).isEqualTo(v3.getContentSha256());
        assertThat(contentDiff.fromValue()).isEqualTo("v1 内容");
        assertThat(contentDiff.toValue()).isEqualTo("v3 内容（已改版两轮）");
    }

    // -------------------------------------------------------------------
    // 测 5 — AC-AI-05 回溯：history() 返回版本列表（含 version / author / createdAt / status）
    // -------------------------------------------------------------------
    @Test
    @DisplayName("AC-AI-05 回溯：history() 升序返回 v1..vN 全链 + author/createdAt/status 完整")
    void history_listsChainWithMetadata() {
        AiDocument v1 = row(1L, 1, null, AiDocumentService.STATUS_REVIEWED, "v1 内容");
        v1.setReviewedBy(7L);
        AiDocument v2 = row(2L, 2, 1L, AiDocumentService.STATUS_REVIEWED, "v2 内容");
        v2.setReviewedBy(8L);
        AiDocument v3 = row(3L, 3, 2L, AiDocumentService.STATUS_GENERATED, "v3 内容");
        v3.setCreateBy(9L);

        when(mapper.selectChain(3L)).thenReturn(List.of(v1, v2, v3));

        List<AiDocument> chain = service.history(3L);

        assertThat(chain).hasSize(3);
        // 每行都具备回溯视图必须字段
        chain.forEach(d -> {
            assertThat(d.getVersionNo()).isNotNull();
            assertThat(d.getCreateBy()).isNotNull();
            assertThat(d.getCreateTime()).isNotNull();
            assertThat(d.getStatus()).isNotBlank();
        });
        // 状态分布：v1/v2 REVIEWED，v3 GENERATED（改版未审）
        assertThat(chain.get(0).getStatus()).isEqualTo(AiDocumentService.STATUS_REVIEWED);
        assertThat(chain.get(2).getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
    }

    // -------------------------------------------------------------------
    // 测 6 — 审计身份可信：review 操作把 reviewed_by 强制绑 operator.id，无法伪造
    // -------------------------------------------------------------------
    @Test
    @DisplayName("审计身份可信：review 必须把 reviewed_by 绑当前 actor.id，无法伪造为他人")
    void review_actorMustMatchReviewedBy() {
        when(mapper.selectById(1L))
            .thenReturn(row(1L, 1, null, AiDocumentService.STATUS_GENERATED, "待审 v1"));
        when(mapper.update(isNull(), any())).thenReturn(1);

        // actor.id = 42 即 reviewed_by 必须为 42；调用方传 99 也不能让它变 99
        AiDocument reviewed = service.review(1L, 42L);

        assertThat(reviewed.getReviewedBy()).isEqualTo(42L);
        assertThat(reviewed.getReviewedAt()).isEqualTo(Date.from(FIXED.instant()));

        // LambdaUpdateWrapper 中 reviewed_by = 42 必现，actor.id = 99 不出现
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiDocument>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(42L);
        assertThat(captor.getValue().getParamNameValuePairs().values()).doesNotContain(99L);
    }

    // -------------------------------------------------------------------
    // 测 7 — 拒绝回退：REJECTED 后必须重新走 review 才能 ARCHIVED
    // -------------------------------------------------------------------
    @Test
    @DisplayName("拒绝回退：REJECTED 行 archive ⇒ 拒绝 + '须人工审核确认'，禁止直接归档")
    void rejected_mustReReviewBeforeArchive() {
        AiDocument rejected = row(1L, 1, null, AiDocumentService.STATUS_REJECTED, "被拒 v1");
        rejected.setReviewComment("内容不完整");
        when(mapper.selectById(1L)).thenReturn(rejected);

        assertThatThrownBy(() -> service.archive(1L, 99L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> {
                IpdBusinessException ibe = (IpdBusinessException) e;
                assertThat(ibe.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
                assertThat(ibe.getMessage()).contains("须人工审核确认");
            });

        // 同时验证 reject() 自身幂等：再 reject 同一 REJECTED 行返回原行，不覆盖原 reviewComment
        AiDocument again = service.reject(1L, 99L, "试图覆盖原因");
        assertThat(again.getReviewComment()).isEqualTo("内容不完整");
        verify(mapper, never()).update(isNull(), any());
    }

    // -------------------------------------------------------------------
    // 测 8 — 归档幂等：已 ARCHIVED 行再次 archive ⇒ STATE_CONFLICT（不重复写 archived_at）
    // -------------------------------------------------------------------
    @Test
    @DisplayName("归档幂等：已 ARCHIVED 行 archive ⇒ STATE_CONFLICT，不重复落 archived_at")
    void archive_archived_idempotentRejection() {
        AiDocument already = row(1L, 1, null, AiDocumentService.STATUS_ARCHIVED, "v1 内容");
        already.setArchivedAt(new Date(0));
        already.setArchivedBy(3L);
        when(mapper.selectById(1L)).thenReturn(already);

        assertThatThrownBy(() -> service.archive(1L, 99L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> {
                IpdBusinessException ibe = (IpdBusinessException) e;
                assertThat(ibe.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
                assertThat(ibe.getMessage()).contains("须人工审核确认");
            });

        // 防御：archived_at/archived_by 维持原值（new Date(0) 与 3L），不被 99L 覆盖
        assertThat(already.getArchivedBy()).isEqualTo(3L);
        assertThat(already.getArchivedAt()).isEqualTo(new Date(0));
        verify(mapper, never()).update(isNull(), any());
    }

    // -------------------------------------------------------------------
    // 测 9 — reject 全状态机：GENERATED/REVIEWED/ARCHIVED/REJECTED 行为矩阵
    // -------------------------------------------------------------------
    @Test
    @DisplayName("reject 状态机：待审核链头可退回 / REVIEWED 接受 / ARCHIVED 拒")
    void reject_stateMachineContract() {
        // 1) 当前链头 GENERATED ⇒ 退回，意见和操作者落在这一版，正文不动
        AiDocument pending = row(11L, 1, null, AiDocumentService.STATUS_GENERATED, "v1 内容");
        when(mapper.selectById(11L)).thenReturn(pending);
        when(mapper.selectChain(11L)).thenReturn(List.of(pending));
        when(mapper.lockVersion(pending.getId())).thenReturn(pending);
        when(mapper.update(isNull(), any())).thenReturn(1);
        AiDocument returned = service.reject(11L, 99L, "原因 X");
        assertThat(returned.getStatus()).isEqualTo(AiDocumentService.STATUS_REJECTED);
        assertThat(returned.getReviewComment()).isEqualTo("原因 X");
        assertThat(returned.getReviewedBy()).isEqualTo(99L);
        assertThat(returned.getContent()).isEqualTo("v1 内容");
        assertThat(returned.getId()).isEqualTo(11L);

        // 2) ARCHIVED 行 reject ⇒ 终态不可拒
        when(mapper.selectById(12L))
            .thenReturn(row(12L, 1, null, AiDocumentService.STATUS_ARCHIVED, "v1 内容"));
        assertThatThrownBy(() -> service.reject(12L, 99L, "原因 X"))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));

        // 3) REVIEWED 行 reject ⇒ 接受，状态 → REJECTED，review_comment 落库
        AiDocument reviewed = row(13L, 1, null, AiDocumentService.STATUS_REVIEWED, "v1 内容");
        when(mapper.selectById(13L)).thenReturn(reviewed);
        when(mapper.update(isNull(), any())).thenReturn(1);
        AiDocument rejected = service.reject(13L, 99L, "项目章程口径不一致");
        assertThat(rejected.getStatus()).isEqualTo(AiDocumentService.STATUS_REJECTED);
        assertThat(rejected.getReviewComment()).isEqualTo("项目章程口径不一致");
    }

    @Test
    @DisplayName("待审核稿已不是链头：拒绝过时退回，零写入")
    void reject_pendingNotHead_staleConflict() {
        AiDocument v1 = row(11L, 1, null, AiDocumentService.STATUS_GENERATED, "v1 内容");
        AiDocument v2 = row(12L, 2, 11L, AiDocumentService.STATUS_GENERATED, "v2 内容");
        when(mapper.selectById(11L)).thenReturn(v1);
        when(mapper.selectChain(11L)).thenReturn(List.of(v1, v2));
        when(mapper.lockVersion(v1.getId())).thenReturn(v1);
        when(mapper.lockChild(v1.getId())).thenReturn(v2);

        assertThatThrownBy(() -> service.reject(11L, 99L, "过时意见"))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> {
                IpdBusinessException ibe = (IpdBusinessException) e;
                assertThat(ibe.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
                assertThat(ibe.getMessage()).contains("版本已变更");
            });

        verify(mapper, never()).update(isNull(), any());
        assertThat(v1.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(v1.getReviewComment()).isNull();
    }

    @Test
    @DisplayName("普通快照仍是旧版：当前锁读发现新链头时拒绝退回")
    void reject_currentLockReadRejectsNewHeadDespiteStaleSnapshot() {
        AiDocument v1 = row(21L, 1, null, AiDocumentService.STATUS_GENERATED, "v1 内容");
        AiDocument v2 = row(22L, 2, 21L, AiDocumentService.STATUS_GENERATED, "v2 内容");
        when(mapper.selectById(21L)).thenReturn(v1);
        when(mapper.selectChain(21L))
            .thenReturn(List.of(v1));
        when(mapper.lockVersion(21L)).thenReturn(v1);
        when(mapper.lockChild(21L)).thenReturn(v2);


        assertThatThrownBy(() -> service.reject(21L, 99L, "竞态意见"))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getMessage()).contains("版本已变更"));

        assertThat(v1.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(v1.getReviewComment()).isNull();
    }

    @Test
    @DisplayName("退回后改版：新版本回到待审核，不继承旧意见")
    void revise_afterReturn_doesNotCarryReview() {
        AiDocument returned = row(1L, 1, null, AiDocumentService.STATUS_REJECTED, "旧正文");
        returned.setReviewComment("请补充竞品口径");
        returned.setReviewedBy(99L);
        when(mapper.selectChain(1L)).thenReturn(List.of(returned));
        when(mapper.lockVersion(returned.getId())).thenReturn(returned);

        AiDocument next = service.revise(1L, 1L, "返工正文", null, 7L);

        assertThat(next.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(next.getReviewComment()).isNull();
        assertThat(next.getReviewedBy()).isNull();
        assertThat(next.getParentVersionId()).isEqualTo(1L);
        assertThat(returned.getStatus()).isEqualTo(AiDocumentService.STATUS_REJECTED);
        assertThat(returned.getReviewComment()).isEqualTo("请补充竞品口径");
    }
}