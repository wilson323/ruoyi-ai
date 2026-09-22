package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.CorrectionLog;
import org.ruoyi.ipd.mapper.CorrectionLogMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 字段更正留痕服务（P3-2.2 更正留痕子模块）。
 *
 * <p>写入路径：record() 一律落审计留痕；写入语义为「追加」而非「覆盖」。
 * 读取路径：listByEntity() 仅超管可访问（SEC-API-01 同严），并强制追加 meta-audit
 * （P3-2.2 postreview finding 3：审计链读取本身需留痕）。
 *
 * <p>必填字段校验（PARAM_INVALID 即报错，R-P3-2.2-POSTREVIEW 升级）：
 * <ul>
 *   <li>actor 非空；entityType 必须是 {@link CorrectionLog.EntityType} 枚举内值；</li>
 *   <li>entityId / fieldName / newValue / reason 非空且 trim；</li>
 *   <li>长度上限：entityType ≤ 64 / fieldName ≤ 128 / newValue & oldValue ≤ 4096 / reason ≤ 1024；</li>
 *   <li>reason 拒绝控制字符（U+0000~U+001F 除去 \t\n\r）；</li>
 *   <li>所有 free-text 字段先 trim 再校验，避免前后空白绕过长度上限。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CorrectionLogServiceImpl implements ICorrectionLogService {

    /** entityType 长度上限（DB 列宽与可读性折中）。 */
    private static final int ENTITY_TYPE_MAX = 64;
    /** fieldName 长度上限（DB 列宽 128）。 */
    private static final int FIELD_NAME_MAX = 128;
    /** newValue / oldValue 长度上限（4 KiB，足以容纳长文本/JSON 摘要）。 */
    private static final int VALUE_MAX = 4096;
    /** reason 长度上限（1 KiB，业务文案）。 */
    private static final int REASON_MAX = 1024;

    private final CorrectionLogMapper correctionLogMapper;
    private final IpdPermission permission;
    private final IAuditLogService auditLogService;

    /**
     * 写入一条字段更正留痕。
     *
     * <p>旧值与新值相同时视为 NO-OP，不入库；reason 必填（强约束业务责任人）。
     * 所有 free-text 字段在 trim 后写入，避免后置空白污染审计显示。
     *
     * @param actor      当前操作人（必填，可为 requireInternal 结果）
     * @param entityType 实体类型（必须为 {@link CorrectionLog.EntityType} 枚举内值）
     * @param entityId   实体 ID
     * @param fieldName  字段名
     * @param oldValue   旧值（允许 null）
     * @param newValue   新值
     * @param reason     更正原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(IpdActor actor, String entityType, Long entityId, String fieldName,
                       String oldValue, String newValue, String reason) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED, "未登录");
        }
        String entityTypeTrim = trimOrNull(entityType);
        if (entityTypeTrim == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityType 不能为空");
        }
        if (entityTypeTrim.length() > ENTITY_TYPE_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "entityType 长度超过上限 " + ENTITY_TYPE_MAX);
        }
        validateEntityType(entityTypeTrim);
        if (entityId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityId 不能为空");
        }
        String fieldNameTrim = trimOrNull(fieldName);
        if (fieldNameTrim == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "fieldName 不能为空");
        }
        if (fieldNameTrim.length() > FIELD_NAME_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "fieldName 长度超过上限 " + FIELD_NAME_MAX);
        }
        String newValueTrim = trimOrNull(newValue);
        if (newValueTrim == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "newValue 不能为空");
        }
        if (newValueTrim.length() > VALUE_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "newValue 长度超过上限 " + VALUE_MAX);
        }
        String oldValueTrim = oldValue == null ? null : oldValue.trim();
        if (oldValueTrim != null && oldValueTrim.length() > VALUE_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "oldValue 长度超过上限 " + VALUE_MAX);
        }
        String reasonTrim = trimOrNull(reason);
        if (reasonTrim == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "reason 不能为空");
        }
        if (reasonTrim.length() > REASON_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "reason 长度超过上限 " + REASON_MAX);
        }
        if (hasControlChar(reason)) {
            // 控制字符检测必须在 trim 之前：Java String.trim() 会剥离 c<=0x20 的字符（含 BEL/BS），
            // 届时已被悄悄吃掉的控制字符无法被 hasControlChar 察觉 → 注入字符溜进审计字段
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "reason 含控制字符（拒绝 \\u0000~\\u001F 除 \\t\\n\\r 外）");
        }
        // NO-OP 短路：旧值与新值（trim 后）完全相同不写库（避免审计噪声）
        if (safeEquals(oldValueTrim, newValueTrim)) {
            return;
        }

        CorrectionLog row = CorrectionLog.builder()
            .entityType(entityTypeTrim)
            .entityId(entityId)
            .fieldName(fieldNameTrim)
            .oldValue(oldValueTrim)
            .newValue(newValueTrim)
            .reason(reasonTrim)
            .operatorId(actor.id())
            .operatorName(actor.name())
            .operatedAt(new Date())
            .build();
        permission.bindCreateAudit(row, actor);
        correctionLogMapper.insert(row);
    }

    /**
     * 按实体维度反查更正历史，按 operatedAt DESC。
     *
     * <p>R-P3-2.2-POSTREVIEW：超管读审计链本身也需落 meta-audit（SEC-AUD-02 同严），
     * 防止「读审计」游离在审计链外。所有 listByEntity 调用必须传入当前操作人；
     * 读取前先在 {@link IAuditLogService#append} 中追加一条 READ 动作。
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<CorrectionLog> listByEntity(String entityType, Long entityId, IpdActor actor) {
        IpdActor admin = permission.requireAdmin();
        String entityTypeTrim = trimOrNull(entityType);
        if (entityTypeTrim == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityType 不能为空");
        }
        validateEntityType(entityTypeTrim);
        if (entityId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityId 不能为空");
        }
        // meta-audit：超管读审计链一律留痕（actor 优先取调用方，否则退化 admin）
        IpdActor effective = actor != null && actor.id() != null ? actor : admin;
        auditLogService.append(AuditLog.builder()
            .operatorName(actorName(effective))
            .operatorRole("SUPER_ADMIN")
            .action("READ")
            .entityType(entityTypeTrim)
            .entityId(entityId)
            .reason("P3-2.2 admin 读审计链（correction_log）")
            .build());
        return correctionLogMapper.selectList(
            new LambdaQueryWrapper<CorrectionLog>()
                .eq(CorrectionLog::getEntityType, entityTypeTrim)
                .eq(CorrectionLog::getEntityId, entityId)
                .orderByDesc(CorrectionLog::getOperatedAt));
    }

    /**
     * entityType 白名单校验：必须在 {@link CorrectionLog.EntityType} 枚举内。
     * OTHER 仍按枚举值收口，未在枚举内的自由字符串一律拒绝。
     */
    private static void validateEntityType(String entityType) {
        for (CorrectionLog.EntityType v : CorrectionLog.EntityType.values()) {
            if (v.name().equals(entityType)) {
                return;
            }
        }
        throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
            "entityType 非法（仅 " + enumNames() + "）");
    }

    private static String enumNames() {
        CorrectionLog.EntityType[] values = CorrectionLog.EntityType.values();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(values[i].name());
        }
        return sb.toString();
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 控制字符检测：{@code c < 0x20} 且非 {@code \t / \n / \r}。
     * 用于拒绝 reason 字段里夹带的 ANSI/日志注入字符（{@code } 等）。
     */
    private static boolean hasControlChar(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return true;
            }
        }
        return false;
    }

    private static String actorName(IpdActor actor) {
        if (actor == null) return "SYSTEM";
        return actor.name() == null
            ? (actor.id() == null ? "SYSTEM" : actor.id().toString())
            : actor.name();
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
