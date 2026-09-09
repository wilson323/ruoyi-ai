package org.ruoyi.ipd.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * IPD 审计注解（审计 AOP 改造设计-20260909 §3.2，R22 落地）。
 * 标注在 Spring bean 公有方法上，方法<b>成功返回后</b>由 {@link IpdAuditAspect} 同步落一条审计。
 * <p>红线（设计 §3.3）：切面<b>同步</b>调用 {@code AuditLogService.append}（保 REQUIRES_NEW +
 * 锚行锁 seq 串行语义），<b>禁止</b>仿 ruoyi-common-log LogAspect 的 publishEvent 异步——
 * 异步会吞掉 requireJson 校验失败信号，且与 verifyChain 验链时序冲突。
 * <p>适用边界（设计 §2 六类不可注解化之外的子集）：action/entityType 静态常量、无
 * before/after 快照。带快照、动态 action、循环多条、异常路径前落库、特殊操作人的调用点
 * <b>保留手写 append</b>，不要硬套本注解。
 *
 * <pre>
 * &#64;IpdAudit(action = "RECEIPT_CREATE",
 *           entityType = IpdEntityType.RECEIPT_LEDGER,
 *           entityId   = "#result.data.id",        // SpEL：#result = 方法返回值，#参数名 / #p0 位置引用
 *           reason     = "'回款录入 ' + #req.receiptMonth()",
 *           adminOnly  = true)                     // 切面先 IpdPermission.requireAdmin() 取操作人
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IpdAudit {

    /** 动作码（静态常量，如 RECEIPT_CREATE / COEFFICIENT_PROPOSE）。 */
    String action();

    /** 实体类型（建议引用 {@link IpdEntityType} 常量，统一命名；历史存量字符串值保持不变）。 */
    String entityType();

    /**
     * 实体 ID 的 SpEL。上下文：方法参数（按名，编译已开 -parameters；亦可用 #p0/#p1 位置引用）
     * 与 {@code #result}（方法返回值；仅 @AfterReturning 阶段可解析）。
     */
    String entityId() default "";

    /** 审计理由的 SpEL，上下文同 {@link #entityId()}。返回前解析；解析失败按空 串降级并 WARN。 */
    String reason() default "";
    
    /**
     * 实体 ID 的 SpEL（P2轮三别名，与 {@link #entityId()} 同义）。
     * 2026-09-09 双线合并兼容：P2轮三（SwitchingAcceptanceController 3 端点）用本属性名，
     * R22 框架用 {@link #entityId()}；切面按 entityId 优先、为空再看 entityIdExpr 解析。
     */
    String entityIdExpr() default "";
    
    /** 审计理由的 SpEL（P2轮三别名，与 {@link #reason()} 同义；解析优先级低于 {@link #reason()}）。 */
    String reasonExpr() default "";

    /**
     * 操作人 IpdActor 的 SpEL（方法参数形式，如 {@code "#actor"}）。
     * 与 {@link #adminOnly()} 互斥；都未提供且 {@link #operatorId()} 为空时审计行 operator 三元组
     * 由 append 单点按 operatorId 补齐（R22 合并框架语义）或留空。
     */
    String operator() default "";

    /**
     * 无用户上下文时的系统操作人 ID（如 KPI 扫描器 0）。设置后切面用该 ID 落审计，
     * operatorName 走 append 补齐或空串。与 {@link #operator()} 互斥。
     * （注解属性只能是原生类型，不可用包装 Long）
     */
    long systemOperatorId() default 0L;

    /** true = 切面在 proceed 前调 {@code IpdPermission.requireAdmin()} 取操作人（方法体内可省略）。 */
    boolean adminOnly() default false;
}
