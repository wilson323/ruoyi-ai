package org.ruoyi.ipd.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EHR 请求契约测试（2026-10-07，修复「Hutool 序列化丢大写键名」回归）。
 *
 * <p>口径来源（三方对齐）：
 * <ol>
 *   <li>官方 demo：demo-v2 的 DataUtil（HEAD / INPUT_TYP / BEGDA / ENDDA 大写下划线键）；</li>
 *   <li>ehr-probe.mjs（2026-09-29 真连实测走到网关业务层，其 JSON.stringify 输出为同序大写键）；</li>
 *   <li>本实现 JsonUtil（Jackson，@JsonProperty 生效）。</li>
 * </ol>
 *
 * <p>本测试把 probe 口径的 data 段字符串钉死——若再出现序列化器/注解漂移，立即变红。
 * 背景：修复前序列化走 Hutool（不识别 Jackson 注解），实际输出
 * {@code {"head":...,"body":[{"inputTyp":"NEW",...}]}}，与网关契约不符且静默无告警。
 */
@Tag("dev")
@DisplayName("EHR 请求契约：data 段序列化与 probe/官方 demo 逐字节一致")
class HrRequestContractTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** probe 结构：{@code { DATA: { HEAD: {...}, BODY: [{INPUT_TYP,BEGDA,ENDDA}] } }}。 */
    private static String dataWrapperJson(HrResponse.SyncBody body) {
        Map<String, Object> data = new HashMap<>();
        data.put("DATA", body);
        return JsonUtil.toJsonString(data);
    }

    @Test
    @DisplayName("incremental(NEW)：全串与 probe 口径逐字节一致（大写 HEAD/BODY/INPUT_TYP）")
    void incrementalMatchesProbe() {
        String json = dataWrapperJson(HrResponse.SyncBody.incremental("20261007"));
        assertThat(json).isEqualTo(
            "{\"DATA\":{\"HEAD\":{\"INTF_ID\":\"\",\"SRC_SYSTEM\":\"\",\"DEST_SYSTEM\":\"\","
            + "\"SRC_MSGID\":\"\",\"BACKUP1\":\"\",\"BACKUP2\":\"\"},"
            + "\"BODY\":[{\"INPUT_TYP\":\"NEW\",\"BEGDA\":\"20261007\",\"ENDDA\":\"20261007\"}]}}");
    }

    @Test
    @DisplayName("single：BACKUP1 带工号，其余键与 probe 同构；禁止再出现小驼峰键")
    void singleCarriesPernrInBackup1() {
        String json = dataWrapperJson(HrResponse.SyncBody.single("00000017", "20261007"));
        assertThat(json).contains("\"BACKUP1\":\"00000017\"");
        assertThat(json).contains("\"INPUT_TYP\":\"ALL\"");
        assertThat(json).doesNotContain("backup1");
        assertThat(json).doesNotContain("inputTyp");
    }

    @Test
    @DisplayName("full：BEGDA/ENDDA 使用注入日期（非 demo 样例值 20230612）")
    void fullUsesInjectedDate() {
        String json = dataWrapperJson(HrResponse.SyncBody.full("20261007"));
        assertThat(json).contains("\"INPUT_TYP\":\"ALL\",\"BEGDA\":\"20261007\",\"ENDDA\":\"20261007\"");
        assertThat(json).doesNotContain("20230612");
        assertThat(json).doesNotContain("\"head\"");
    }

    @Test
    @DisplayName("签名与请求体共用 JSON：sign() 的待签串含与请求体同源的 data JSON")
    void signatureDataMatchesRequestBody() {
        HrResponse.SyncBody body = HrResponse.SyncBody.incremental("20261007");
        Map<String, Object> data = new HashMap<>();
        data.put("DATA", body);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", "id");
        params.put("format", "JSON");
        params.put("method", "zkteco.ehr.getUserInfo");
        params.put("signType", "md5");
        params.put("timestamp", 123L);
        params.put("data", data);

        String signed = new HrSignatureUtil().sign(params, "k");

        String expectedData = "{\"DATA\":{\"HEAD\":{\"INTF_ID\":\"\",\"SRC_SYSTEM\":\"\",\"DEST_SYSTEM\":\"\","
            + "\"SRC_MSGID\":\"\",\"BACKUP1\":\"\",\"BACKUP2\":\"\"},"
            + "\"BODY\":[{\"INPUT_TYP\":\"NEW\",\"BEGDA\":\"20261007\",\"ENDDA\":\"20261007\"}]}}";
        String expectedSrc = "appId=id&data=" + expectedData
            + "&format=JSON&method=zkteco.ehr.getUserInfo&signType=md5&timestamp=123&secretKey=k";
        assertThat(signed).isEqualTo(HrSignatureUtil.md5Hex(expectedSrc));
    }

    @Test
    @DisplayName("requireOk：code=0 放行；1005/9999 PERMANENT 上抛；1006/1007 重试后 TRANSIENT")
    void requireOkSemantics() throws Exception {
        JsonNode ok = OM.readTree("{\"code\":0,\"data\":{}}");
        assertThat(HrApiClient.requireOk(ok, "m")).isSameAs(ok);

        JsonNode ipBlocked = OM.readTree("{\"code\":1005,\"msg\":\"当前IP禁止调用\"}");
        assertThatThrownBy(() -> HrApiClient.requireOk(ipBlocked, "m"))
            .isInstanceOf(HrApiException.class)
            .satisfies(e -> {
                HrApiException h = (HrApiException) e;
                assertThat(h.isPermanent()).isTrue();
                assertThat(h.getHrCode()).isEqualTo("1005");
                assertThat(h.getMessage()).contains("当前IP禁止调用");
            });

        JsonNode signError = OM.readTree("{\"code\":9999,\"msg\":\"sign error\"}");
        assertThatThrownBy(() -> HrApiClient.requireOk(signError, "m"))
            .isInstanceOf(HrApiException.class)
            .satisfies(e -> assertThat(((HrApiException) e).isPermanent()).isTrue());

        JsonNode tokenInvalid = OM.readTree("{\"code\":1006,\"msg\":\"token invalid\"}");
        assertThatThrownBy(() -> HrApiClient.requireOk(tokenInvalid, "m"))
            .isInstanceOf(HrApiException.class)
            .satisfies(e -> assertThat(((HrApiException) e).isTransient()).isTrue());
    }
}
