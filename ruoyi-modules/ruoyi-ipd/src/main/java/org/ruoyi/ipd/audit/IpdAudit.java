package org.ruoyi.ipd.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * P2-1 结构性修复：声明式审计注解（配合 {@link IpdAuditAspect}）。
 *
 * <p>挂在写端点（Controller 方法）上，方法成功返回后自动落一条审计
 * （业务异常不落，与既有手工版「成功才审」口径一致）。SpEL 表达式
 * 可引用方法参数（编译已开 -parameters）。
 *
 * <p>注意：已有手工 {@code auditLogService.append} 的端点不得再加本注解
 * （会双重审计）；本注解只用于补零审计的写端点，存量迁移按域渐进。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IpdAudit {

    /** 审计动作名（沿用既有大写惯例，如 SWITCHING_LOCK）。 */
    String action();

    /** 审计对象类型（如 switching_acceptance）。 */
    String entityType();

    /** entityId 的 SpEL（可选）；求值为 Number/数字字符串时落 Long，否则 null。 */
    String entityIdExpr() default "";

    /** reason 的 SpEL（可选，可拼接参数，如 "#month + ' | ' + #req.reason"）。 */
    String reasonExpr() default "";
}
