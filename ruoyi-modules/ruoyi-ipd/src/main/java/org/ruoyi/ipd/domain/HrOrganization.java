package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * HR 组织行（FA-HR-Sync R149-v1 D6）。
 *
 * <p>承载 HR §3.2.2 给的"全公司组织树"（ORGEH/STEXT/SHORT/ORGEH_PUP/BMFZR 等），
 * 与 IPD 既有的 {@code product_groups}（产品组）语义不同，**独立落库、不交叉**：
 * <ul>
 *   <li>{@code product_groups}：IPD 业务侧产品组，含 owner/responsibility</li>
 *   <li>{@code hr_organizations}：HR 真源公司组织树，含部门负责人 PERNR、层级</li>
 * </ul>
 *
 * <p>persons.group_id 仍然绑 product_groups（业务侧维护），HR 同步不修改；
 * HR 给的部门编码 {@code orgeh} 留作"组织源标识"备查（{@code orgSourceId}），不参与 persons 表写入。
 *
 * <p>多租户：表登记在 application.yml 的 tenant.excludes（不参与租户隔离）；HR 是公司级数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "hr_organizations", autoResultMap = true)
@AutoMapper(target = HrOrganization.class)
public class HrOrganization extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** HR 组织编码（ORGEH；公司全树唯一）。 */
    @TableField("orgeh")
    private String orgeh;

    /** 组织全称（STEXT）。 */
    @TableField("stext")
    private String stext;

    /** 组织简称（SHORT）。 */
    @TableField("short_name")
    private String shortName;

    /** 有效起（BEGDA；yyyy-MM-dd）。 */
    @TableField("begda")
    private String begda;

    /** 有效止（ENDDA；yyyy-MM-dd；空=当前有效）。 */
    @TableField("endda")
    private String endda;

    /** 上级组织编码（ORGEH_PUP；根节点=NULL）。 */
    @TableField("parent_orgeh")
    private String parentOrgeh;

    /** 部门负责人 PERNR（BMFZR；外键 persons.employee_no，弱约束）。 */
    @TableField("bmfzr")
    private String bmfzr;

    /** 层级（ZBMCJ；数字字符串，1/2/3...）。 */
    @TableField("zbmcj")
    private String zbmcj;

    /** 删除标记（HR delFlag；X=已删）。 */
    @TableField("hr_del_flag")
    private String hrDelFlag;

    /** 过期标记（HR expirationflag；X=已过期）。 */
    @TableField("expiration_flag")
    private String expirationFlag;

    /** 最后同步时间（最新一次 HR 同步时间）。 */
    @TableField("last_sync_at")
    private Date lastSyncAt;

    /** 同步触发者（CRON_DAILY / MANUAL_adminId / INITIAL_seed）。 */
    @TableField("trigger_by")
    private String triggerBy;

    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
