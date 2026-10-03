package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P1-3.3 SOP 出入参 DTO 契约。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li>{@link SopTemplateSaveReq} 是白名单 record——只有 title/content 两个分量，
 *       客户端夹带 id/version/status/actionCode 既不能被忽略报错，也不能被绑定进对象
 *       （否则可绕过 service 层的「仅 DRAFT 可编辑 / 仅超管」判定直接改状态）。</li>
 *   <li>{@link SopTemplateListItem} 的 JSON 字段名是前端 {@code IpdSopTemplateItem} 的契约，
 *       改名即前端静默拿不到值（不报错，显示空白）。</li>
 * </ul>
 */
@Tag("dev")
class SopTemplateDtoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========== SopTemplateSaveReq：白名单入参 ==========

    @Test
    @DisplayName("record 分量恰为 title/content（新增分量即打开越权写入口）")
    void saveReq_hasExactlyTwoComponents() {
        RecordComponent[] components = SopTemplateSaveReq.class.getRecordComponents();

        assertThat(components).extracting(RecordComponent::getName)
            .containsExactly("title", "content");
    }

    @Test
    @DisplayName("夹带 id/version/status/actionCode 的 JSON 不抛异常，且这些字段无法落到对象上")
    void saveReq_unknownPropertiesAreIgnoredNotBound() throws Exception {
        String payload = "{\"title\":\"标题\",\"content\":\"正文\","
            + "\"id\":999,\"version\":42,\"status\":\"PUBLISHED\",\"actionCode\":\"V10\"}";

        assertThatCode(() -> JSON.readValue(payload, SopTemplateSaveReq.class))
            .as("@JsonIgnoreProperties(ignoreUnknown=true) 被摘掉时本断言转红")
            .doesNotThrowAnyException();

        SopTemplateSaveReq req = JSON.readValue(payload, SopTemplateSaveReq.class);
        assertThat(req.title()).isEqualTo("标题");
        assertThat(req.content()).isEqualTo("正文");
        // 越权字段无处可去——record 只有两个分量，不存在 status/id 的存取器
        assertThat(SopTemplateSaveReq.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .doesNotContain("id", "version", "status", "actionCode");
    }

    @Test
    @DisplayName("空 JSON 反序列化得双 null（由 service 层判 400，DTO 不预判）")
    void saveReq_emptyJsonYieldsNulls() throws Exception {
        SopTemplateSaveReq req = JSON.readValue("{}", SopTemplateSaveReq.class);

        assertThat(req.title()).isNull();
        assertThat(req.content()).isNull();
    }

    @Test
    @DisplayName("record 值语义：同值相等同哈希，异值不等")
    void saveReq_recordEquality() {
        SopTemplateSaveReq a = new SopTemplateSaveReq("t", "c");
        SopTemplateSaveReq b = new SopTemplateSaveReq("t", "c");
        SopTemplateSaveReq c = new SopTemplateSaveReq("t", "其他");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    // ========== SopTemplateListItem：前端契约 ==========

    @Test
    @DisplayName("字段往返：六个字段原样读出")
    void listItem_roundTripsAllFields() {
        SopTemplateListItem item = new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L);

        assertThat(item.id()).isEqualTo(1L);
        assertThat(item.actionCode()).isEqualTo("C01");
        assertThat(item.title()).isEqualTo("标题");
        assertThat(item.version()).isEqualTo(2L);
        assertThat(item.status()).isEqualTo("PUBLISHED");
        assertThat(item.contentLen()).isEqualTo(30L);
    }

    @Test
    @DisplayName("contentLen 可空（计算列缺失时不得抛异常）")
    void listItem_nullContentLenTolerated() {
        SopTemplateListItem item = new SopTemplateListItem(1L, "C01", "标题", 1L, "DRAFT", null);

        assertThat(item.contentLen()).isNull();
        assertThat(item.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("序列化字段名与前端 IpdSopTemplateItem 契约逐字一致（改名即前端静默空白）")
    void listItem_serializesToContractFieldNames() {
        JsonNode node = JSON.valueToTree(
            new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L));

        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrder(
            "id", "actionCode", "title", "version", "status", "contentLen");
    }

    @Test
    @DisplayName("列表序列化为 JSON 数组且元素形状稳定")
    void listItem_serializesAsArray() {
        JsonNode node = JSON.valueToTree(List.of(
            new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L)));

        assertThat(node.isArray()).isTrue();
        assertThat(node).hasSize(1);
        assertThat(node.get(0).get("actionCode").asText()).isEqualTo("C01");
        assertThat(node.get(0).get("contentLen").asLong()).isEqualTo(30L);
    }

    @Test
    @DisplayName("record 值语义：同值相等同哈希，version 不同则不等")
    void listItem_recordEquality() {
        SopTemplateListItem a = new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L);
        SopTemplateListItem b = new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L);
        SopTemplateListItem c = new SopTemplateListItem(1L, "C01", "标题", 3L, "PUBLISHED", 30L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("record 分量恰为六个（新增分量须同步前端契约）")
    void listItem_hasExactlySixComponents() {
        assertThat(SopTemplateListItem.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("id", "actionCode", "title", "version", "status", "contentLen");
    }
}
