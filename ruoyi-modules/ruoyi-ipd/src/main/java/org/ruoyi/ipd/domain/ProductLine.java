package org.ruoyi.ipd.domain;

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
 * 产品线团队大工作空间。一个产品线可包含多个 Product，项目空间由 Product 关联。
 * 与组织 ProductGroup 分离，组织调整不改变空间身份。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "product_lines", autoResultMap = true)
public class ProductLine extends BaseEntity implements SoftDeletable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String lineCode;
    private String lineName;
    /** 管理员指定，且须为该空间在职成员。 */
    private Long leaderPersonId;
    private String status;
    private String tenantId;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    @Override
    public void setDelFlag(String flag) {
        this.delFlag = flag;
    }
}
