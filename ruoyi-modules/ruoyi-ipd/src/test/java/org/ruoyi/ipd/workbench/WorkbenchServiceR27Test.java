package org.ruoyi.ipd.workbench;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.WorkbenchService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R27 P0-6：WorkbenchService 路径 2 函数补全 单测（TDD 红→绿）。
 *
 * <p>本类守「2 个新增方法签名 + 基本行为契约」：
 * <ul>
 *   <li>{@link WorkbenchService#myInitiated(Long)}：聚合 3 张业务单据（删除/系数/上市日期）create_by=personId</li>
 *   <li>{@link WorkbenchService#myPendingApprovals(Long)}：当前人待审批（业务单据+阶段动作）</li>
 * </ul>
 *
 * <p>纯 mock 注入 mapper，Lambda 列缓存通过 {@link TableInfoHelper#initTableInfo} 初始化。
 *
 * <p>@Tag("dev") 是项目级 surefire 守门——非 dev 标签不进入执行。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WorkbenchServiceR27Test {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private NotificationService notificationService;
    @Mock private DeletionRequestMapper deletionRequestMapper;
    @Mock private CoefficientChangeRequestMapper coefficientChangeRequestMapper;
    @Mock private LaunchDateChangeRequestMapper launchDateChangeRequestMapper;

    private WorkbenchService service;

    /** 纯 JVM 单测无 MP 运行时：手动初始化 lambda 列缓存（MyInitiatedTask 域） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MyInitiatedTask.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkbenchService(
            projectMapper, projectMemberMapper, stageActionMapper, notificationService,
            java.util.List.of(),  // aggregators 为空（myInitiated/myPendingApprovals 不走 aggregator）
            deletionRequestMapper, coefficientChangeRequestMapper, launchDateChangeRequestMapper);
    }

    /* ====================== 1. myInitiated ====================== */

    @Test
    @DisplayName("[R27-P0-6#1] myInitiated：聚合三表 create_by=personId，返回 List<MyInitiatedTask>")
    void myInitiated_aggregatesThreeSources() {
        long personId = 900101L;
        when(deletionRequestMapper.selectCount(any())).thenReturn(2L);
        when(coefficientChangeRequestMapper.selectCount(any())).thenReturn(1L);
        when(launchDateChangeRequestMapper.selectCount(any())).thenReturn(3L);

        List<MyInitiatedTask> result = service.myInitiated(personId);

        assertThat(result).isNotNull();
        // 期望：2+1+3 = 6 条 task（类型分别为 DELETION/COEFFICIENT/LAUNCH_DATE）
        assertThat(result).hasSize(6);
        long deletion = result.stream().filter(t -> "DELETION".equals(t.getTaskType())).count();
        long coefficient = result.stream().filter(t -> "COEFFICIENT".equals(t.getTaskType())).count();
        long launchDate = result.stream().filter(t -> "LAUNCH_DATE".equals(t.getTaskType())).count();
        assertThat(deletion).isEqualTo(2);
        assertThat(coefficient).isEqualTo(1);
        assertThat(launchDate).isEqualTo(3);
    }

    @Test
    @DisplayName("[R27-P0-6#1] myInitiated：personId 为空 ⇒ 返回空列表（防御性）")
    void myInitiated_null_returnsEmpty() {
        List<MyInitiatedTask> result = service.myInitiated(null);

        assertThat(result).isNotNull().isEmpty();
    }

    /* ====================== 2. myPendingApprovals ====================== */

    @Test
    @DisplayName("[R27-P0-6#2] myPendingApprovals：聚合三表中待当前人审批的记录")
    void myPendingApprovals_aggregatesThreeSources() {
        long personId = 900101L;
        when(deletionRequestMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(coefficientChangeRequestMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        List<MyInitiatedTask> result = service.myPendingApprovals(personId);

        // mock 状态下返空列表（绿路径）
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("[R27-P0-6#2] myPendingApprovals：personId 为空 ⇒ 返回空列表（防御性）")
    void myPendingApprovals_null_returnsEmpty() {
        List<MyInitiatedTask> result = service.myPendingApprovals(null);

        assertThat(result).isNotNull().isEmpty();
    }
}
