package org.ruoyi.ipd.hr;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HR 平台业务数据 API 客户端（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>对接：
 * <ul>
 *   <li>{@code zkteco.ehr.getUserInfo}（HR §3.2.1）</li>
 *   <li>{@code zkteco.ehr.getOrganizationInfo}（HR §3.2.2）</li>
 * </ul>
 *
 * <p>本期不接：{@code zkteco.ehr.getJobTitleInfo}（岗位）、BANKLIST、COSTCENTER（用户 2026-09-21 排除）。
 *
 * <p>token 失效自动续签：HR code=1006/1007（token 过期/不匹配）时强制 refresh + 重试 1 次；
 * 签名错（1002）/ 时间戳过期（1003）/ HTTP 5xx **不重试**——避免脏数据入 persons。
 *
 * <p>下游联动：fetch 出的 PersonRow / OrgRow 由 {@code HrSyncService.markResignedByHr}
 * 接入既有"HR 事件联动适配器"（不复刻业务规则）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrApiClient {

    /** HR 文档为大写驼峰（PERNR/BASIC_INFO/ORGEH），反序列化为 camelCase POJO。 */
    /** HR POJO 字段已加 @JsonProperty 显式标注 HR 文档字段名；策略默认即可。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HrSyncProperties props;
    private final HrSignatureUtil signatureUtil;
    private final HrTokenClient tokenClient;

    public List<HrResponse.PersonRow> fetchUserInfo(HrResponse.SyncBody body) {
        JsonNode root = callWithRetry("zkteco.ehr.getUserInfo", body);
        return parseBodyArray(root.path("data").path("BODY"), HrResponse.PersonRow.class);
    }

    public List<HrResponse.OrgRow> fetchOrganizationInfo(HrResponse.SyncBody body) {
        JsonNode root = callWithRetry("zkteco.ehr.getOrganizationInfo", body);
        return parseBodyArray(root.path("data").path("BODY"), HrResponse.OrgRow.class);
    }

    private JsonNode callWithRetry(String method, HrResponse.SyncBody body) {
        JsonNode first = send(method, body, tokenClient.getToken());
        int code = first.path("code").asInt(-1);
        if (code == 1006 || code == 1007) {
            log.warn("ipd_hr_token_invalid method={} code={} retrying", method, code);
            tokenClient.refresh();
            return send(method, body, tokenClient.getToken());
        }
        return first;
    }

    private JsonNode send(String method, HrResponse.SyncBody body, String token) {
        long ts = System.currentTimeMillis();
        Map<String, Object> dataWrapper = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("DATA", body);
        dataWrapper.put("data", data);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", nvl(props.getAppId()));
        params.put("format", "JSON");
        params.put("method", method);
        params.put("timestamp", ts);
        params.put("signType", "md5");
        params.put("token", nvl(token));
        params.put("data", data);
        params.put("sign", signatureUtil.sign(params, nvl(props.getSecretKey())));

        String json = JsonUtil.toJsonString(params);
        return tokenClient.postJson("/ehr/getUserInfo".equals(method) ? "/ehr/getUserInfo"
                : "/ehr/getOrganizationInfo", json);
    }

    private <T> List<T> parseBodyArray(JsonNode bodyArray, Class<T> klass) {
        List<T> result = new ArrayList<>();
        if (bodyArray == null || bodyArray.isMissingNode() || bodyArray.isNull()) return result;
        if (!bodyArray.isArray()) {
            throw new HrApiException(HrApiException.PERMANENT, "-1",
                "HR 响应 BODY 非数组", bodyArray.toString());
        }
        for (JsonNode node : bodyArray) {
            try {
                result.add(MAPPER.treeToValue(node, klass));
            } catch (Exception e) {
                log.warn("ipd_hr_row_parse_skip err={} node={}",
                    e.getMessage(),
                    node.toString().substring(0, Math.min(200, node.toString().length())));
            }
        }
        return result;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
