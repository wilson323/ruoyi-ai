package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.NotificationEventMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-4.4 深管逾期与轻管免打扰（AC-IPD-12 / AC-IPD-13）。
 *
 * <p><b>重写说明（卡片 b828c017 退回执行）</b>：原 {@code P144AcceptanceTest}（7 用例，
 * 面向旧 {@code OverdueReminderService}）已在 commit de52088d（R189/R25 死代码清理）随被删
 * 服务连带删除（R25 清单失真漏列），导致本卡专属验收测试断链。本测试<b>按现网 main 重写</b>，
 * 钉住现网逾期链路（R219 卡④ ef20c06a）的真实行为：
 *
 * <ul>
 *   <li>AC-IPD-12 深管逾期 ⇒ 主责人（在册未退出）收 ACTION_OVERDUE 每日提醒：
 *       {@code StageActionService.notifyOverdueActions()}（扫描 dueDate 非空且已过期的
 *       开放动作 NOT_STARTED/IN_PROGRESS/DELAYED）→ {@code NotificationService.publishDaily}
 *       （dedupKey 含自然日 yyyyMMdd，同日重扫不重发、次日再提醒）；</li>
 *   <li>AC-IPD-12 重复扫描不多通知 ⇒ dedupKey 撞唯一键返回既有行（真实 NotificationService 验证）；</li>
 *   <li>已 DELAYED 动作每日续提醒，且状态机幂等（同态 transit 不写库、不写审计=不重复标记）；</li>
 *   <li>主责人已退出（exitDate 非空）⇒ 不接收提醒（查询谓词 isNull(exitDate)）；</li>
 *   <li>AC-IPD-13 轻管免打扰 ⇒ ①轻管动作禁 DELAYED（BR-IPD-05，状态机拒绝=永不标记）；
 *       ②提醒扫描以「dueDate 非空 + 已过期 + 开放态」为唯一触达谓词——轻管动作无 dueDate
 *       业务写入口（R218 lane2 归因：LIGHT 动作无法赋 due_date，全库 0 条 LIGHT 带 due_date），
 *       故永不进入逾期提醒（免打扰）。</li>
 * </ul>
 *
 * <p>口径差异登记（相对旧实现，均为现网 main 既定口径，非本测试引入）：
 * 旧 OverdueReminderService 的「深管逾期自动 transit DELAYED（actor=SYSTEM）」与开关
 * {@code action.overdueReminder.enabled} 在现网不存在——现网逾期标记仍走 P1-4.3 transit
 * 唯一入口人工/上游触发，提醒链只负责通知。本测试只断言现网真实行为，不做假绿。
 */
@Tag("dev")
class P144AcceptanceTest {

