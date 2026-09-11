package org.ruoyi.ipd.dto;

/**
 * P1-3.3 SOP 版本列表轻量视图（对齐前端 IpdSopTemplateItem；
 * 不含 mediumtext 正文，仅 contentLen 字符数）。
 */
public record SopTemplateListItem(Long id, String actionCode, String title,
                                  Long version, String status, Long contentLen) {
}
