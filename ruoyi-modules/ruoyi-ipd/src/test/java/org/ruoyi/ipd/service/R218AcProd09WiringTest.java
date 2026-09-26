package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R218 卡2（U2）AC-PROD-09 契约测试：「待指派」需求超 5 工作日提醒的触发接线与通知对象对齐。
 *
 * <p>缺陷（看板 f62ab684 / R218 归因 DEF-B）：
 * ① GuestDemandService.notifyOverdueUnassigned 全仓零调用方（无 @Scheduled 覆盖、无 Controller 端点，
 *    真库 audit overdue_unassigned=0 行）；
 * ② 方法体只写 audit 不 publish 通知；
 * ③ 通知对象注释口径=超管，AC 文本=产品组组长。
 *
 * <p>契约断言（均以反射定位契约缝，红阶段可编译、以行为失败自证红）：
 * ① 存在 @Scheduled 调度器类接线本扫描（每日触发路径，R215-GAP-B1 同款惯例）；
 * ② 存在超管手动扫描端点（验收兜底，LegacyScanController 同款惯例）；
 * ③ notifyOverdueUnassigned 对逾期需求 publishDaily 真通知，接收人=该产品组组长
 *    （persons.personType=GROUP_LEADER 且 groupId=产品所属组，GateReviewService.collectLeaders 同源口径）。
 *
 * <p>mock 合法性：SUBMITTED+productId 有值+双 PM 空 = 游客选 ACTIVE 产品但项目无在职 PM 的真库写入路径
 * （GuestDemandService.submit→resolveDualPm 返回 project-no-pm 后原样入库，routedAt/PM 均空）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class R218AcProd09WiringTest {

    @Mock private RequirementMapper requirementMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private GuestDemandService.GuestRateLimiter rateLimiter;

    private GuestDemandService service;

    /** 固定时钟=2026-09-25(周五) 10:00，消除真实时钟摇摆（既有 setClock 缝）。 */
    private static final Date FIXED_NOW = date(2026, 9, 25, 10);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "r218-prod09");
        TableInfoHelper.initTableInfo(assistant, Person.class);
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
    }

    @BeforeEach
    void setUp() {
        service = new GuestDemandService(requirementMapper, productMapper,
            projectMemberMapper, auditLogService, rateLimiter);
        service.setClock(java.time.Clock.fixed(FIXED_NOW.toInstant(), java.time.ZoneId.systemDefault()));
    }

    private static Date date(int y, int m, int d, int hh) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, hh, 0, 0);
        return c.getTime();
    }

    /** 6 个工作日前的提交时间（阈值=5 工作日，须命中）。 */
    private static Date sixBizDaysBefore(Date from) {
        Calendar c = Calendar.getInstance();
        c.setTime(from);
        int sub = 0;
        while (sub < 6) {
            c.add(Calendar.DAY_OF_MONTH, -1);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) sub++;
        }
        return c.getTime();
    }

    private Requirement submittedUnassigned(Long id, Long productId) {
        Requirement r = Requirement.builder()
            .id(id).queryCode("AB12CD34").status("SUBMITTED")
            .source("PORTAL_GUEST").productId(productId)
            .marketPmId(null).rdPmId(null).title("ZK-控制器 - 智控科技").build();
        r.setCreateTime(sixBizDaysBefore(FIXED_NOW));
        return r;
    }

    private Person leader(Long id, Long groupId) {
        return Person.builder().id(id).name("产品组长" + id).personType("GROUP_LEADER")
            .groupId(groupId).delFlag("0").build();
    }

    // ===== 契约①：调度接线 =====

    @Test
    @DisplayName("AC-PROD-09-① 存在每日 @Scheduled 调度器，其 @Scheduled 方法触发 notifyOverdueUnassigned")
    void schedulerWiringExists() throws Exception {
        Class<?> scheduler = Class.forName("org.ruoyi.ipd.service.GuestDemandOverdueScheduler");
        Method scheduled = null;
        for (Method m : scheduler.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) {
                scheduled = m;
                break;
            }
        }
        assertThat(scheduled).as("调度器必须有 @Scheduled 方法（每日触发路径，IpdSchedulingConfig 已 @EnableScheduling）").isNotNull();

        // 行为验证：调度方法必须实际委托 service.notifyOverdueUnassigned()
        GuestDemandService svcMock = mock(GuestDemandService.class);
        Object instance = scheduler.getConstructor(GuestDemandService.class).newInstance(svcMock);
        scheduled.invoke(instance);
        verify(svcMock).notifyOverdueUnassigned();
    }

    // ===== 契约②：手动触发端点 =====

    @Test
    @DisplayName("AC-PROD-09-② 存在超管手动扫描端点类（LegacyScanController 同型，便于验收复测）")
    void manualScanEndpointExists() throws Exception {
        Class<?> controller = Class.forName("org.ruoyi.ipd.controller.GuestDemandOverdueScanController");
        Method scan = null;
        for (Method m : controller.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)) {
                scan = m;
                break;
            }
        }
        assertThat(scan).as("必须有 POST 手动扫描端点").isNotNull();
        assertThat(scan.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value())
            .containsExactly("/api/v1/guest-demands/overdue-scan");
    }

    // ===== 契约③：publish 真通知给产品组组长 =====

    @Test
    @DisplayName("AC-PROD-09-③ notifyOverdueUnassigned 对逾期需求 publishDaily 真通知给该产品组组长，且仍写 audit")
    void scanPublishesRealNotificationToProductGroupLeader() throws Exception {
        Requirement overdue = submittedUnassigned(500L, 1001L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(overdue));
        Product product = new Product();
        product.setId(1001L);
        product.setGroupId(20L);
        when(productMapper.selectById(1001L)).thenReturn(product);

        // 契约缝：GuestDemandService 须装配 PersonMapper + NotificationService（缺缝即红——自证未接线）
        Field notifField = findFieldOfType(service, NotificationService.class);
        Field personField = findFieldOfType(service, PersonMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        when(personMapper.selectList(any())).thenReturn(List.of(leader(777L, 20L)));
        notifField.set(service, notificationService);
        personField.set(service, personMapper);

        int notified = service.notifyOverdueUnassigned();

        assertThat(notified).isEqualTo(1);
        // AC 文本对齐：接收人=产品组组长 777（修复前注释口径=超管，属偏差）
        verify(notificationService).publishDaily(eq(777L), eq("DEMAND_OVERDUE_UNASSIGNED"),
            eq(NotificationService.KIND_ACTION), eq("requirement"), eq(500L),
            anyString(), anyString(), anyString(), any(Date.class));
        // audit 留痕不得丢（既有行为保持，真库取证面）
        verify(auditLogService).append(any());
    }

    @Test
    @DisplayName("AC-PROD-09-④ 无组长兜底：产品组解析不到组长 ⇒ 不静默，升级通知在任超管（ACTIVE SUPER_ADMIN）")
    void fallbackToSuperAdminWhenNoLeader() throws Exception {
        Requirement overdue = submittedUnassigned(501L, 1002L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(overdue));
        Product product = new Product();
        product.setId(1002L);
        product.setGroupId(21L);
        when(productMapper.selectById(1002L)).thenReturn(product);

        Field notifField = findFieldOfType(service, NotificationService.class);
        Field personField = findFieldOfType(service, PersonMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        // 组长查询返回空、超管查询返回 1 人：按 wrapper 先后顺序区分两次调用
        when(personMapper.selectList(any()))
            .thenReturn(List.of())
            .thenReturn(List.of(Person.builder().id(900L).name("超管").personType("SUPER_ADMIN")
                .employmentStatus("ACTIVE").accountStatus("ACTIVE").delFlag("0").build()));
        notifField.set(service, notificationService);
        personField.set(service, personMapper);

        int notified = service.notifyOverdueUnassigned();

        assertThat(notified).isEqualTo(1);
        verify(notificationService).publishDaily(eq(900L), eq("DEMAND_OVERDUE_UNASSIGNED"),
            eq(NotificationService.KIND_ACTION), eq("requirement"), eq(501L),
            anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    @DisplayName("AC-PROD-09-⑤ 未装配通知协作者时优雅降级（存量 5 参构造测试不破：仅 audit 不 NPE）")
    void degradesGracefullyWithoutNotificationWiring() {
        when(requirementMapper.selectList(any())).thenReturn(List.of(submittedUnassigned(502L, null)));

        int notified = service.notifyOverdueUnassigned();

        assertThat(notified).isEqualTo(1);
        verify(auditLogService).append(any());
    }

    /** 反射找契约缝：按类型扫描声明字段（含私有），缺缝即断言失败=行为红。 */
    private static Field findFieldOfType(Object target, Class<?> type) {
        for (Field f : target.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType()) && !java.lang.reflect.Modifier.isStatic(f.getType().getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        }
        // 也允许经 setter 装配的实现：检测是否存在 NotificationService/PersonMapper 的 set 方法
        for (Method m : target.getClass().getMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                if (type.isAssignableFrom(p) && m.getName().startsWith("set")) {
                    // 无字段仅有 setter 亦可（构造 scheduler 注入形态）——但 notify 路径需字段引用，此分支保留诊断信息
                    throw new AssertionError("契约偏差：" + target.getClass().getSimpleName()
                        + " 存在 setter " + m.getName() + " 但字段未声明（类型 " + type.getSimpleName() + "）");
                }
            }
        }
        throw new AssertionError("契约缺失：" + target.getClass().getSimpleName()
            + " 未装配 " + type.getSimpleName() + "（AC-PROD-09 通知链未接线，修复前必红）");
    }
}
