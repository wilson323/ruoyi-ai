package org.ruoyi.ipd.agent.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 目录：经 AgentScope 原生 ClasspathSkillRepository 读取真实 classpath 资源，
 * sha256 与清单一致；清单被篡改/未登记/资源缺失时不可用且拒绝加载正文。
 */
@Tag("dev")
class ProjectAgentSkillCatalogTest {

    private static final String SKILL = "competitor-analysis-ipd";

    @Test
    @DisplayName("正文可从 classpath 加载，原始字节 sha256 与清单锁定值一致，正文覆盖 C02 四维度")
    void skillLoadsFromClasspathWithMatchingSha() throws Exception {
        CapabilityManifest manifest = AgentTestFixtures.manifest();
        ProjectAgentSkillCatalog catalog = AgentTestFixtures.skillCatalog(manifest);
        String locked = manifest.skill(SKILL).orElseThrow().sha256();

        byte[] raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ipd-skills/" + SKILL + "/SKILL.md")) {
            assertThat(in).as("SKILL.md 在 classpath 中").isNotNull();
            raw = in.readAllBytes();
        }
        assertThat(ProjectAgentSkillCatalog.sha256Hex(raw)).isEqualTo(locked);

        ProjectAgentSkillCatalog.LoadedSkill loaded = catalog.load(SKILL).orElseThrow();
        assertThat(loaded.sha256()).isEqualTo(locked);
        assertThat(loaded.version()).isEqualTo(frontmatterVersion(raw));
        assertThat(loaded.content()).contains("功能").contains("价格").contains("渠道").contains("技术路线")
            .contains("禁止编造");
        assertThat(catalog.status(SKILL).available()).isTrue();
        // 清单 skills[] 可含多技能；至少含 C02，且名单内全部应 available（sha256 与 classpath 一致）
        assertThat(catalog.statuses()).extracting(ProjectAgentSkillCatalog.SkillStatus::name).contains(SKILL);
        assertThat(catalog.statuses()).allSatisfy(s -> assertThat(s.available()).as(s.name()).isTrue());
    }

    @Test
    @DisplayName("43项技能清单版本与真实frontmatter、原始SHA及加载版本逐项一致")
    void allManifestSkillsMatchShippedVersionsAndBytes() throws Exception {
        CapabilityManifest manifest = AgentTestFixtures.manifest();
        ProjectAgentSkillCatalog catalog = AgentTestFixtures.skillCatalog(manifest);
        // 2026-10-03：monthly-receipt-tracking-ipd（LC01）/ six-month-receipt-settlement-ipd（LC03）
        // 随回款台账 / 奖金池退役下线，清单由 45 → 43。
        assertThat(manifest.skills()).hasSize(43);
        for (CapabilityManifest.SkillEntry skill : manifest.skills()) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("ipd-skills/" + skill.name() + "/SKILL.md")) {
                assertThat(in).as(skill.name()).isNotNull();
                byte[] raw = in.readAllBytes();
                assertThat(skill.version()).as(skill.name()).isEqualTo(frontmatterVersion(raw));
                assertThat(skill.sha256()).as(skill.name()).isEqualTo(ProjectAgentSkillCatalog.sha256Hex(raw));
                assertThat(catalog.load(skill.name()).orElseThrow().version()).isEqualTo(skill.version());
            }
        }
    }

    private static String frontmatterVersion(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).startsWith("---\n");
        int end = text.indexOf("\n---", 4);
        assertThat(end).as("frontmatter closing delimiter").isGreaterThan(4);
        var version = Pattern.compile("(?m)^version:\\s*\"?([^\"\\r\\n]+)\"?\\s*$")
            .matcher(text.substring(4, end));
        assertThat(version.find()).as("frontmatter version").isTrue();
        return version.group(1).trim();
    }

    @Test
    @DisplayName("清单 sha256 被篡改：不可用、reason 指明完整性失败、load 返回空")
    void tamperedManifestShaRejectsSkill() {
        CapabilityManifest tampered = new CapabilityManifest(1,
            List.of(new CapabilityManifest.SkillEntry(SKILL, "1.0.0", "0".repeat(64))), List.of(), List.of());
        ProjectAgentSkillCatalog catalog = new ProjectAgentSkillCatalog("ipd-skills", tampered);

        assertThat(catalog.status(SKILL).available()).isFalse();
        assertThat(catalog.status(SKILL).reason()).contains("完整性校验失败");
        assertThat(catalog.load(SKILL)).isEmpty();
    }

    @Test
    @DisplayName("未登记 / 资源缺失 / 仓库根不存在：均不可用")
    void unknownOrMissingSkillIsUnavailable() {
        CapabilityManifest manifest = new CapabilityManifest(1,
            List.of(new CapabilityManifest.SkillEntry("not-shipped", "1.0.0", "0".repeat(64))), List.of(), List.of());
        assertThat(new ProjectAgentSkillCatalog("ipd-skills", manifest).status("not-shipped").reason())
            .isEqualTo("Skill 资源缺失");
        assertThat(new ProjectAgentSkillCatalog("ipd-skills", manifest).status("unlisted").reason())
            .isEqualTo("Skill 未在内置清单登记");
        assertThat(new ProjectAgentSkillCatalog("no-such-root", AgentTestFixtures.manifest()).status(SKILL).available())
            .isFalse();
    }

    @Test
    @DisplayName("清单：market-research@v1 精确映射 C01/C02 与两项真实技能，工具均已登记")
    void manifestDeclaresC01AndC02Pack() {
        CapabilityManifest manifest = AgentTestFixtures.manifest();
        CapabilityManifest.PackEntry pack = manifest.pack("market-research", "v1").orElseThrow();
        assertThat(pack.actionCodes()).containsExactly("C01", "C02");
        assertThat(pack.skills()).containsExactly("market-opportunity-research-ipd", "competitor-analysis-ipd");
        assertThat(pack.stages()).containsExactly("CONCEPT");
        assertThat(pack.skills()).allSatisfy(s -> assertThat(manifest.skill(s)).isPresent());
        assertThat(pack.tools()).allSatisfy(t -> assertThat(manifest.tool(t)).isPresent());
        assertThat(manifest.pack("market-research", "v2")).isEmpty();
    }
}
