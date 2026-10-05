package org.ruoyi.ipd.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 项目智能体产物版本行（ipd_agent_artifact_version）。
 *
 * <p>反馈 {@code ARTIFACT_VERSION} 的 targetId 为本行雪花 id；事件 payload.artifactId
 * 为逻辑产物 ID。status 仅 DRAFT|APPLIED；apply 成功后回写 documentId。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ipd_agent_artifact_version", autoResultMap = true)
public class IpdAgentArtifactVersion extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 草稿：可 apply、可反馈。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已应用到项目文档。 */
    public static final String STATUS_APPLIED = "APPLIED";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private Long runId;
    /** 逻辑产物 ID（字符串，与 ARTIFACT 事件 payload.artifactId 一致）。 */
    private String artifactId;
    private Integer versionNo;
    private String title;
    private String content;
    private String contentSha256;
    /** DRAFT / APPLIED。 */
    private String status;
    /** apply 成功后的 ai_documents.id。 */
    private Long documentId;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
