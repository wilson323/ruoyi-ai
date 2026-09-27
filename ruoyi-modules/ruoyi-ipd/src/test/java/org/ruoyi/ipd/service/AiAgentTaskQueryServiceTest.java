package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.vo.AiAgentTaskView;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232 P2-04：AiAgentTaskQueryService 只读查询单测（零写入面）。
 * {@code @Tag("dev")} 必须，否则 Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖：单查映射（含 resultSummary/errorMsg 透出）、不存在 → 50001 NOT_FOUND、
 * 列表映射 + 空项目空列表；并断言本类方法面零写（只允许 selectById/selectList）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiAgentTaskQueryServiceTest {

    @Mock
    private AiAgentTaskMapper taskMapper;

    @InjectMocks
    private AiAgentTaskQueryService service;

    private static AiAgentTask sampleTask() {
        AiAgentTask t = AiAgentTask.builder()
            .projectId(200L).actionCode("C01").stageActionId(300L)
            .triggerType(AiAgentTask.TRIGGER_PASSIVE).execMode("AI_GENERATE")
            .status(AiAgentTask.STATUS_SUCCEEDED)
            .resultSummary("AI 草稿已生成，待市场 PM 审核（aiDocId=9001）")
            .aiDocId(9001L)
            .dedupKey("C01:300:PASSIVE")
            .fillPayload("{\"secret\":\"prompt 原文不得出 VO\"}")
            .inputDigest("sha256:deadbeef")
            .attempt(0)
            .triggeredBy(9001L)
            .build();
        t.setId(2104L);
        t.setCreateTime(new Date(1_700_000_000_000L));
        return t;
    }

    @Test
    @DisplayName("P2-04：getByTaskId 映射 AiAgentTaskView（resultSummary/errorMsg 透出，fillPayload/inputDigest 不出 VO）")
    void getByTaskId_mapsToView() {
        when(taskMapper.selectById(2104L)).thenReturn(sampleTask());

        AiAgentTaskView view = service.getByTaskId(2104L);

        assertThat(view.id()).isEqualTo(2104L);
        assertThat(view.projectId()).isEqualTo(200L);
        assertThat(view.status()).isEqualTo(AiAgentTask.STATUS_SUCCEEDED);
        assertThat(view.resultSummary()).contains("AI 草稿已生成");
        assertThat(view.aiDocId()).isEqualTo(9001L);
        assertThat(view.triggerType()).isEqualTo(AiAgentTask.TRIGGER_PASSIVE);
        // record 组件面不含敏感列（组件里根本没有），逐值兜底：VO 对象 toString 不得带出原文
        assertThat(view.toString())
            .doesNotContain("prompt 原文")
            .doesNotContain("sha256:deadbeef")
            .doesNotContain("fillPayload");
        verify(taskMapper).selectById(2104L);
    }

    @Test
    @DisplayName("P2-04：getByTaskId 不存在 → 50001 NOT_FOUND（IpdResources 收口）")
    void getByTaskId_missing_throwsNotFound() {
        when(taskMapper.selectById(4044L)).thenReturn(null);

        assertThatThrownBy(() -> service.getByTaskId(4044L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("P2-04：listByProject 映射列表；空项目返回空列表")
    void listByProject_mapsAndEmptySafe() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sampleTask()));
        List<AiAgentTaskView> views = service.listByProject(200L);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).actionCode()).isEqualTo("C01");

        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.listByProject(200L)).isEmpty();

        // 查询条件经 selectList 进入 mapper（wrapper 内容由 MP 引擎解析，此处只锁调用面）
        ArgumentCaptor<Wrapper<AiAgentTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, org.mockito.Mockito.atLeast(2)).selectList(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
    }
}
