package org.ruoyi.ipd.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HrApiClient 单测（FA-HR-Sync·2026-09-21 R149，{@code @Tag("dev")}）。
 *
 * <p>本轮目标：仅验证 DTO 解析路径稳定（HR §3.2.1.8 / §3.2.2.8 出参示例）。
 * HTTP 调用 + token 续签重试留 FA-HR-Sync-v1 集成阶段（在 WireMock Mock HR 服务里跑）。
 */
@Tag("dev")
@DisplayName("FA-HR-Sync-A HrApiClient: DTO 解析与示例响应回放")
class HrApiClientTest {

    private HrApiClient apiClient;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        HrSyncProperties props = new HrSyncProperties();
        props.setAppId("test");
        props.setSecretKey("test-secret");
        props.setBaseUrl("http://127.0.0.1:0");
        HrSignatureUtil sig = new HrSignatureUtil();
        HrTokenClient token = new HrTokenClient(props, sig);
        apiClient = new HrApiClient(props, sig, token);
    }

    @Test
    @DisplayName("PersonRow 解析：HR §3.2.1.8 出参示例首条")
    void parseUserInfo_docExample() throws Exception {
        String json = "{ \"code\": 0, \"data\": { \"HEAD\": {}, \"RETURN\": {\"STATUS\":\"S\"},"
            + " \"BODY\": ["
            + " { \"PERNR\":\"00000017\","
            + "   \"BASIC_INFO\": { \"NACHN\":\"陈守一\", \"RUFNM\":\"\", \"GESCH\":\"1\","
            + "     \"EMAIL_COM\":\"test@zkteco.com\", \"PHONE\":\"13900000001\","
            + "     \"ORGEH\":\"10000342\", \"DEPTNAME\":\"董总办(大连)\", \"PLANS\":\"31000001\","
            + "     \"POSITIONNAME\":\"M5总经理\", \"BUKRS\":\"1000\", \"CONTRACTSUBJECT\":\"熵基科技大连\","
            + "     \"STAT2\":\"3\", \"LEAVE_DATE\":\"\", \"DEL_FLAG\":\"\","
            + "     \"HIRE_DATE\":\"2005-02-24\", \"REGULARDATE\":\"\" },"
            + "   \"BANKLIST\":[{\"CARDFLAG\":\"HC02\"}],"
            + "   \"ID_INFO\":{} }"
            + " ] } }";
        JsonNode root = om.readTree(json);
        JsonNode body = root.path("data").path("BODY");
        List<HrResponse.PersonRow> rows = parse(body, HrResponse.PersonRow.class);
        assertThat(rows).hasSize(1);
        HrResponse.PersonRow row = rows.get(0);
        assertThat(row.pernr).isEqualTo("00000017");
        assertThat(row.basicInfo.orgEh).isEqualTo("10000342"); // snake→camel 转换后字段名
        assertThat(row.basicInfo.stat2).isEqualTo("3");
        assertThat(row.basicInfo.positionname).isEqualTo("M5总经理");
    }

    @Test
    @DisplayName("OrgRow 解析：HR §3.2.2.8 出参示例首条")
    void parseOrgInfo_docExample() throws Exception {
        String json = "{ \"code\": 0, \"data\": { \"HEAD\":{}, \"RETURN\":{\"STATUS\":\"S\"},"
            + " \"BODY\": ["
            + " { \"ORGEH\":\"14999999\", \"STEXT\":\"熵基科技股份有限公司\","
            + "   \"SHORT\":\"熵基科技股份有限公司\", \"BEGDA\":\"1900-01-01\","
            + "   \"ENDDA\":\"2099-12-31\", \"ORGEH_PUP\":\"\", \"BMFZR\":\"01200723\","
            + "   \"ZBMCJ\":\"1\", \"DELFLAG\":\"\", \"EXPIRATIONFLAG\":\"\" }"
            + " ] } }";
        JsonNode root = om.readTree(json);
        JsonNode body = root.path("data").path("BODY");
        List<HrResponse.OrgRow> rows = parse(body, HrResponse.OrgRow.class);
        assertThat(rows).hasSize(1);
        HrResponse.OrgRow row = rows.get(0);
        assertThat(row.orgeh).isEqualTo("14999999");
        assertThat(row.stext).isEqualTo("熵基科技股份有限公司");
        assertThat(row.bmfzr).isEqualTo("01200723");
    }

    @Test
    @DisplayName("SyncBody.full() 与 incremental() 构造正确")
    void syncBodyBuilds() {
        HrResponse.SyncBody full = HrResponse.SyncBody.full();
        assertThat(full.body).hasSize(1);
        assertThat(full.body.get(0).inputTyp).isEqualTo("ALL");

        HrResponse.SyncBody inc = HrResponse.SyncBody.incremental("20260921");
        assertThat(inc.body).hasSize(1);
        assertThat(inc.body.get(0).inputTyp).isEqualTo("NEW");
        assertThat(inc.body.get(0).begda).isEqualTo("20260921");
    }

    private <T> List<T> parse(JsonNode array, Class<T> klass) {
        java.util.List<T> out = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            out.add(cn.hutool.json.JSONUtil.toBean(node.toString(), klass));
        }
        return out;
    }
}
