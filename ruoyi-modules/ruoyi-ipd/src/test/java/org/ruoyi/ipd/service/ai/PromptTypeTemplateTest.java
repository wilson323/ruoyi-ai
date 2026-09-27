package org.ruoyi.ipd.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.PromptType;
import org.ruoyi.ipd.dto.AiGenerateReq;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-P1-1 后端切片：promptType 枚举 + system prompt 模板拼接 + 向后兼容。
 * 覆盖卡片三个验收点：①promptType 为空→裸 prompt 直传不变；②每个枚举值→模板正确
 * 拼接（含 sourceText）；③未知/非法枚举值→拒绝（PARAM_INVALID，选定策略：拒绝而非
 * 降级——静默降级会造成"以为套了模板"的假成功，与 service 层既有拒错风格一致）。
 */
@Tag("dev")
class PromptTypeTemplateTest {

    private static final String SOURCE = "原始资料：用户反馈整理与竞品速览";

    // ---------- 验收点①：空 → 裸 prompt 直传（向后兼容） ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankPromptTypePassesRawPromptUnchanged(String promptType) {
        assertThat(PromptTemplates.render(promptType, SOURCE)).isEqualTo(SOURCE);
    }

    @Test
    void nullPromptTypePassesRawPromptUnchanged() {
        assertThat(PromptTemplates.render(null, SOURCE)).isEqualTo(SOURCE);
    }

    @Test
    void legacyFourArgConstructorKeepsPromptTypeNull() {
        // 既有调用点（GenerateExecutor / P422 等 5 处）零改动编译 + promptType 恒 null
        AiGenerateReq legacy = new AiGenerateReq(77L, "PRD", "需求文档", SOURCE);
        assertThat(legacy.promptType()).isNull();
        assertThat(PromptTemplates.render(legacy.promptType(), legacy.prompt())).isEqualTo(SOURCE);
    }

    @Test
    void oldFrontendJsonWithoutPromptTypeDeserializesToNull() throws Exception {
        // 老前端 payload（无 promptType 字段）→ null → 裸 prompt 直传；
        // 同时守门：record 双构造器下 Jackson 必须选 canonical（5 参）而非 4 参兼容口。
        ObjectMapper mapper = new ObjectMapper();
        String legacyJson = "{\"projectId\":77,\"docType\":\"PRD\",\"title\":\"需求文档\",\"prompt\":\"" + SOURCE + "\"}";
        AiGenerateReq req = mapper.readValue(legacyJson, AiGenerateReq.class);
        assertThat(req.promptType()).isNull();
        assertThat(req.prompt()).isEqualTo(SOURCE);

        String newJson = legacyJson.substring(0, legacyJson.length() - 1) + ",\"promptType\":\"MRD\"}";
        assertThat(mapper.readValue(newJson, AiGenerateReq.class).promptType()).isEqualTo("MRD");
    }

    // ---------- 验收点②：每个枚举值 → 模板正确拼接（含 sourceText） ----------

    @ParameterizedTest
    @EnumSource(PromptType.class)
    void everyEnumTypeRendersTemplateWithSourceTextSlot(PromptType type) {
        String rendered = PromptTemplates.render(type.getCode(), SOURCE);

        // 槽位被完整替换：不残留占位符
        assertThat(rendered).doesNotContain(PromptTemplates.SLOT);
        // sourceText 完整落入槽位，且在模板尾部（本模板集统一约定资料后置）
        assertThat(rendered).endsWith(SOURCE);
        // 模板指令部分确实拼进去了（渲染结果严格长于裸资料）
        assertThat(rendered).isNotEqualTo(SOURCE);
        assertThat(rendered.length()).isGreaterThan(SOURCE.length());
        // 模板本身含槽位标记（防止静态块里漏拼 SLOT）
        assertThat(PromptTemplates.templateOf(type)).contains(PromptTemplates.SLOT);
    }

    @Test
    void sevenTemplatesAreDistinctAndCoverCardList() {
        // 卡面枚举清单：PRD/MRD/BRD/CHARTER/TEST_REPORT/RELEASE_NOTE/REVIEW
        assertThat(PromptType.values()).extracting(PromptType::getCode)
            .containsExactly("PRD", "MRD", "BRD", "CHARTER", "TEST_REPORT", "RELEASE_NOTE", "REVIEW");
        List<String> templates = new ArrayList<>();
        for (PromptType t : PromptType.values()) {
            templates.add(PromptTemplates.templateOf(t));
        }
        assertThat(templates).doesNotHaveDuplicates().allMatch(s -> !s.isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prd", "Prd", " test_report "})
    void promptTypeMatchingIsCaseInsensitiveAndTrimmed(String code) {
        PromptType expected = PromptType.fromCodeOrNull(code.trim().toUpperCase());
        assertThat(PromptTemplates.render(code, SOURCE)).startsWith(
            PromptTemplates.templateOf(expected).substring(0, 10));
    }

    // ---------- 验收点③：未知/非法值 → 拒绝（选定策略，非降级） ----------

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "SYSTEM_PROMPT_INJECTION", "PRD2", "中文类型"})
    void unknownPromptTypeIsRejectedNotSilentlyDowngraded(String code) {
        assertThatThrownBy(() -> PromptTemplates.render(code, SOURCE))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("promptType 非法")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    void fromCodeOrNullMapsUnknownAndBlankToNull() {
        assertThat(PromptType.fromCodeOrNull("FOO")).isNull();
        assertThat(PromptType.fromCodeOrNull(null)).isNull();
        assertThat(PromptType.fromCodeOrNull("  ")).isNull();
        assertThat(PromptType.fromCodeOrNull("release_note")).isEqualTo(PromptType.RELEASE_NOTE);
    }
}
