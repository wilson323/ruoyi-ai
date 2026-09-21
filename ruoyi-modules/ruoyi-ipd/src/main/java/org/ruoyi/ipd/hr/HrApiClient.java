package org.ruoyi.ipd.hr;

import com.fasterxml.jackson.databind.JsonNode;
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
 *   <li>{@code zkteco.ehr.getUserInfo} — 人员信息（FA-HR-Sync-5 实现）</li>
 *   <li>{@code zkteco.ehr.getOrganizationInfo} — 组织信息（FA-HR-Sync-6 实现）</li>
 * </ul>
 *
 * <p>本期不接：{@code zkteco.ehr.getJobTitleInfo}（岗位）、HR 推过来的 BANKLIST 多银行明细、
 * COSTCENTER 多成本中心展开（用户 2026-09-21 明确排除）。
 *
 * <p>自动 token 失效重试：HR 返回 code=1007（token 不匹配）时，强制刷新一次 token 后重发。
 * HTTP 5xx / 1002（签名失败）/1003（时间戳过期）等错误**不重试**——重试会把脏数据写库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrApiClient {

    private final HrSyncProperties props;
    private final HrSignatureUtil signatureUtil;
    private final HrTokenClient tokenClient;

    /**
     * 拉取人员信息（全量或增量由 {@code body.inputTyp} 决定）。
     *
     * @param body HR 接口要求的 DATA.HEAD + DATA.BODY 结构
     * @return 解析后的 PersonRow 列表
     */
    public List<HrResponse.PersonRow> fetchUserInfo(HrResponse.SyncBody body) {
        JsonNode root = callWithRetry("zkteco.ehr.getUserInfo", body);
        return parseBodyArray(root.path("data").path("BODY"), HrResponse.PersonRow.class);
    }

    /**
     * 拉取组织信息。
     */
    public List<HrResponse.OrgRow> fetchOrganizationInfo(HrResponse.SyncBody body) {
        JsonNode root = callWithRetry("zkteco.ehr.getOrganizationInfo", body);
        return parseBodyArray(root.path("data").path("BODY"), HrResponse.OrgRow.class);
    }

    // ───── 内部：拼装公共参 + sign + POST + 自动重试 ─────

    private JsonNode callWithRetry(String method, HrResponse.SyncBody body) {
        JsonNode first = send(method, body, tokenClient.getToken());
        // HR code=1006/1007（token 已过期/不匹配） → 强制刷新 + 重试 1 次
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
        params.put("data", data); // 注意：直接传 inner data，签名时序列化
        params.put("sign", signatureUtil.sign(params, nvl(props.getSecretKey())));

        String json = JsonUtil.toJsonString(params);
        return tokenClient.postJson("/ehr/getUserInfo".equals(method) ? "/ehr/getUserInfo"
                : "/ehr/getOrganizationInfo", json);
    }

    private <T> List<T> parseBodyArray(JsonNode bodyArray, Class<T> klass) {
        List<T> result = new ArrayList<>();
        if (bodyArray == null || bodyArray.isMissingNode() || bodyArray.isNull()) return result;
        if (!bodyArray.isArray()) {
            throw new HrApiException(-1, "HR 响应 BODY 非数组", bodyArray.toString());
        }
        for (JsonNode node : bodyArray) {
            try {
                result.add(cn.hutool.json.JSONUtil.toBean(node.toString(), klass));
            } catch (Exception e) {
                log.warn("ipd_hr_row_parse_skip error={} node={}", e.getMessage(), node.toString().substring(0, Math.min(200, node.toString().length())));
            }
        }
        return result;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
