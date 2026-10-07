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
        // 全量33行都在、其中一行漂移：该行保留不覆盖，且无缺号故不新增任何行。
        for (var conflict : List.of(
            GateElement.builder().gateCode("G2").elementCode("G2-6").elementName("认证与法规清单确认").isVeto("1").passStandard("旧否决标准").delFlag("0").build(),
            GateElement.builder().gateCode("G3").elementCode("G3-1").elementName("其他名称").isVeto("0").passStandard("其他标准").delFlag("0").build())) {
            var full = new java.util.ArrayList<GateElement>();
            for (var r : rows) {
                full.add(r.getElementCode().equals(conflict.getElementCode()) ? conflict : r);
            }
            when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(full);
            initializer.run(null);
        }
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(GateElement.class));
    }

    // 已于 2026-10-07 移除 driftedRowDoesNotBlockSeedingOfMissingElements：
    // 该用例断言「非空表按缺号补种」，G2 守卫上线后行为不可达——owner 口径改为
    // 「表非空即一行不插一行不改写」。新口径由 IpdGateElementSeedTableEmptyGuardTest 覆盖。
    // ⚠️ 不要照 git history 把它加回来，除非 owner 明确把守卫放宽回按缺号补种。

    /**
     * 预检 SQL 的口径留档：必须是裸查询、不带 del_flag 过滤。
     * 实体 GateElement.delFlag 上有 @TableLogic，MyBatis-Plus 会给 selectCount 自动追加
     * del_flag=0；若守卫改用 selectCount 计数，软删行被滤掉，全软删的表会被误判成空表
     * 而重插 33 条。本断言锁死「无 del_flag 过滤」这一前提。
     */
    @Test void seedPreflightSqlMustNotFilterSoftDeletedRows() throws Exception {
        String sql = GateElementMapper.class.getMethod("selectSeedPreflightIncludingDeleted")
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertThat(sql).contains("gate_review_elements").contains("del_flag");
        assertThat(sql).doesNotContain("del_flag =", "del_flag='");
    }

    /**
     * 存活中的旧零填充编号（G5-07 published/enabled）会整批中止：此刻补种 G5-7 会并存两套编号。
     * 这是整批中止唯一保留的情形——与「规范编号已存在但漂移」不同，它会产生重复编号集合。
     */
    @Test void liveLegacyNumberedRowAbortsWholeBatchToAvoidTwoNumberingSets() throws Exception {
        var mapper = mock(GateElementMapper.class);
        when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(List.of(
            GateElement.builder().gateCode("G1").elementCode("G1-1").elementName("市场机会真实性")
                .passStandard("≥5家目标客户一手验证或≥1家客户书面意向；客户ID、记录及附件可追；一手验证门槛可配置").isVeto("0").build(),
            GateElement.builder().gateCode("G5").elementCode("G5-07").status("published").enabled("1").delFlag("0").build()));
        new IpdGateElementSeedInitializer(mapper).run(null);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(GateElement.class));
    }

    // 已于 2026-10-07 移除 deletedCanonicalIdentifierIsAReadOnlyConflictNotAnEmptyTable：
    // 它断言「1 行软删存量 ⇒ 补种其余 32 条」，属非空表补种语义，G2 守卫下不可达
    // （表现在是 1 行存量 ⇒ 0 插入）。新口径见 IpdGateElementSeedTableEmptyGuardTest。
    // 该用例内的 SQL 口径断言未随删除丢失，已拆出为上方 seedPreflightSqlMustNotFilterSoftDeletedRows。

    /**
     * 2026-10-04 G2-6 对齐脚本哨兵：脚本文本还在就必须同时具备三件事——
     * 目标只有 G2-6、is_veto 段幂等、文本段以 SQL seed 原文为守卫（不覆盖人工改写行）。
     * 脚本被删除或守卫被削弱时本测试变红。
     */
    @Test void g26AlignmentScriptStaysScopedAndIdempotent() throws Exception {
        var script = java.nio.file.Paths.get("../../docs/script/sql/update/2026-10-04-ipd-g2-6-veto-align.sql");
        assertThat(java.nio.file.Files.exists(script)).as("G2-6 对齐脚本存在: " + script.toAbsolutePath()).isTrue();
        String sql = java.nio.file.Files.readString(script.toAbsolutePath());
        assertThat(sql).contains("SET is_veto = '0'");
        assertThat(sql).contains("AND is_veto = '1'");
        assertThat(sql).contains("AND pass_standard = '目标市场强制认证全部列入且周期匹配；缺失或周期冲突=否决'");
        assertThat(sql).contains("目标市场认证清单及周期进行中也可通过G2；保留当前缺口、责任人、计划和后续结果检查关联");
        // 只动 G2-6：剔掉注释行后，生效的 UPDATE 必须恰好两条（is_veto / pass_standard），且各自按 G2-6 定位。
        String live = sql.lines().filter(line -> !line.trim().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
        var updates = java.util.Arrays.stream(live.split(";")).map(String::trim)
            .filter(stmt -> stmt.startsWith("UPDATE gate_review_elements")).toList();
        assertThat(updates).hasSize(2);
        updates.forEach(stmt -> assertThat(stmt).contains("element_code = 'G2-6'"));
    }
    // 已于 2026-10-07 移除 legalCustomDraftArchivedAndPublishedDefinitionsDoNotBlockCanonicalSeed：
    // 它断言「3 行 custom/draft/archived 存量 ⇒ 仍灌满 33 条」，属非空表补种语义，
    // G2 守卫下不可达（表现在是任何存量 ⇒ 0 插入，合法自定义行也不例外）。
    // 新口径见 IpdGateElementSeedTableEmptyGuardTest。
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
