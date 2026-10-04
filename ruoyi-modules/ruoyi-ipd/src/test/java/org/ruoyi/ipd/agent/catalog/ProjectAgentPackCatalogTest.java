package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentPackCatalogTest {
    final CapabilityManifest builtin = CapabilityManifest.load(CapabilityManifest.DEFAULT_RESOURCE, new ObjectMapper());
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ProjectAgentPackCatalog catalog = new ProjectAgentPackCatalog(builtin, jdbc, new ObjectMapper());

    Map<String,Object> row(String status) {
        return Map.of("id", 1L, "code", "market-research", "version", "v1", "name", "管理配置",
            "description", "管理配置", "stages", "[\"CONCEPT\"]", "action_codes", "[\"C02\"]",
            "status", status, "del_flag", "0");
    }
    @Test void administratorOverrideIsReadAgainAndTenantScoped() {
        when(jdbc.queryForList(contains("FROM ipd_capability_pack WHERE"), eq("tenant-a")))
            .thenReturn(List.of(row("ACTIVE")), List.of(row("DISABLED")));
        var s=builtin.skill("competitor-analysis-ipd").orElseThrow();
        when(jdbc.queryForList(contains("FROM ipd_capability_pack_item"), eq("tenant-a"), eq(1L)))
            .thenReturn(List.of(Map.of("item_type", "SKILL", "item_ref", s.name(), "item_version", s.version(), "sha256", s.sha256())));
        when(jdbc.queryForList(contains("FROM ipd_action_skill_map"), eq("tenant-a"), eq("C02")))
            .thenReturn(List.of(Map.of("skill_names", "[\"competitor-analysis-ipd\"]")));
        assertThat(catalog.pack("tenant-a", "market-research", "v1").orElseThrow().actionCodes()).containsExactly("C02");
        assertThat(catalog.pack("tenant-a", "market-research", "v1")).isEmpty();
        verify(jdbc, times(2)).queryForList(contains("FROM ipd_capability_pack WHERE"), eq("tenant-a"));
    }
    @Test void badDigestCannotSilentlyFallBackToBuiltin() {
        when(jdbc.queryForList(contains("FROM ipd_capability_pack WHERE"), eq("tenant-a"))).thenReturn(List.of(row("ACTIVE")));
        when(jdbc.queryForList(contains("FROM ipd_capability_pack_item"), eq("tenant-a"), eq(1L)))
            .thenReturn(List.of(Map.of("item_type", "SKILL", "item_ref", "competitor-analysis-ipd", "item_version", "1.1.0", "sha256", "bad")));
        assertThatThrownBy(() -> catalog.packs("tenant-a")).isInstanceOf(IllegalStateException.class).hasMessageContaining("摘要不一致");
    }
    @Test void actionWithoutBoundSkillIsRejectedBeforePlanning() {
        when(jdbc.queryForList(contains("FROM ipd_capability_pack WHERE"), eq("tenant-a"))).thenReturn(List.of(row("ACTIVE")));
        when(jdbc.queryForList(contains("FROM ipd_capability_pack_item"), eq("tenant-a"), eq(1L))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ipd_action_skill_map"), eq("tenant-a"), eq("C02"))).thenReturn(List.of());
        assertThatThrownBy(() -> catalog.packs("tenant-a")).isInstanceOf(IllegalStateException.class).hasMessageContaining("未绑定执行技能");
    }
    @Test void otherTenantKeepsBuiltinAndMissingTenantIsRejected() {
        when(jdbc.queryForList(contains("FROM ipd_capability_pack WHERE"), eq("tenant-b"))).thenReturn(List.of());
        assertThat(catalog.pack("tenant-b", "market-research", "v1").orElseThrow().actionCodes()).containsExactly("C01", "C02");
        assertThatThrownBy(() -> catalog.packs(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
