package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前Java种子逐项遵循DOC-05已确认合同；旧SQL仅锁定历史事实，禁止视作新库权威。
 * 历史SQL仍保留33项/15否决及认证模板；当前种子为33项/14否决。
 */
@Tag("dev")
class IpdSeedConsistencyTest {

    private static final Path SEED = Paths.get("../../docs/script/sql/update/2026-09-05-ipd-p0-seed-elements.sql");

    private String seed() throws IOException {
        assertThat(Files.exists(SEED)).as("种子 SQL 存在: " + SEED.toAbsolutePath()).isTrue();
        return Files.readString(SEED.toAbsolutePath());
    }

    @Test
    @DisplayName("要素总数 = 33，G1..G5 = 7/6/5/8/7")
    void elementCounts() throws IOException {
        String sql = seed();
        Matcher m = Pattern.compile("INSERT (?:IGNORE )?INTO gate_review_elements").matcher(sql);
        int total = 0;
        while (m.find()) {
            total++;
        }
        assertThat(total).isEqualTo(33);
        assertThat(countGate(sql, "'G1'")).isEqualTo(7);
        assertThat(countGate(sql, "'G2'")).isEqualTo(6);
        assertThat(countGate(sql, "'G3'")).isEqualTo(5);
        assertThat(countGate(sql, "'G4'")).isEqualTo(8);
        assertThat(countGate(sql, "'G5'")).isEqualTo(7);
    }

    @Test
    @DisplayName("历史SQL固定15否决位（已被DOC-05覆盖；禁止作当前合同权威）")
    void vetoCount() throws IOException {
        String sql = seed();
        Matcher m = Pattern.compile("INSERT (?:IGNORE )?INTO gate_review_elements[^;]*?, '1', [0-9]+, '1',").matcher(sql);
        int veto = 0;
        while (m.find()) {
            veto++;
        }
        assertThat(veto).isEqualTo(15);
    }

    @Test
    @DisplayName("当前Java种子逐项等于DOC-05；历史SQL的G2-6否决显式标记，不作新权威")
    void currentJavaMatchesOwnerContractEveryField() throws Exception {
        var mapper = org.mockito.Mockito.mock(org.ruoyi.ipd.mapper.GateElementMapper.class);
        org.mockito.Mockito.when(mapper.selectSeedPreflightIncludingDeleted()).thenReturn(java.util.List.of());
        new org.ruoyi.ipd.config.IpdGateElementSeedInitializer(mapper).run(null);
        var captured = org.mockito.ArgumentCaptor.forClass(org.ruoyi.ipd.domain.GateElement.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(33)).insert(captured.capture());
        var actual = captured.getAllValues().stream().collect(java.util.stream.Collectors.toMap(
            org.ruoyi.ipd.domain.GateElement::getElementCode, java.util.function.Function.identity()));
        var expected = new java.util.LinkedHashMap<String,String[]>();
        for (String line : Files.readAllLines(Paths.get("../../docs/ipd-系统说明/工程合同/DOC-05.md"))) {
            if (!line.matches("^\\| G[1-5]-[1-9] \\|.*")) continue;
            var fields = line.split("\\|", -1);
            expected.put(fields[1].trim(), new String[]{fields[2].trim(), fields[4].trim(), "是".equals(fields[5].trim()) ? "1" : "0"});
        }
        assertThat(expected).hasSize(33);
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((id, fields) -> {
            var row = actual.get(id);
            assertThat(row.getGateCode()).isEqualTo(id.substring(0, 2));
            assertThat(row.getElementName()).as(id).isEqualTo(fields[0]);
            assertThat(row.getPassStandard()).as(id).isEqualTo(fields[1]);
            assertThat(row.getIsVeto()).as(id).isEqualTo(fields[2]);
        });
        assertThat(actual.values().stream().filter(row -> "1".equals(row.getIsVeto())).map(org.ruoyi.ipd.domain.GateElement::getElementCode))
            .containsExactlyInAnyOrder("G1-2","G1-4","G1-5","G1-6","G1-7","G2-1","G2-3","G2-4","G2-5","G4-1","G4-2","G4-6","G5-3","G5-7");
        assertThat(seed()).contains("'G2-6', '认证与法规清单确认'").contains("缺失或周期冲突=否决");
        assertThat(actual.get("G2-6").getIsVeto()).isEqualTo("0");
    }

    @Test
    @DisplayName("认证模板 = 21 项（P1-3 增补拉美/国际后）")
    void certCount() throws IOException {
        String sql = seed();
        Matcher m = Pattern.compile("INSERT (?:IGNORE )?INTO cert_templates").matcher(sql);
        int total = 0;
        while (m.find()) {
            total++;
        }
        assertThat(total).isEqualTo(21);
        for (String cc : new String[]{"'CN'", "'US'", "'EU'", "'SA'", "'AE'", "'IN'", "'KR'", "'JP'", "'AU'", "'BR'", "'MX'", "'CB'", "'IEC'"}) {
            assertThat(sql).contains(cc);
        }
        assertThat(sql).contains("SABER/SASO");
        assertThat(sql).contains("GDPR");
        assertThat(sql).contains("ANATEL");
        assertThat(sql).contains("IEC 62443");
    }

    private int countGate(String sql, String gate) {
        Matcher m = Pattern.compile(Pattern.quote(gate) + ", 'G[0-9]-[0-9]+'").matcher(sql);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}