package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.seed.ActionCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class ProjectAgentBusinessDeliveryCatalogTest {
    final CapabilityManifest manifest = CapabilityManifest.load(CapabilityManifest.DEFAULT_RESOURCE, new ObjectMapper());

    @Test void everyOfferedActionHasItsOwnRealLockedSkillAndCorrectStage() throws Exception {
        for (var pack : manifest.packs()) {
            for (var action : pack.actionCodes()) {
                var def = ActionCatalog.byCode(action);
                assertThat(pack.stages()).as(action).contains(def.stage());
                boolean matching=false;
                for (String name : pack.skills()) {
                    var skill=manifest.skill(name).orElseThrow();
                    try (var in=getClass().getClassLoader().getResourceAsStream("ipd-skills/"+name+"/SKILL.md")) {
                        assertThat(in).as(name).isNotNull();
                        byte[] bytes=in.readAllBytes();
                        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(skill.sha256());
                        String body=new String(bytes, StandardCharsets.UTF_8);
                        if(body.contains("ipd-action: \""+action+"\"")) {
                            matching=true;
                            assertThat(body).contains("## 输入", "## 步骤", "## 禁止事项");
                        }
                    }
                }
                assertThat(matching).as("动作 %s 必须有自己的技能与输入/步骤/边界，不能只加动作码", action).isTrue();
            }
        }
    }
    @Test void externalPhysicalWorkIsNotAdvertisedAsAutomatedDelivery() {
        var offered=manifest.packs().stream().flatMap(p -> p.actionCodes().stream()).toList();
        assertThat(offered).doesNotContain("D02", "D03", "D07", "L05", "LC06", "LC01", "LC03");
        assertThat(offered).contains("C08", "P02", "P13", "D05", "V07", "L03", "LC09", "K01");
    }
    @Test void deepActionArtifactsHaveSpecificContractDocumentTypes() {
        ActionCatalog.ALL.stream().filter(a -> "DEEP".equals(a.depth())).forEach(a ->
            assertThat(ActionCatalog.docTypeOf(a.code())).as(a.code()).isNotBlank().isNotEqualTo("PROJECT_AGENT_ARTIFACT"));
        assertThat(ActionCatalog.docTypeOf("C08")).isEqualTo("CHARTER");
        assertThat(ActionCatalog.docTypeOf("P02")).isEqualTo("VERSION_ROADMAP");
        assertThat(ActionCatalog.docTypeOf("V07")).isEqualTo("PACKAGING_USER_GUIDE");
        assertThat(ActionCatalog.docTypeOf("L03")).isEqualTo("SALES_TOOLKIT");
        assertThat(ActionCatalog.docTypeOf("LC09")).isEqualTo("PROJECT_ARCHIVE_PACKAGE");
    }
    @Test void newSkillDraftsDoNotBecomeRuntimeCapabilitiesBeforeOwnerApproval() {
        assertThat(manifest.skill("technical-feasibility-ipd")).isEmpty();
        assertThat(manifest.skill("system-architecture-ipd")).isEmpty();
        assertThat(manifest.packs().stream().flatMap(p -> p.actionCodes().stream()).toList())
            .doesNotContain("C05", "P03", "P04", "P05", "P06", "P07", "P08", "P09", "P11", "D01", "D04", "V08");
    }
    @Test void retainedSalesKpiDoesNotResurrectReceiptLedger() throws Exception {
        try(var in=getClass().getClassLoader().getResourceAsStream("ipd-skills/kpi-receipt-achievement-ipd/SKILL.md")) {
            String body=new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("实际数量 / 目标数量", "不读取或恢复已退役回款台账");
            assertThat(body).doesNotContain("默认分子为实际回款", "达成率 = 回款");
        }
    }
}
