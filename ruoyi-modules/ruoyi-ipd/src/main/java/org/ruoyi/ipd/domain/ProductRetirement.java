package org.ruoyi.ipd.domain;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import java.util.Date;
/** Existing product retirement record. Historical two-level columns are preserved as evidence. */
@Data @EqualsAndHashCode(callSuper=true) @TableName("product_retirements")
public class ProductRetirement extends BaseEntity {
 @TableId(type=IdType.ASSIGN_ID) private Long id;
 private Long productId; private Long proposerId; private String proposerRole; private String reason;
 private Integer readinessActiveProjects; private Integer readinessActiveReviews; private Integer readinessOpenIssues;
 private String readinessPassed; private String status;
 private Long rdLeaderId; private String rdDecision; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date rdDecidedAt; private String rdOpinion;
 private Long superAdminId; private String superDecision; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date superDecidedAt; private String superOpinion;
 @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date dueAt; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date approvedAt; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date rejectedAt;
 @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date marketingStopAt; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date orderStopAt; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date productionStopAt;
 @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date spareSupportStopAt; @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private Date softwareSupportStopAt; private String softwareSupportPolicy;
 private String tenantId; @TableLogic private String delFlag; @Version private Integer version;
}
