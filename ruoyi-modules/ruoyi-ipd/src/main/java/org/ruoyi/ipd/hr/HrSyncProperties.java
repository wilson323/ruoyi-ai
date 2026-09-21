package org.ruoyi.ipd.hr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HR 真源 API 客户端配置（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>对接信息集成平台接口文档-EHR.docx（zkteco.ehr.getUserInfo + zkteco.ehr.getOrganizationInfo）。
 * <p>本类只配真源 HTTP 调用所需的连接参数；既有的"HR 事件联动"
 * （{@code service.HrSyncService#markResignedByHr / escalateStaleResignations}）已落地，本类不与之双轨。
 *
 * <p>appId / secretKey / baseUrl 一律外部注入（env: IPD_HR_APP_ID / IPD_HR_SECRET_KEY / IPD_HR_BASE_URL），
 * 真实值放 {@code .codex/ipd-dev/config/application-ipd-local.yml}（gitignored）。
 * 本仓不落任何字面量；sensitive-field-guard hook 也会阻断。
 *
 * <p>对接范围：仅人员 + 组织；不接 BANKLIST / COSTCENTER / getJobTitleInfo（用户 2026-09-21 明确排除）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ipd.hr")
public class HrSyncProperties {

    /** 总开关；prod 必须显式覆盖 true + 完成真实凭据注入后才允许 true。 */
    private boolean enabled = false;

    /** HR 平台开发者 key（env: IPD_HR_APP_ID）。 */
    private String appId;

    /** HR 平台密钥（env: IPD_HR_SECRET_KEY）。 */
    private String secretKey;

    /** HR 平台基础 URL（env: IPD_HR_BASE_URL）。 */
    private String baseUrl;

    /** HTTP 客户端参数。 */
    private Http http = new Http();

    @Data
    public static class Http {
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 30000;
        private int retryCount = 2;
    }
}
