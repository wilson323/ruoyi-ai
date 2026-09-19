package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.config.IpdSchedulingConfig;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectScoreTaskMapper;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P3-2.3 调度入口接线契约（2026-09-19）：
 * dailyScanScheduled 必须带 @Scheduled 09:15（与 09:00/09:05 错峰不冲突）且
 * 自身声明 @Transactional（内部调用不走代理）；@EnableScheduling 全局开启；
 * 方法委托 scanLaunchedProjects（空项目列表冒烟，不写库）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectScoreScheduleCronTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper memberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ProductGroupMapper groupMapper;
    @Mock private ProjectScoreTaskMapper taskMapper;
    @Mock private org.ruoyi.ipd.service.NotificationService notificationService;
    @Mock private org.ruoyi.ipd.service.AuditLogService auditLogService;
    @Mock private IpdPermission permission;

    private ProjectScoreScheduleService service() {
        return new ProjectScoreScheduleService(projectMapper, memberMapper, personMapper,
            groupMapper, taskMapper, notificationService, auditLogService, permission);
    }

    @Test
    @DisplayName("dailyScanScheduled 存在且带 @Scheduled 09:15 + @Transactional")
    void scheduledEntryAnnotationContract() throws NoSuchMethodException {
        Method method = ProjectScoreScheduleService.class.getMethod("dailyScanScheduled");
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
        assertThat(scheduled).as("dailyScanScheduled 必须标注 @Scheduled").isNotNull();
        assertThat(scheduled.cron()).as("cron 固定 09:15").isEqualTo("0 15 9 * * ?");
        assertThat(CronExpression.parse(scheduled.cron()).next(LocalDateTime.of(2026, 9, 19, 8, 0)))
            .as("cron 表达式可解析且下次触发为 09:15")
            .isEqualTo(LocalDateTime.of(2026, 9, 19, 9, 15));
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(tx).as("入口必须自带 @Transactional（this 内部调用不走代理）").isNotNull();
        assertThat(tx.rollbackFor()).as("回滚口径与 scanLaunchedProjects 一致").contains(Exception.class);
    }

    @Test
    @DisplayName("错峰契约：09:00/09:05/09:15 三任务整点分钟互不冲突")
    void staggeredCronContract() throws NoSuchMethodException {
        String resign = cronOf(org.ruoyi.ipd.service.PersonResignEscalator.class, "dailyEscalationJob");
        String handover = cronOf(org.ruoyi.ipd.service.HandoverOverdueScanner.class, "dailyOverdueScan");
        String score = cronOf(ProjectScoreScheduleService.class, "dailyScanScheduled");
        assertThat(List.of(resign, handover, score))
            .as("三个整点任务的 cron 两两不同（错峰）")
            .doesNotHaveDuplicates();
        assertThat(score).as("评分扫描 09:15 晚于移交超时 09:05").isEqualTo("0 15 9 * * ?");
    }

    @Test
    @DisplayName("@EnableScheduling 全局开启（IpdSchedulingConfig）")
    void enableSchedulingOn() {
        assertThat(IpdSchedulingConfig.class.getAnnotation(EnableScheduling.class))
            .as("OPS-04：@EnableScheduling 必须开启，否则 @Scheduled 静默不跑")
            .isNotNull();
    }

    @Test
    @DisplayName("dailyScanScheduled 委托 scanLaunchedProjects（空项目列表冒烟）")
    void delegatesToScan() {
        when(projectMapper.selectList(any())).thenReturn(List.of());
        service().dailyScanScheduled();
        org.mockito.Mockito.verify(projectMapper).selectList(any());
    }

    private static String cronOf(Class<?> type, String methodName) throws NoSuchMethodException {
        Method method = type.getMethod(methodName);
        Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
        assertThat(scheduled).as(type.getSimpleName() + "." + methodName + " 必须有 @Scheduled").isNotNull();
        return scheduled.cron();
    }
}
