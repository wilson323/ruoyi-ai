package org.ruoyi.ipd.dto;

/** 提交后返回一次性上传凭据；查询响应永不再次返回此凭据。 */
public record GuestDemandSubmittedView(String code, String status, String uploadToken) {
    public GuestDemandSubmittedView(String code, String status) { this(code, status, null); }
}
