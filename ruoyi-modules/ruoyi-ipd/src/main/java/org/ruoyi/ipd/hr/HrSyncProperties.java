package org.ruoyi.ipd.hr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HR 同步配置（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>对接信息集成中心接口（zkteco.*）：appId / secretKey / baseUrl 必须外部注入，
 * 不在源码留任何字面量。定时策略完全交给 {@code ipd.hr.sync.cron}，
 * 关闭开关走 {@code ipd.hr.sync.enabled=false}（默认 true）。
 *
 * <p>真实值放置路径：{@code .codex/ipd-dev/config/application-ipd-local.yml}（gitignored），
 * dev profile 已在父 application.yml 给出占位 env 变量（IPD_HR_APP_ID / IPD_HR_SECRET_KEY / IPD_HR_BASE_URL）。
 * 任何 commit 阶段都不能硬编码 appId/secretKey/baseUrl 真实值。
 *
 * <p>对接范围：仅 zkteco.ehr.getUserInfo + zkteco.ehr.getOrganizationInfo；成本中心/银行/岗位接口本期不接。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ipd.hr")
public class HrSyncProperties {

    /** 总开关；false 时不调用任何 HR 接口（可用于灰度）。 */
    private boolean enabled = true;

    /** HR 平台分配的开发者 key（env: IPD_HR_APP_ID）。 */
    private String appId;

    /** HR 平台分配的开发者密钥（env: IPD_HR_SECRET_KEY）；用于签名拼装 secretKey=xxx。 */
    private String secretKey;

    /** HR 平台基础 URL，例如 https://ehr.zkteco.com/api（env: IPD_HR_BASE_URL）。 */
    private String baseUrl;

    /** 同步调度策略（默认每天凌晨 02:00 错峰 09:00/09:05 的兄弟调度）。 */
    private Sync sync = new Sync();

    /** HTTP 客户端参数。 */
    private Http http = new Http();

    @Data
    public static class Sync {
        /** cron 表达式；默认 0 0 2 * * ?（每天 02:00）。 */
        private String cron = "0 0 2 * * ?";

        /** 启动时是否立即全量一次（true=立即先全量第一次再走定时）。 */
        private boolean fullOnStartup = false;

        /** 单次同步的最大页/批（HR 接口未明确分页；保留扩展位）。 */
        private int pageSize = 500;
    }

    @Data
    public static class Http {
        /** 单次 HTTP 调用超时（毫秒）；HR 接口按官方建议默认 15000。 */
        private int connectTimeoutMs = 5000;
        /** 读取超时（毫秒）；HR 全量同步返回较大，默认 30000。 */
        private int readTimeoutMs = 30000;
        /** 失败重试次数（仅对非鉴权错误重试）。 */
        private int retryCount = 2;
    }
}
