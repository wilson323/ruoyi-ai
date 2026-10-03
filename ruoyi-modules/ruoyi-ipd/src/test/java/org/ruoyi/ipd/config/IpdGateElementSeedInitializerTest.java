package org.ruoyi.ipd.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.GateElementMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页47 事故链哨兵：Seed 值域映射必须正确（Y→1 否决、N→0），且 enabled/status/version/dual 归一。
 *
 * <p>本测试是「会红哨兵」——在三处历史错误版本上均失败：
 * ① main 旧版 .isVeto(def[4]) 直接把 Y/N 存进 is_veto（14 否决位存成 'Y'，业务侧 '1'.equals 判不出）；
 * ② ed997168 版 .isVeto("1".equals(def[4])) 因 def[4] 恒为 Y/N 永不等于 "1"，14 否决位全灭成 '0'；
 * ③ 仅 .isVeto("Y".equals(def[4]) ? "1" : "0") 正确映射出 14 否决位。
 */
@Tag("dev")
class IpdGateElementSeedInitializerTest {

    @Test
    @DisplayName("Seed 33 项：14 否决位 isVeto='1'、19 非否决 '0'，无 Y/N 泄漏，enabled/status/version/dual 归一")
    void seedMapsVetoAndNormalizesFields() throws Exception {
        GateElementMapper mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of());
        when(mapper.insert(any(GateElement.class))).thenReturn(1);

        new IpdGateElementSeedInitializer(mapper).run(null);

        ArgumentCaptor<GateElement> captor = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captor.capture());
        List<GateElement> all = captor.getAllValues();

        assertThat(all).hasSize(33);
        // 核心：14 否决位（Y→1），19 非否决（N→0）
        assertThat(all.stream().filter(x -> "1".equals(x.getIsVeto())).count()).isEqualTo(14L);
        assertThat(all.stream().filter(x -> "0".equals(x.getIsVeto())).count()).isEqualTo(19L);
        // 无字面 Y/N 泄漏（main 旧版病根）
        assertThat(all).noneMatch(x -> "Y".equals(x.getIsVeto()) || "N".equals(x.getIsVeto()));
        // 归一字段（main 旧版 enabled='Y'/status='PUBLISHED'/version=0/dual='N' 全错）
        assertThat(all).allMatch(x -> "1".equals(x.getEnabled()));
        assertThat(all).allMatch(x -> "published".equals(x.getStatus()));
        assertThat(all).allMatch(x -> Integer.valueOf(1).equals(x.getVersion()));
        assertThat(all).allMatch(x -> "0".equals(x.getVetoDualRequired()));
    }
    @Test void oldNumberingBlocksAllSeedWritesWithoutOverwriting() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(GateElement.builder().gateCode("G3")
            .elementCode("G3-01").elementName("关键功能实现").isVeto("1").status("published").enabled("1").delFlag("0").build()));
        new IpdGateElementSeedInitializer(mapper).run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(GateElement.class));
    }

    @Test void canonicalRowsAreIdempotentAndFieldConflictsWriteNothing() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of());
        when(mapper.insert(any(GateElement.class))).thenReturn(1);
        var initializer = new IpdGateElementSeedInitializer(mapper);
        initializer.run(null);
        var captured = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captured.capture());
        var rows = captured.getAllValues();
        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(rows);
        initializer.run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
        for (var conflict : List.of(
            GateElement.builder().gateCode("G2").elementCode("G2-6").elementName("认证与法规清单确认").isVeto("1").passStandard("旧否决标准").build(),
            GateElement.builder().gateCode("G3").elementCode("G3-1").elementName("其他名称").isVeto("0").passStandard("其他标准").build())) {
            when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(conflict));
            initializer.run(null);
        }
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
    }

    @Test void preflightConflictAfterMatchingRowsNeverPartiallySeeds() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(
            GateElement.builder().gateCode("G1").elementCode("G1-1").elementName("市场机会真实性")
                .passStandard("≥5家目标客户一手验证或≥1家客户书面意向；客户ID、记录及附件可追；一手验证门槛可配置").isVeto("0").build(),
            GateElement.builder().gateCode("G5").elementCode("G5-07").status("published").enabled("1").delFlag("0").build()));
        new IpdGateElementSeedInitializer(mapper).run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
    }
    @Test void deletedCanonicalIdentifierIsAReadOnlyConflictNotAnEmptyTable() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(
            GateElement.builder().gateCode("G1").elementCode("G1-1").elementName("市场机会真实性")
                .passStandard("≥5家目标客户一手验证或≥1家客户书面意向；客户ID、记录及附件可追；一手验证门槛可配置")
                .isVeto("0").delFlag("1").build()));
        new IpdGateElementSeedInitializer(mapper).run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
        String sql = GateElementMapper.class.getMethod("selectSeedPreflightIncludingDeleted")
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertThat(sql).contains("gate_review_elements").contains("del_flag");
        assertThat(sql).doesNotContain("del_flag =", "del_flag='");
    }
    @Test void legalCustomDraftArchivedAndPublishedDefinitionsDoNotBlockCanonicalSeed() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(
            GateElement.builder().gateCode("G1").elementCode("CUSTOM-1").status("draft").delFlag("0").build(),
            GateElement.builder().gateCode("G2").elementCode("G2-ARCH-01").status("archived").delFlag("0").build(),
            GateElement.builder().gateCode("G1").elementCode("G1-8").status("published").delFlag("0").build()));
        when(mapper.insert(any(GateElement.class))).thenReturn(1);
        new IpdGateElementSeedInitializer(mapper).run(null);
        verify(mapper, times(33)).insert(any(GateElement.class));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(GateElement.class));
    }
    @Test void archivedDisabledLegacySeedDoesNotBlockCanonicalIdempotence() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of());
        when(mapper.insert(any(GateElement.class))).thenReturn(1);
        var initializer = new IpdGateElementSeedInitializer(mapper);
        initializer.run(null);
        var captured = ArgumentCaptor.forClass(GateElement.class);
        verify(mapper, times(33)).insert(captured.capture());
        var rows = new java.util.ArrayList<>(captured.getAllValues());
        rows.add(GateElement.builder().gateCode("G3").elementCode("G3-01").elementName("旧关键功能实现")
            .isVeto("1").status("archived").enabled("0").delFlag("0").build());
        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(rows);
        initializer.run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(GateElement.class));
    }
}
