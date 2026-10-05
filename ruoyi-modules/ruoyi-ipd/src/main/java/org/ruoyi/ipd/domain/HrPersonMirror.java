package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * HR 人员档案镜像行（D10 评审落地，2026-09-29）。
 *
 * <p>承载《IPD系统_熵基EHR项目接口需求-2026.9.22》「产品IPD」Sheet 的员工信息全字段
 * （身份识别 / 联系方式 / 组织岗位 / 汇报关系 / 学历扩展），来源 {@code zkteco.ehr.getUserInfo} §3.2.1。
 *
 * <p><b>防双轨边界</b>（沿用 R149 原则，owner 2026-09-29 拍板）：
 * <ul>
 *   <li>本表是 HR 真源字段快照，<b>不是登录体系</b>——登录/权限仍走 {@code persons}
 *       （username=pernr / bcrypt(pernr) / 首登改密），本表不参与鉴权</li>
 *   <li>{@code pernr} 软引用 {@code persons.employee_no}，不加外键（HR 口径过滤后可能暂无 persons 行）</li>
 *   <li>{@code orgeh}/{@code deptname} 为冗余快照（查询便利）；组织树真源在 {@code hr_organizations}</li>
 *   <li>DEL_FLAG 不落表（IPD 口径：删除标识 ×）；离职判定只看 {@code leaveFlag}（+STAT2）</li>
 * </ul>
 *
 * <p>多租户：登记在 application.yml 的 tenant.excludes（HR 是公司级数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "hr_person_mirror", autoResultMap = true)
public class HrPersonMirror extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 工号 PERNR（业务键；软引用 persons.employee_no）。 */
    @TableField("pernr")
    private String pernr;

    /** 员工姓名 NACHN。 */
    @TableField("nachn")
    private String nachn;

    /** 英文名 RUFNM。 */
    @TableField("rufnm")
    private String rufnm;

    /** 性别 GESCH（1=男 2=女，HR 原始值）。 */
    @TableField("gesch")
    private String gesch;

    /** 国籍 NATIO（国家编码，如 CN）。 */
    @TableField("natio")
    private String natio;

    /** 入职日期 HIRE_DATE（yyyy-MM-dd，HR 原始字符串）。 */
    @TableField("hire_date")
    private String hireDate;

    /** 离职日期 LEAVE_DATE（yyyy-MM-dd）。 */
    @TableField("leave_date")
    private String leaveDate;

    /** 手机号 PHONE。 */
    @TableField("phone")
    private String phone;

    /** 企业邮箱 EMAIL_COM。 */
    @TableField("email_com")
    private String emailCom;

    /** 部门编码 ORGEH（关联 hr_organizations.orgeh）。 */
    @TableField("orgeh")
    private String orgeh;

    /** 部门名称 DEPTNAME。 */
    @TableField("deptname")
    private String deptname;

    /** 岗位代码 PLANS。 */
    @TableField("plans")
    private String plans;

    /** 标准岗位 POSITIONNAME。 */
    @TableField("positionname")
    private String positionname;

    /** 职级 POSITIONGRADE（职位图谱等级）。 */
    @TableField("positiongrade")
    private String positiongrade;

    /** 任职资格等级 QUALIFICATIONLEVEL。 */
    @TableField("qualificationlevel")
    private String qualificationlevel;

    /** 直接上级工号 SUPERVISORNO。 */
    @TableField("supervisorno")
    private String supervisorno;

    /** 直接上级姓名 SUPERVISORNAME。 */
    @TableField("supervisorname")
    private String supervisorname;

    /** 最高学历 CERTIFICATE（仅主学历）。 */
    @TableField("certificate")
    private String certificate;

    /** 毕业院校 INSITUTE。 */
    @TableField("insitute")
    private String insitute;

    /** 专业 LINE_OF_STUDY。 */
    @TableField("line_of_study")
    private String lineOfStudy;

    /** 员工类型 EMPCATEGORY（Regular/Probation/Relationship，入库前已按 IPD 口径过滤）。 */
    @TableField("empcategory")
    private String empcategory;

    /** 员工状态 STAT2（3=在职 0=离职）。 */
    @TableField("stat2")
    private String stat2;

    /** 离职标识 LEAVE_FLAG（X=离职生效；IPD 口径 √）。 */
    @TableField("leave_flag")
    private String leaveFlag;

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
