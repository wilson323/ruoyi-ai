package org.ruoyi.ipd.audit;

import cn.dev33.satoken.exception.NotLoginException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.service.AuditLogService;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * P2-1 结构性修复：@IpdAudit 声明式审计切面。
 *
 * <p>背景（系统性梳理-20260909 §P2-1）：审计写点此前以手工 append 散落 80+ 处，
 * 新写端点极易漏审计（SwitchingAcceptance run/lock/unlock 即为实证缺口）。
 * 本切面提供「注解即审计」：目标方法成功返回后落一条审计；业务异常不落
 * （与手工版口径一致）。actor 从 {@link IpdAuthSession} 当前 Person 推导
 * （与 IpdPermission.requireInternal 同映射，但不做 scope 校验——能走到
 * Controller 方法的请求已通过各自鉴权）。
 *
 * <p>首批挂载：SwitchingAcceptanceController 3 写端点（此前零审计）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IpdAuditAspect {

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final AuditLogService auditLogService;
    private final IpdAuthSession session;

    @Around("@annotation(ipdAudit)")
    public Object auditAround(ProceedingJoinPoint pjp, IpdAudit ipdAudit) throws Throwable {
        Object result = pjp.proceed();
        writeAudit(pjp, ipdAudit);
        return result;
    }

    private void writeAudit(ProceedingJoinPoint pjp, IpdAudit ann) {
        IpdActor actor;
        try {
            Person person = session.currentPerson();
            actor = new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
        } catch (NotLoginException e) {
            // 受保护写端点理论不可达；出现说明鉴权链被绕过——不落审计但不吞业务响应，WARN 留痕。
            log.warn("[IpdAudit] {} {} 跳过：无登录上下文（写端点不应出现，疑似鉴权链异常）",
                ann.entityType(), ann.action());
            return;
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Long entityId = null;
        String reason = null;
        boolean needsCtx = (ann.entityIdExpr() != null && !ann.entityIdExpr().isBlank())
            || (ann.reasonExpr() != null && !ann.reasonExpr().isBlank());
        if (needsCtx) {
            MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(
                actor, signature.getMethod(), pjp.getArgs(), PARAMETER_NAME_DISCOVERER);
            entityId = evalLong(ann.entityIdExpr(), ctx);
            reason = evalString(ann.reasonExpr(), ctx);
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id())
            .operatorName(actor.name())
            .operatorRole(actor.role())
            .action(ann.action())
            .entityType(ann.entityType())
            .entityId(entityId)
            .reason(reason)
            .build());
    }

    private Long evalLong(String expr, MethodBasedEvaluationContext ctx) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        Object value = PARSER.parseExpression(expr).getValue(ctx);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String evalString(String expr, MethodBasedEvaluationContext ctx) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        Object value = PARSER.parseExpression(expr).getValue(ctx);
        return value == null ? null : String.valueOf(value);
    }
}
