package org.ruoyi.ipd.feedback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.service.NegativeFeedbackService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R27 P0-5：NegativeFeedbackService 状态机 5 函数补全 单测（TDD 红→绿）。
 *
 * <p>本类守「4 个新增方法签名 + 基本行为契约」（原 #3 updateStatus 已按 owner 2026-09-25 拍板删除：与 submit/decide/lift 状态机构成双轨）：
 * <ul>
 *   <li>{@link NegativeFeedbackService#getByProjectId(Long)}：项目维度列表</li>
 *   <li>{@link NegativeFeedbackService#submit(NegativeFeedback)}：简化版提交（已存在 row）</li>
 *   <li>{@link NegativeFeedbackService#deleteById(Long)}：软删除（del_flag=1）</li>
 *   <li>{@link NegativeFeedbackService#listBySeverity(String)}：按 severity 过滤</li>
 * </ul>
 *
 * <p>Hermetic 单元测试（无 Spring 启动、无 DB 连接）：纯 mock 注入 NegativeFeedbackMapper，
 * Lambda 列缓存通过 {@link TableInfoHelper#initTableInfo} 初始化（同 BidInvitationServiceTest 模式）。
 *
 * <p>@Tag("dev") 是项目级 surefire 守门——非 dev 标签不进入执行。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class NegativeFeedbackServiceR27Test {

    @Mock
    private NegativeFeedbackMapper mapper;

    private NegativeFeedbackService service;

    /** 纯 JVM 单测无 MP 运行时：手动初始化 lambda 列缓存（LambdaQueryWrapper.eq/.orderBy 需列名解析） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NegativeFeedback.class);
    }

    @BeforeEach
    void setUp() {
        // 单注入构造器（已有）：测试口仅 mapper，其余依赖为 null
        service = new NegativeFeedbackService(mapper);
    }

    /* ====================== 1. getByProjectId ====================== */

    @Test
    @DisplayName("[R27-P0-5#1] getByProjectId：项目维度列表，返回 mapper 列表")
    void getByProjectId_returnsList() {
        NegativeFeedback fb1 = NegativeFeedback.builder().id(1L).projectId(7L).severity("MEDIUM").build();
        NegativeFeedback fb2 = NegativeFeedback.builder().id(2L).projectId(7L).severity("HIGH").build();
        when(mapper.selectList(any())).thenReturn(List.of(fb1, fb2));

        List<NegativeFeedback> result = service.getByProjectId(7L);

        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).getProjectId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("[R27-P0-5#1] getByProjectId(null)：参数为 null ⇒ 返回空列表（防御性，不抛 NPE）")
    void getByProjectId_null_returnsEmpty() {
        List<NegativeFeedback> result = service.getByProjectId(null);

        assertThat(result).isNotNull().isEmpty();
    }

    /* ====================== 2. submit(NegativeFeedback) 简化版 ====================== */

    @Test
    @DisplayName("[R27-P0-5#2] submit(NegativeFeedback fb)：已存在 row 状态机 DRAFT→PENDING_DECISION 返 true")
    void submitByEntity_transitionsDraft() {
        NegativeFeedback fb = NegativeFeedback.builder().id(11L).status("DRAFT").projectId(1L).build();
        when(mapper.selectById(11L)).thenReturn(fb);
        when(mapper.updateById(any(NegativeFeedback.class))).thenReturn(1);

        boolean ok = service.submit(fb);

        assertThat(ok).isTrue();
        assertThat(fb.getStatus()).isEqualTo("PENDING_DECISION");
    }

    /* ====================== 4. deleteById 软删除 ====================== */

    @Test
    @DisplayName("[R27-P0-5#4] deleteById：按 id 软删除（del_flag=1），affected>0 返 true")
    void deleteById_returnsTrueOnSuccess() {
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean ok = service.deleteById(99L);

        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("[R27-P0-5#4] deleteById：affected=0 返 false")
    void deleteById_returnsFalseOnZeroAffected() {
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(0);

        boolean ok = service.deleteById(99L);

        assertThat(ok).isFalse();
    }

    /* ====================== 5. listBySeverity ====================== */

    @Test
    @DisplayName("[R27-P0-5#5] listBySeverity：按 severity 过滤列表")
    void listBySeverity_returnsList() {
        NegativeFeedback fb = NegativeFeedback.builder().id(1L).severity("HIGH").projectId(7L).build();
        when(mapper.selectList(any())).thenReturn(List.of(fb));

        List<NegativeFeedback> result = service.listBySeverity("HIGH");

        assertThat(result).isNotNull().hasSize(1);
        assertThat(result.get(0).getSeverity()).isEqualTo("HIGH");
    }
}
