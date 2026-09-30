package org.ruoyi.ipd.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * AI 点赞/点踩事实行（ipd_ai_feedback）。唯一键 (target_type, target_id, person_id)：
 * 同一人对同一目标只保留一条，再次提交为本人更新。
 *
 * <p>与激励域 {@code NegativeFeedback}（质量事故负反馈）无关，不得混用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ipd_ai_feedback", autoResultMap = true)
public class IpdAiFeedback extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    /** RUN_MESSAGE / ARTIFACT_VERSION。 */
    private String targetType;
    private Long targetId;
    /** 目标所属项目（授权锚点，写入时由服务端解析，不信任请求）。 */
    private Long projectId;
    private Long personId;
    /** UP / DOWN。 */
    private String rating;
    private String reason;
    @Version
    private Integer version;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
