package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.PermanentDeleteAudit;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PermanentDeleteAuditMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * R149 batch2b C3：永久清除服务单测。
 */
@Tag("dev")
class PermanentDeleteServiceTest {

    private PersonMapper personMapper;
    private ProjectMapper projectMapper;
    private KpiRecordMapper kpiRecordMapper;
    private PermanentDeleteAuditMapper auditMapper;
    private ObjectMapper objectMapper;
    private PermanentDeleteService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", null);
    private static final IpdActor LEADER = new IpdActor(2L, "leader", "GROUP_LEADER", 100L);

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "pda-test"),
                PermanentDeleteAudit.class);
    }

    @BeforeEach
    void setUp() {
        personMapper = mock(PersonMapper.class);
        projectMapper = mock(ProjectMapper.class);
        kpiRecordMapper = mock(KpiRecordMapper.class);
        auditMapper = mock(PermanentDeleteAuditMapper.class);
        objectMapper = new ObjectMapper();
        service = new PermanentDeleteService(personMapper, projectMapper, kpiRecordMapper,
            auditMapper, objectMapper);
    }

    @Test
    @DisplayName("① 二次确认：confirmCode 错误抛异常")
    void confirmCode_wrong() {
        assertThatThrownBy(() -> service.execute(ADMIN, "person", 100L, "WRONG_CODE"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("confirmCode");
        verifyNoInteractions(personMapper, projectMapper, kpiRecordMapper, auditMapper);
    }

    @Test
    @DisplayName("② 白名单：scenario 暂未建模抛异常")
    void entityType_scenario_rejected() {
        assertThatThrownBy(() -> service.execute(ADMIN, "scenario", 1L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("scenario 暂未建模");
    }

    @Test
    @DisplayName("③ 白名单：未知 entityType 抛异常")
    void entityType_unknown_rejected() {
        assertThatThrownBy(() -> service.execute(ADMIN, "unknown_entity", 1L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("④ 权限兜底：GROUP_LEADER 角色抛 FORBIDDEN")
    void role_notAdmin_rejected() {
        assertThatThrownBy(() -> service.execute(LEADER, "person", 100L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅 SUPER_ADMIN");
    }

    @Test
    @DisplayName("⑤ 流程：person 真删 + 审计快照写入")
    void person_deleteSuccess() {
        Person p = new Person();
        p.setId(100L); p.setName("张三"); p.setPersonType("RD_PM");
        when(personMapper.selectById(100L)).thenReturn(p);
        when(personMapper.deleteById(100L)).thenReturn(1);
        when(auditMapper.insert(any(PermanentDeleteAudit.class))).thenAnswer(invocation -> {
            PermanentDeleteAudit arg = invocation.getArgument(0);
            arg.setId(999L);
            return 1;
        });

        Long auditId = service.execute(ADMIN, "person", 100L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE);

        assertThat(auditId).isEqualTo(999L);
        verify(personMapper).selectById(100L);
        verify(personMapper).deleteById(100L);
        verify(auditMapper).insert(any(PermanentDeleteAudit.class));
    }

    @Test
    @DisplayName("⑥ 流程：project 真删 + 审计快照写入")
    void project_deleteSuccess() {
        Project p = new Project();
        p.setId(200L); p.setName("测试项目");
        when(projectMapper.selectById(200L)).thenReturn(p);
        when(projectMapper.deleteById(200L)).thenReturn(1);
        when(auditMapper.insert(any(PermanentDeleteAudit.class))).thenAnswer(invocation -> {
            PermanentDeleteAudit arg = invocation.getArgument(0);
            arg.setId(1000L);
            return 1;
        });

        Long auditId = service.execute(ADMIN, "project", 200L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE);

        assertThat(auditId).isEqualTo(1000L);
        verify(projectMapper).deleteById(200L);
        verify(auditMapper).insert(any(PermanentDeleteAudit.class));
    }

    @Test
    @DisplayName("⑦ 流程：kpi_record 真删 + 审计快照写入")
    void kpiRecord_deleteSuccess() {
        KpiRecord k = new KpiRecord();
        k.setId(300L); k.setProjectId(1L); k.setPeriod("2026-09");
        when(kpiRecordMapper.selectById(300L)).thenReturn(k);
        when(kpiRecordMapper.deleteById(300L)).thenReturn(1);
        when(auditMapper.insert(any(PermanentDeleteAudit.class))).thenAnswer(invocation -> {
            PermanentDeleteAudit arg = invocation.getArgument(0);
            arg.setId(1001L);
            return 1;
        });

        Long auditId = service.execute(ADMIN, "kpi_record", 300L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE);

        assertThat(auditId).isEqualTo(1001L);
        verify(kpiRecordMapper).deleteById(300L);
        verify(auditMapper).insert(any(PermanentDeleteAudit.class));
    }

    @Test
    @DisplayName("⑧ 实体不存在：抛 NOT_FOUND")
    void entity_notFound() {
        when(personMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.execute(ADMIN, "person", 999L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不存在");
        verify(personMapper, never()).deleteById(anyLong());
        verifyNoInteractions(auditMapper);
    }

    @Test
    @DisplayName("⑨ 真删失败（并发删）抛异常")
    void deleteById_concurrentDeleted() {
        Person p = new Person();
        p.setId(100L); p.setName("张三");
        when(personMapper.selectById(100L)).thenReturn(p);
        when(personMapper.deleteById(100L)).thenReturn(0); // 并发删
        assertThatThrownBy(() -> service.execute(ADMIN, "person", 100L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("物理删除失败");
        verify(auditMapper, never()).insert(any(PermanentDeleteAudit.class));
    }

    @Test
    @DisplayName("⑩ entityId 校验：<= 0 抛异常")
    void entityId_invalid() {
        assertThatThrownBy(() -> service.execute(ADMIN, "person", 0L,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> service.execute(ADMIN, "person", null,
            PermanentDeleteService.REQUIRED_CONFIRM_CODE))
            .isInstanceOf(IpdBusinessException.class);
    }
}