    private StageActionMapper actionMapper;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper memberMapper;
    private NotificationService notificationService;
    private IAuditLogService auditLogService;
    private StageActionService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "p144-acceptance");
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, NotificationEvent.class);
    }

    @BeforeEach
    void setUp() {
        actionMapper = mock(StageActionMapper.class);
        projectMapper = mock(ProjectMapper.class);
        memberMapper = mock(ProjectMemberMapper.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectMapper.selectById(any())).thenReturn(
            Project.builder().id(100L).status("ACTIVE").delFlag("0").build());
        service = new StageActionService(actionMapper, mock(DeliverableMapper.class), auditLogService,
            mock(ProjectStageMapper.class), projectMapper);
        service.setNotificationService(notificationService);
        service.setProjectMemberMapper(memberMapper);
        service.setProductGroupMapper(mock(ProductGroupMapper.class));
    }

    private static StageAction deepOverdue(Long id, String ownerRole, Long projectId) {
        return StageAction.builder()
            .id(id).projectId(projectId).stageId(10L).actionCode("C01").actionName("概念评估")
            .ownerRole(ownerRole).depth("DEEP").status("IN_PROGRESS")
            .isBlocking("1").isBioFeature("0").version(0)
            .dueDate(new Date(System.currentTimeMillis() - 86_400_000L))
            .build();
    }

    private static StageAction lightAction(Long id) {
        return StageAction.builder()
            .id(id).projectId(100L).stageId(10L).actionCode("C01").actionName("概念评估")
            .ownerRole("MARKET_PM").depth("LIGHT").status("IN_PROGRESS")
            .isBlocking("0").isBioFeature("0").version(0)
            .build();
    }

    private static ProjectMember activeMember(Long personId, String role) {
        return ProjectMember.builder()
            .projectId(100L).personId(personId).role(role).exitDate(null).build();
    }

    @Test
    @DisplayName("AC-IPD-12 深管逾期：主责人（在册未退出）收 ACTION_OVERDUE 每日提醒（publishDailyAfterCommit 按日 dedup 通道）")
    void deepOverdue_ownerReminded_viaDailyDedupChannel() {
        when(actionMapper.selectList(any())).thenReturn(List.of(deepOverdue(1L, "MARKET_PM", 100L)));
        when(memberMapper.selectList(any())).thenReturn(List.of(activeMember(101L, "MARKET_PM")));

        assertThat(service.notifyOverdueActions()).isEqualTo(1);
        verify(notificationService).publishDailyAfterCommit(eq(101L), eq(NotificationService.Types.ACTION_OVERDUE),
            eq(NotificationService.KIND_ACTION), eq("stage_action"), eq(1L),
            anyString(), anyString(), eq("/projects/100"), any(Date.class));
    }

    @Test
    @DisplayName("AC-IPD-12 重复扫描不多通知：同日重扫 dedupKey 撞唯一键只落一行（真实 NotificationService）")
    void repeatScan_sameDay_singleNotificationRow() {
        NotificationEventMapper eventMapper = mock(NotificationEventMapper.class);
        NotificationChannel channel = mock(NotificationChannel.class);
        when(channel.code()).thenReturn("IN_APP");
        List<NotificationEvent> stored = new ArrayList<>();
        when(eventMapper.insert(any(NotificationEvent.class))).thenAnswer(inv -> {
            NotificationEvent row = inv.getArgument(0);
            for (NotificationEvent e : stored) {
                if (e.getDedupKey().equals(row.getDedupKey())) {
                    // 真库 notifications.dedup_key 唯一键语义
                    throw new DuplicateKeyException("dup dedupKey=" + row.getDedupKey());
                }
            }
            stored.add(row);
            return 1;
        });
        when(eventMapper.selectOne(any())).thenAnswer(inv -> stored.get(0));

        service.setNotificationService(new NotificationService(eventMapper, channel));
        when(actionMapper.selectList(any())).thenReturn(List.of(deepOverdue(1L, "MARKET_PM", 100L)));
        when(memberMapper.selectList(any())).thenReturn(List.of(activeMember(101L, "MARKET_PM")));

        assertThat(service.notifyOverdueActions()).isEqualTo(1);
        assertThat(service.notifyOverdueActions()).isEqualTo(1);

        assertThat(stored).hasSize(1);
        verify(eventMapper, times(2)).insert(any(NotificationEvent.class));
        assertThat(stored.get(0).getDedupKey())
            .matches("stage_action:ACTION_OVERDUE:1:101:\\d{8}").as("dedupKey 含自然日（同日幂等、次日再提醒）");
    }

    @Test
    @DisplayName("已 DELAYED 动作：状态机幂等不重复标记，但每日仍续提醒（标记一次、提醒每日）")
    void alreadyDelayed_notReMarked_butStillReminded() {
        StageAction a = deepOverdue(2L, "MARKET_PM", 100L);
        a.setStatus("DELAYED");
        when(actionMapper.selectById(2L)).thenReturn(a);

        StageAction out = service.transit(2L, "DELAYED", null, "SYSTEM");
        assertThat(out.getStatus()).isEqualTo("DELAYED");
        verify(actionMapper, never()).updateById(any(StageAction.class));
        verify(auditLogService, never()).append(any(AuditLog.class));

        when(actionMapper.selectList(any())).thenReturn(List.of(a));
        when(memberMapper.selectList(any())).thenReturn(List.of(activeMember(101L, "MARKET_PM")));
        assertThat(service.notifyOverdueActions()).isEqualTo(1);
        verify(notificationService).publishDailyAfterCommit(eq(101L), eq(NotificationService.Types.ACTION_OVERDUE),
            eq(NotificationService.KIND_ACTION), eq("stage_action"), eq(2L),
            anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    @DisplayName("主责人已退出（exitDate 非空被谓词排除）：只扫描不通知，不抛错")
    void ownerExited_noNotification() {
        when(actionMapper.selectList(any())).thenReturn(List.of(deepOverdue(3L, "MARKET_PM", 100L)));
        when(memberMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.notifyOverdueActions()).isZero();
        verify(notificationService, never())
            .publishDailyAfterCommit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-IPD-13 轻管免打扰①：轻管动作禁 DELAYED（BR-IPD-05 状态机拒绝=永不标记）")
    void lightOverdue_delayedRejected_neverMarked() {
        StageAction light = lightAction(4L);
        when(actionMapper.selectById(4L)).thenReturn(light);

        assertThatThrownBy(() -> service.transit(4L, "DELAYED", null, "SYSTEM"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不支持「已延期」");
        verify(actionMapper, never()).updateById(any(StageAction.class));
    }

    @Test
    @DisplayName("AC-IPD-13 轻管免打扰②：提醒扫描触达谓词钉死「dueDate 非空+已过期+开放态」——轻管无 dueDate 写入口即永不触达")
    void reminderScan_predicateDueDateOnly_lightNeverTouched() {
        when(actionMapper.selectList(any())).thenReturn(List.of());

        service.notifyOverdueActions();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<StageAction>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(actionMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        String dueCol = columnOf("dueDate");
        String statusCol = columnOf("status");
        assertThat(sql).as("只触达 dueDate 非空动作（轻管无 dueDate ⇒ 免打扰）").contains(dueCol + " IS NOT NULL");
        assertThat(sql).as("只触达已过期动作").contains(dueCol + " <");
        assertThat(sql).as("只触达开放态（NOT_STARTED/IN_PROGRESS/DELAYED）").contains(statusCol + " IN");
    }

    private static String columnOf(String property) {
        TableInfo info = TableInfoHelper.getTableInfo(StageAction.class);
        return info.getFieldList().stream()
            .filter(f -> property.equals(f.getProperty()))
            .findFirst().orElseThrow()
            .getColumn();
    }
}
