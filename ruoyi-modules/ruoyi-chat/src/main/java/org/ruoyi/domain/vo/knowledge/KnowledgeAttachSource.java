package org.ruoyi.domain.vo.knowledge;

import lombok.Data;

/**
 * 向量检索用的附件出处。只带文件名和创建人。
 * <p>
 * {@code knowledge_attach.create_by} 是字符串。导入片段的创建人可以是
 * {@code wb-kb-20260806} 这种非数字。这里按字符串读，避免父类 {@code Long createBy}
 * 把整段检索打成失败。数字创建人仍以原文字返回，例如 {@code 900103}。
 */
@Data
public class KnowledgeAttachSource {

    /** 附件名称，作为命中出处。 */
    private String name;

    /** 创建人原文，不转成数字。 */
    private String createBy;
}
