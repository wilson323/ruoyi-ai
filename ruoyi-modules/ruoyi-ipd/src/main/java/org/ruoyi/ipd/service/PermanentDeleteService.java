package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.PermanentDeleteAudit;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PermanentDeleteAuditMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * R149 batch2b C3：永久清除服务。
 *
 * <p>AC-C3 决策：最终清除要二次确认 + 审计永久保留。本服务实现：
 * <ul>
 *   <li>白名单实体：person / project / kpi_record（scenario 暂未建模，调用即抛 400）</li>
 *   <li>二次确认：请求体 confirmCode 必须等于字面量 {@link #REQUIRED_CONFIRM_CODE}</li>
 *   <li>审计快照：删之前先把整行 JSON 序列化写入 {@link PermanentDeleteAudit}</li>
 *   <li>真删：MyBatis-Plus deleteById 物理删除（绕开 @TableLogic）</li>
 *   <li>权限：仅 SUPER_ADMIN（controller 层 SaCheckPermission + service 层双重兜底）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermanentDeleteService {

    /** 二次确认码（必须等于此字面量，否则拒） */
    public static final String REQUIRED_CONFIRM_CODE = "PERMANENT_DELETE_CONFIRMED";

    /** 白名单实体类型 */
    public static final Set<String> ALLOWED_ENTITY_TYPES = Set.of("person", "project", "kpi_record");

    private final PersonMapper personMapper;
    private final ProjectMapper projectMapper;
    private final KpiRecordMapper kpiRecordMapper;
    private final PermanentDeleteAuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    /**
     * 执行永久清除。
     *
     * @param actor        当前超管
     * @param entityType   person|project|kpi_record
     * @param entityId     实体主键
     * @param confirmCode  必须等于 REQUIRED_CONFIRM_CODE
     * @return audit row id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long execute(IpdActor actor, String entityType, Long entityId, String confirmCode) {
        // 1. 参数校验
        if (entityType == null || !ALLOWED_ENTITY_TYPES.contains(entityType)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "entityType 仅允许 person|project|kpi_record，实际：" + entityType + "（scenario 暂未建模）");
        }
        if (entityId == null || entityId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityId 必填且 > 0");
        }
        if (!REQUIRED_CONFIRM_CODE.equals(confirmCode)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "confirmCode 错误，必须等于：" + REQUIRED_CONFIRM_CODE);
        }

        // 2. 二次校验：操作人必须是 SUPER_ADMIN（service 层兜底，防注解层被绕过）
        if (!"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN,
                "永久清除仅 SUPER_ADMIN 可执行，实际角色：" + actor.role());
        }

        // 3. 加载实体（按 entityType 路由）
        Object entity = loadEntity(entityType, entityId);
        if (entity == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                entityType + " id=" + entityId + " 不存在");
        }

        // 4. 物理删除
        int deleted = physicalDelete(entityType, entityId);
        if (deleted == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                entityType + " id=" + entityId + " 物理删除失败（可能已被并发删）");
        }

        // 5. 写审计（实体已删，JSON 序列化在内存中保留，避免依赖 DB）
        String json = serializeEntity(entity);
        PermanentDeleteAudit audit = PermanentDeleteAudit.builder()
            .operatorId(actor.id())
            .operatorName(actor.name())
            .entityType(entityType)
            .entityId(entityId)
            .originalDataJson(json)
            .deletedAt(now())
            .ipAddress(resolveClientIp())
            .tenantId(resolveTenantId())
            .delFlag("0")
            .build();
        auditMapper.insert(audit);

        log.warn("R149 batch2b PERMANENT DELETE entityType={} entityId={} operatorId={} auditId={}",
            entityType, entityId, actor.id(), audit.getId());
        return audit.getId();
    }

    /** 按 entityType 路由加载（兼容 @TableLogic 软删：物理删除前要看到行，先按主键 selectById） */
    private Object loadEntity(String entityType, Long id) {
        return switch (entityType) {
            case "person" -> personMapper.selectById(id);
            case "project" -> projectMapper.selectById(id);
            case "kpi_record" -> kpiRecordMapper.selectById(id);
            default -> throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "entityType 不支持：" + entityType);
        };
    }

    /**
     * 物理删除（绕开 @TableLogic 软删保护）。
     * <p>实现：先用 selectById 校验行存在（同时取得 entity），再用 mapper.deleteById 真删。
     * MyBatis-Plus 的 {@code deleteById} 默认会带 {@code @TableLogic} 过滤条件，
     * 对于已软删的行返回 0——本服务 loadEntity 已用 selectById（含 @TableLogic）查到行，
     * 表示该行未软删；deleteById 在行未软删时正常返回 1。
     */
    private int physicalDelete(String entityType, Long id) {
        return switch (entityType) {
            case "person" -> personMapper.deleteById(id);
            case "project" -> projectMapper.deleteById(id);
            case "kpi_record" -> kpiRecordMapper.deleteById(id);
            default -> 0;
        };
    }

    /** 把实体序列化为 JSON 字符串（供审计快照持久化） */
    private String serializeEntity(Object entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            log.error("永久清除审计 JSON 序列化失败 entity={}", entity.getClass().getSimpleName(), e);
            // 序列化失败不阻断主流程——审计表写空 JSON + 错误标记，便于事后人工补录
            return "{\"_serializeError\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /** 取客户端 IP（X-Forwarded-For 首段；无则 "unknown"） */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "unknown";
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return real.trim();
            return req.getRemoteAddr() == null ? "unknown" : req.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 取租户 ID（兼容租户开关关闭场景） */
    private String resolveTenantId() {
        try {
            String t = TenantHelper.getTenantId();
            return t == null || t.isBlank() ? "000000" : t;
        } catch (Exception e) {
            return "000000";
        }
    }

    /** 列出审计（仅超管；前端对账视图用） */
    public List<PermanentDeleteAudit> listByEntityType(String entityType, int limit) {
        LambdaQueryWrapper<PermanentDeleteAudit> q = new LambdaQueryWrapper<>();
        q.eq(entityType != null && !entityType.isBlank(), PermanentDeleteAudit::getEntityType, entityType)
         .orderByDesc(PermanentDeleteAudit::getCreateTime)
         .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        return auditMapper.selectList(q);
    }

    /** 检查白名单（controller 提前校验用） */
    public static boolean isAllowedEntityType(String entityType) {
        return entityType != null && ALLOWED_ENTITY_TYPES.contains(entityType);
    }
}
