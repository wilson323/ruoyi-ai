package org.ruoyi.ipd.hr;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.HrPersonMirror;
import org.ruoyi.ipd.hr.HrResponse.BasicInfo;
import org.ruoyi.ipd.mapper.HrPersonMirrorMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HrPersonMirrorIngestService 单测（D10 落地，2026-09-29，{@code @Tag("dev")}）。
 *
 * <p>核心红线：<b>同 pernr 二次写不产生重复行</b>（幂等 upsert）——首写 INSERT、
 * 同值二写零写入、差异二写走 UPDATE 而非 INSERT。
 */
@Tag("dev")
@DisplayName("EHR-镜像 HrPersonMirrorIngestService: pernr 幂等 upsert")
class HrPersonMirrorIngestServiceTest {

    private HrPersonMirrorMapper mapper;
    private HrPersonMirrorIngestService service;

    @BeforeEach
    void setUp() {
        // 纯单测无 Spring/MyBatis 上下文：手动初始化 TableInfo，否则 LambdaUpdateWrapper.set 取不到列缓存
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), HrPersonMirror.class);
        mapper = mock(HrPersonMirrorMapper.class);
        service = new HrPersonMirrorIngestService(mapper);
    }

    private static BasicInfo sample() {
        BasicInfo i = new BasicInfo();
        i.nachn = "陈守一";
        i.rufnm = "";
        i.gesch = "1";
        i.natio = "CN";
        i.hireDate = "2005-02-24";
        i.leaveDate = "";
        i.phone = "13900000001";
        i.emailCom = "test@zkteco.com";
        i.orgeh = "10000342";
        i.deptname = "董总办(大连)";
        i.plans = "31000001";
        i.positionname = "M5总经理";
        i.positiongrade = "M5";
        i.qualificationlevel = "Q3";
        i.supervisorno = "00000001";
        i.supervisorname = "上级";
        i.certificate = "本科";
        i.insitute = "大连理工";
        i.lineOfStudy = "计算机";
        i.empcategory = "Regular";
        i.stat2 = "3";
        i.leaveFlag = "";
        return i;
    }

    @Test
    @DisplayName("首写 INSERT：pernr 不存在时 insert 一条并返回 true")
    void firstWrite_inserts() {
        when(mapper.selectOne(any())).thenReturn(null);

        boolean written = service.upsert("00000017", sample(), "MANUAL:1");

        assertThat(written).isTrue();
        ArgumentCaptor<HrPersonMirror> captor = ArgumentCaptor.forClass(HrPersonMirror.class);
        verify(mapper, times(1)).insert(captor.capture());
        verify(mapper, never()).update(any(), any());
        HrPersonMirror saved = captor.getValue();
        assertThat(saved.getPernr()).isEqualTo("00000017");
        assertThat(saved.getNachn()).isEqualTo("陈守一");
        assertThat(saved.getEmpcategory()).isEqualTo("Regular");
        // DEL_FLAG 不落业务值：仅标准审计 del_flag=0
        assertThat(saved.getDelFlag()).isEqualTo("0");
        assertThat(saved.getTriggerBy()).isEqualTo("MANUAL:1");
        assertThat(saved.getLastSyncAt()).isNotNull();
    }

    @Test
    @DisplayName("幂等红线：同 pernr 同值二写不重复行（insert 仅 1 次、update 不触发）")
    void secondWrite_sameValues_noDuplicateRow() {
        when(mapper.selectOne(any())).thenReturn(null);
        service.upsert("00000017", sample(), "MANUAL:1");

        // 二写：命中已存在行且字段全同
        HrPersonMirror existing = HrPersonMirror.builder()
            .pernr("00000017")
            .nachn("陈守一").rufnm("").gesch("1").natio("CN")
            .hireDate("2005-02-24").leaveDate("")
            .phone("13900000001").emailCom("test@zkteco.com")
            .orgeh("10000342").deptname("董总办(大连)")
            .plans("31000001").positionname("M5总经理").positiongrade("M5")
            .qualificationlevel("Q3").supervisorno("00000001").supervisorname("上级")
            .certificate("本科").insitute("大连理工").lineOfStudy("计算机")
            .empcategory("Regular").stat2("3").leaveFlag("")
            .delFlag("0")
            .build();
        when(mapper.selectOne(any())).thenReturn(existing);

        boolean written = service.upsert("00000017", sample(), "CRON_DAILY");

        assertThat(written).isFalse();
        verify(mapper, times(1)).insert(any(HrPersonMirror.class));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("差异二写走 UPDATE：字段变化时不新增行、只更新既有行")
    void secondWrite_changedValue_updates() {
        HrPersonMirror existing = HrPersonMirror.builder()
            .pernr("00000017").nachn("旧名").empcategory("Regular").stat2("3").leaveFlag("")
            .delFlag("0")
            .build();
        when(mapper.selectOne(any())).thenReturn(existing);

        boolean written = service.upsert("00000017", sample(), "CRON_DAILY");

        assertThat(written).isTrue();
        verify(mapper, never()).insert(any(HrPersonMirror.class));
        verify(mapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("pernr / BasicInfo 空缺直接跳过（零写入）")
    void blankPernrOrNullInfo_skipped() {
        assertThat(service.upsert(null, sample(), "MANUAL:1")).isFalse();
        assertThat(service.upsert("", sample(), "MANUAL:1")).isFalse();
        assertThat(service.upsert("00000017", null, "MANUAL:1")).isFalse();
        verify(mapper, never()).insert(any(HrPersonMirror.class));
        verify(mapper, never()).update(any(), any());
    }
}
