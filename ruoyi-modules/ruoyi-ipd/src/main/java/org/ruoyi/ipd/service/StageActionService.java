package org.ruoyi.ipd.service;

import org.ruoyi.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段动作实例服务：深轻管分离完成校验（BR-IPD-03/04/05，动作清单 v3）
 *
 * 校验矩阵（以 ActionCatalog 目录为 SSOT，不信任前端）：
 * - 深管 DONE：至少 1 个未删交付物（del_flag=0）——BR-IPD-03 强制附件
 * - 轻管 DONE：actual_done_at 必填（BR-IPD-05）；且**不允许 DELAYED**（轻管枚举无延期）
 * - 数值登记（valueFields）：D11=FAR,FRR；V02=CERT_NO,CERT_DATE
 * - 阻断跳阶（is_blocking）由 P1-5 GateEngine 消费本表状态
 *
 * P1-4.3 状态机（仅 /transit 入口，禁止 PATCH status 字段）：
 * - 深管：NOT_STARTED → IN_PROGRESS → DONE / NA / DELAYED
 * - 轻管：NOT_STARTED → IN_PROGRESS → DONE / NA（无 DELAYED）
 * - NA 必传 reason（防绕过）
 * - 幂等：同 id 同 target 重复 /transit 返回当前状态，不写新审计
 * - 乐观锁：@Version；并发同 id 仅 1 成功
 */
@Service
@RequiredArgsConstructor
public class StageActionService implements IStageActionService {

    private static final Set<String> LIGHT_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "NA");
    private static final Set<String> DEEP_EXTRA_STATUSES = Set.of("DELAYED");
    private static final Set<String> DEEP_ALLOWED = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "NA", "DELAYED");

    private final StageActionMapper stageActionMapper;
    private final DeliverableMapper deliverableMapper;
    private final IAuditLogService auditLogService;
    private final ProjectStageMapper projectStageMapper;
    private final ProjectMapper projectMapper;

    /**
     * R219 卡④（ef20c06a）逾期动作通知链的可选依赖：setter 注入，
     * 不扩 @RequiredArgsConstructor 构造签名（既有 5 参构造调用方不破）。
     * 缺失时 notifyOverdueActions 只扫描返回 0，不影响主业务。
     */
    private NotificationService notificationService;
    private ProjectMemberMapper projectMemberMapper;
    private ProductGroupMapper productGroupMapper;

    @Autowired(required = false)
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired(required = false)
    public void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }

    @Autowired(required = false)
    public void setProductGroupMapper(ProductGroupMapper productGroupMapper) {
        this.productGroupMapper = productGroupMapper;
    }

    public StageAction getById(Long id) {
        StageAction a = stageActionMapper.selectById(id);
        if (a == null) {
            throw new ServiceException("动作实例不存在: " + id);
        }
        return a;
    }

    public List<StageAction> listByProject(Long projectId) {
        return stageActionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId)
                .orderByAsc(StageAction::getActionCode));
    }

    /**
     * 状态迁移唯一入口（P1-4.3）。
     * - 状态机白名单（depth + 目标）
     * - 幂等：当前态 == 目标态 → 直接返回，不写库、不写审计
     * - NA 必 reason
     * - DONE 触发深度+数值双重校验
     * - 乐观锁：@Version，updateById 失败（version 冲突）抛 ServiceException
     * - 每次成功迁移写审计 action=TRANSIT
     */
    @Transactional(rollbackFor = Exception.class)
    public StageAction transit(Long id, String target, String reason, String operator) {
        if (target == null) { throw new ServiceException("目标状态不能为空"); }
        StageAction a = getById(id);
        assertProjectWritable(a.getProjectId());
        ActionDef def = canonicalizeAction(a);
        boolean deep = "DEEP".equals(a.getDepth());

        if (!deep && DEEP_EXTRA_STATUSES.contains(target)) {
            throw new ServiceException("轻管动作不支持延期状态（BR-IPD-05 三字段登记）: " + def.code());
        }
        Set<String> allowed = deep ? DEEP_ALLOWED : LIGHT_STATUSES;
        if (!allowed.contains(target)) {
            throw new ServiceException("非法目标状态: " + target);
        }
        if (target.equals(a.getStatus())) {
            return a; // 幂等
        }
        if ("NA".equals(target) && (reason == null || reason.isBlank())) {
            throw new ServiceException("标记 NA 必须填写原因（防绕过 P1-4.3）: " + def.code());
        }
        // AC-PROD-13：涉生物场景下 C12 不可取消（NA）
        if ("NA".equals(target) && "C12".equals(ActionCatalog.resolveCode(a.getActionCode()))
            && hasBioFeatureActions(a.getProjectId())) {
            throw new ServiceException("涉生物项目的 C12 生物特征数据合规审查不可取消（AC-PROD-13）");
        }
        if ("DONE".equals(target)) {
            validateCompletion(a, def, deep);
        }

        String before = statusSnapshot(a);
        a.setStatus(target);
        a.setUpdateBy(actorIdOf(operator));
        if ("DONE".equals(target) && a.getActualDoneAt() == null) {
            a.setActualDoneAt(new Date(Math.floorDiv(System.currentTimeMillis(), 1000L) * 1000L));
        }
        int n = stageActionMapper.updateById(a);
        if (n != 1) {
            throw new ServiceException("动作状态更新失败：可能并发冲突或记录不存在（P1-4.3 乐观锁）: " + def.code());
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(operator).operatorRole("PM")
            .action("TRANSIT").entityType("STAGE_ACTION").entityId(a.getId())
            .beforeData(before).afterData(statusSnapshot(a))
            .reason(reason == null || reason.isBlank() ? "P1-4.3 状态机" : reason)
            .build());
        return a;
    }

    /**
     * P1-4.1 / P1-8.2：录入轻管完成日 / BioCV FAR·FRR / 证书 / 算法分类。
     * 不改 status；完成仍须随后 /transit→DONE。Z 别名写入时归一为权威码。
     *
     * @param id           动作实例 ID
     * @param actualDoneAt 实际完成日（可空表示不改）
     * @param farValue     FAR（与 frr 成对）
     * @param frrValue     FRR
     * @param certNo       证书编号
     * @param certPassedAt 证书通过日
     * @param algoType     算法分类（可空；传空串视为未提交）
     * @param operator     操作者 Person id 字符串
     * @return 更新后实例
     */
    @Transactional(rollbackFor = Exception.class)
    public StageAction recordFields(Long id, Date actualDoneAt, BigDecimal farValue, BigDecimal frrValue,
                                    String certNo, Date certPassedAt, String algoType, String operator) {
        StageAction a = getById(id);
        assertProjectWritable(a.getProjectId());
        String before = fieldsSnapshot(a);
        String rawCode = a.getActionCode();
        ActionDef def = canonicalizeAction(a);
        boolean touched = rawCode != null && !def.code().equals(rawCode);
        String vf = def.valueFields() == null ? "" : def.valueFields();

        if (actualDoneAt != null) {
            a.setActualDoneAt(new Date(Math.floorDiv(actualDoneAt.getTime(), 1000L) * 1000L));
            touched = true;
        }
        if (farValue != null || frrValue != null) {
            if (!vf.contains("FAR")) {
                throw new ServiceException("动作不支持 FAR/FRR 录入: " + def.code());
            }
            if (farValue == null || frrValue == null) {
                throw new ServiceException("FAR/FRR 必须成对登记: " + def.code());
            }
            assertRate01(farValue, "FAR");
            assertRate01(frrValue, "FRR");
            a.setFarValue(farValue);
            a.setFrrValue(frrValue);
            touched = true;
        }
        if (certNo != null || certPassedAt != null) {
            if (!vf.contains("CERT_NO")) {
                throw new ServiceException("动作不支持证书字段录入: " + def.code());
            }
            if (certNo != null) {
                if (certNo.isBlank()) {
                    throw new ServiceException("证书编号不能为空: " + def.code());
                }
                a.setCertNo(certNo.trim());
                touched = true;
            }
            if (certPassedAt != null) {
                a.setCertPassedAt(new Date(Math.floorDiv(certPassedAt.getTime(), 1000L) * 1000L));
                touched = true;
            }
        }
        if (algoType != null && !algoType.isBlank()) {
            if (!vf.contains("FAR") && !"1".equals(a.getIsBioFeature())) {
                throw new ServiceException("动作不支持算法分类录入: " + def.code());
            }
            try {
                a.setAlgoType(ActionCatalog.normalizeAlgoType(algoType));
                touched = true;
            } catch (IllegalArgumentException ex) {
                throw new ServiceException(ex.getMessage());
            }
        }
        if (!touched) {
            throw new ServiceException("未提交任何可写字段（actualDoneAt/FAR·FRR/证书/算法分类）");
        }
        a.setUpdateBy(actorIdOf(operator));
        int n = stageActionMapper.updateById(a);
        if (n != 1) {
            throw new ServiceException("字段更新失败：可能并发冲突或记录不存在: " + def.code());
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(operator).operatorRole("PM")
            .action("RECORD_FIELDS").entityType("STAGE_ACTION").entityId(a.getId())
            .beforeData(before).afterData(fieldsSnapshot(a))
            .reason("P1-4.1/P1-8.2 完成字段录入")
            .build());
        return a;
    }

    /**
     * P1-8.2：Z 系编码归一为权威码并回填动作名。
     *
     * @param a 动作实例（可能就地改写 actionCode/actionName）
     * @return 目录定义
     */
    private ActionDef canonicalizeAction(StageAction a) {
        String raw = a.getActionCode();
        ActionDef def = ActionCatalog.byCode(raw);
        String resolved = def.code();
        if (raw != null && !resolved.equals(raw)) {
            a.setActionCode(resolved);
            a.setActionName(def.name());
        }
        return def;
    }

    /** FAR/FRR 取值须在 [0,1]。 */
    private static void assertRate01(BigDecimal v, String label) {
        if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(BigDecimal.ONE) > 0) {
            throw new ServiceException(label + " 须在 0~1 之间");
        }
    }

    private static String fieldsSnapshot(StageAction a) {
        return AuditEventData.json(
            "actionCode", a.getActionCode(),
            "actualDoneAt", a.getActualDoneAt() == null ? null : a.getActualDoneAt().getTime(),
            "farValue", a.getFarValue(),
            "frrValue", a.getFrrValue(),
            "certNo", a.getCertNo(),
            "certPassedAt", a.getCertPassedAt() == null ? null : a.getCertPassedAt().getTime(),
            "algoType", a.getAlgoType());
    }

    private void validateCompletion(StageAction a, ActionDef def, boolean deep) {
        if (deep) {
            Long cnt = deliverableMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Deliverable>()
                    .eq(Deliverable::getActionId, a.getId())
                    .eq(Deliverable::getDelFlag, "0"));
            if (cnt == null || cnt == 0) {
                throw new ServiceException("深管动作完成前必须上传至少 1 个未删交付物（BR-IPD-03）: " + def.code());
            }
        } else if (a.getActualDoneAt() == null) {
            throw new ServiceException("轻管动作完成必须登记实际完成日期（BR-IPD-05）: " + def.code());
        }
        String vf = def.valueFields() == null ? "" : def.valueFields();
        if (vf.contains("FAR") && (a.getFarValue() == null || a.getFrrValue() == null)) {
            throw new ServiceException("BioCV 算法评测必须登记实测 FAR/FRR（动作清单 v3 例外二）: " + def.code());
        }
        if (vf.contains("CERT_NO") && (a.getCertNo() == null || a.getCertNo().isBlank()
            || a.getCertPassedAt() == null)) {
            throw new ServiceException("认证送检完成必须登记证书编号与通过日期（v3 例外一）: " + def.code());
        }
    }

    /**
     * P1-8.1 / AC-PROD-13：项目已有涉生物动作且缺少 C12 时，补挂到 CONCEPT 阶段。
     * 幂等：无涉生物 / 已有 C12 → 返回 0；新挂返回 1。
     *
     * @param projectId 项目主键
     * @param actor     操作人（R212-③：必填；组归属断言主体）
     * @return 新建 C12 条数（0 或 1）
     */
    @Transactional(rollbackFor = Exception.class)
    public int ensureBioComplianceMount(Long projectId, IpdActor actor) {
        // R212-③（看板卡 dbe1b6a7）：补 actor 形参与组归属断言——原方法无 actor，
        // 控制器仅 requireInternal 宽角色门 ⇒ 任意内部 PM 可跨组挂载 C12 合规动作。
        // 顺序：actor 必填 → 参数正数（既有语义不变，各角色一致）→ 状态门禁 → 组归属。
        IpdIdorGuard.requireAuthenticated(actor);
        if (projectId == null || projectId <= 0) {
            throw new ServiceException("项目ID必须为正数");
        }
        assertProjectWritableInGroup(projectId, actor);
        if (!hasBioFeatureActions(projectId)) {
            return 0;
        }
        Long c12 = stageActionMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId)
                .eq(StageAction::getActionCode, "C12"));
        if (c12 != null && c12 > 0) {
            return 0;
        }
        Long conceptStageId = resolveConceptStageId(projectId);
        ActionDef def = ActionCatalog.byCode("C12");
        StageAction c12Action = StageAction.builder()
            .projectId(projectId)
            .stageId(conceptStageId)
            .actionCode(def.code())
            .actionName(def.name())
            .ownerRole(def.ownerRole())
            .depth(def.depth())
            .status("NOT_STARTED")
            .isBlocking(def.blocking() ? "1" : "0")
            .isBioFeature("1")
            .build();
        c12Action.setCreateTime(new Date());
        stageActionMapper.insert(c12Action);
        if (c12Action.getId() == null || c12Action.getId() <= 0) {
            throw new ServiceException("C12 补挂失败：未生成主键");
        }
        return 1;
    }

    /**
     * 是否存在未删的涉生物动作（is_bio_feature=1）。
     *
     * @param projectId 项目主键
     * @return true 表示已标记涉生物
     */
    public boolean hasBioFeatureActions(Long projectId) {
        Long bioCount = stageActionMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId)
                .eq(StageAction::getIsBioFeature, "1"));
        return bioCount != null && bioCount > 0;
    }

    /**
     * 解析 CONCEPT 阶段实例 ID；缺失则拒绝补挂（避免 stage_id 空违反 NOT NULL）。
     *
     * @param projectId 项目主键
     * @return CONCEPT 阶段主键
     */
    private Long resolveConceptStageId(Long projectId) {
        List<ProjectStage> stages = projectStageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectStage>()
                .eq(ProjectStage::getProjectId, projectId)
                .eq(ProjectStage::getStageCode, "CONCEPT")
                .eq(ProjectStage::getDelFlag, "0")
                .last("LIMIT 1"));
        if (stages == null || stages.isEmpty() || stages.get(0).getId() == null) {
            throw new ServiceException("C12 补挂失败：项目缺少 CONCEPT 阶段实例");
        }
        return stages.get(0).getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Deliverable addDeliverable(Long actionId, String fileName, Long ossId, String operator) {
        StageAction a = getById(actionId);
        // Round 8 / 后台安全审查 sibling-path-gate-parity：加项目状态门禁
        // 防止在已冻结 / 已删除 / DRAFT 之前的项目上挂载交付物（横向越权防护）
        assertProjectWritable(a.getProjectId());
        Deliverable d = Deliverable.builder()
            .actionId(actionId)
            .projectId(a.getProjectId())
            .fileName(fileName)
            .ossId(ossId)
            .uploadedAt(new Date())
            .build();
        deliverableMapper.insert(d);
        auditLogService.append(AuditLog.builder()
            .operatorName(operator).operatorRole("PM")
            .action("CREATE").entityType("DELIVERABLE").entityId(d.getId())
            // Round 8 / R8-P1-A：JSON 字符串统一走 AuditEventData.json（避免手工拼接被 DEF-6 requireJson 拦截时 fail-late）
            .afterData(AuditEventData.json("actionCode", a.getActionCode(), "file", fileName))
            .build());
        return d;
    }

    /**
     * PERF-03：批量实例化阶段动作，从 N 次 selectCount + N 次 insert 优化为
     * 1 次 selectList（取项目所有已有 action codes）+ 1 次 insertBatch（批量插入剩余）。
     * 69 动作 CONCEPT 阶段 = 138 IO → 2 IO，P99 下降 ~250ms → ~20ms。
     */
    @Transactional(rollbackFor = Exception.class)
    public int instantiate(Long projectId, Long stageId, String stage, IpdActor actor) {
        // Round 8 / 后台安全审查 sibling-path-gate-parity：加项目状态门禁
        // R212-②（看板卡 dbe1b6a7）：状态门禁之上叠加组归属断言（原方法无 actor 入参
        // ⇒ 任意内部 PM 可向任意项目批量物化 69 项阶段动作）。
        assertProjectWritableInGroup(projectId, actor);
        Set<String> existingCodes = stageActionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId))
            .stream().map(StageAction::getActionCode)
            .collect(java.util.stream.Collectors.toSet());
        List<StageAction> toCreate = ActionCatalog.byStage(stage).stream()
            .filter(def -> !existingCodes.contains(def.code()))
            .map(def -> StageAction.builder()
                .projectId(projectId)
                .stageId(stageId)
                .actionCode(def.code())
                .actionName(def.name())
                .ownerRole(def.ownerRole())
                .depth(def.depth())
                .status("NOT_STARTED")
                .isBlocking(def.blocking() ? "1" : "0")
                .isBioFeature(def.bioFeature() ? "1" : "0")
                .build())
            .toList();
        if (!toCreate.isEmpty()) {
            stageActionMapper.insertBatch(toCreate, 200);
        }
        return toCreate.size();
    }

    /**
     * 序列化动作状态快照为合法 JSON（供审计 before/after）。
     * 使用 {@link AuditEventData} 避免手工拼接在 actionCode/status 含引号时写出非法 JSON。
     */
    private static String statusSnapshot(StageAction a) {
        if (a.getActualDoneAt() == null) {
            return AuditEventData.json(
                "actionCode", a.getActionCode(),
                "status", a.getStatus(),
                "version", a.getVersion());
        }
        return AuditEventData.json(
            "actionCode", a.getActionCode(),
            "status", a.getStatus(),
            "version", a.getVersion(),
            "actualDoneAt", a.getActualDoneAt().getTime());
    }

    private static Long actorIdOf(String operator) {
        try { return Long.parseLong(operator); } catch (NumberFormatException e) { return null; }
    }

    /**
     * P1-2.2：暂停/归档项目只读——禁止动作状态迁移。
     *
     * <p>R212-②③ 改造：返回已加载的 {@link Project}，供 {@link #assertProjectWritableInGroup}
     * 复用同一次 DB 读（不新增查询次数）；既有调用方（transit / addDeliverable / 本方法）
     * 忽略返回值，判定语义完全不变。
     *
     * @param projectId 项目 ID
     * @return 项目实体；{@code projectId == null} 时返回 null（沿用既有「null 跳过状态门禁」口径）
     */
    private Project assertProjectWritable(Long projectId) {
        if (projectId == null) {
            return null;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        if ("SUSPENDED".equals(project.getStatus()) || "ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("暂停/归档项目禁止变更动作状态");
        }
        return project;
    }

    /**
     * R212-②③（看板卡 dbe1b6a7）：HTTP 批量物化 / C12 合规补挂入口的对象级归属守卫。
     *
     * <p>三段式（与 {@link IpdIdorGuard} 范式一致，任何一环不满足即 fail-closed）：
     * ① actor 必填（缺失 → UNAUTHORIZED 20001/401）；② 复用既有状态门禁
     * （项目不存在/软删/暂停归档 → ServiceException，行为不变）；
     * ③ 操作人组 == 项目主组（跨组 → FORBIDDEN 30001/403，SUPER_ADMIN 运维豁免）。
     *
     * <p>组断言排在状态门禁之后：跨组者无法借「暂停/归档」与「不存在」的文案差异探测
     * 他组项目状态；而存在性文案（ServiceException）本就对同/跨组一致，未新增泄露面。
     */
    private void assertProjectWritableInGroup(Long projectId, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        Project project = assertProjectWritable(projectId);
        IpdIdorGuard.assertSameGroupIpd(actor, project == null ? null : project.getMainGroupId());
    }

    /**
     * R219 卡④（ef20c06a）：逾期动作每日提醒链。
     *
     * <p>扫描 dueDate 已过且仍处开放态（NOT_STARTED/IN_PROGRESS/DELAYED）的动作（LIMIT 500 兜底），
     * 按 ownerRole 解析接收人后走 {@link NotificationService#publishDaily}：
     * dedupKey 含自然日（yyyyMMdd），同日重扫不重发、次日再提醒（AC-IPD-12 语义）。
     *
     * <p>接收人映射：MARKET_PM/RD_PM → 该项目对应在职成员（exitDate 为空）；
     * BOTH → 两类并集；GROUP_LEADER → 项目主产品组组长（product_groups.leader_person_id）。
     * 解析不到接收人的动作跳过计数，不抛错（调度器不因单条脏数据中断整批）。
     *
     * @return 实际发布的通知条数（含幂等命中既有行的重复调用，同日重跑结果稳定）
     */
    public int notifyOverdueActions() {
        if (notificationService == null || projectMemberMapper == null || productGroupMapper == null) {
            return 0;
        }
        Date now = new Date();
        List<StageAction> overdue = stageActionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                .isNotNull(StageAction::getDueDate)
                .lt(StageAction::getDueDate, now)
                .in(StageAction::getStatus, "NOT_STARTED", "IN_PROGRESS", "DELAYED")
                .last("LIMIT 500"));

        int sent = 0;
        Map<Long, Project> projectCache = new HashMap<>();
        Map<Long, List<ProjectMember>> memberCache = new HashMap<>();
        for (StageAction a : overdue) {
            Set<Long> receivers = resolveOverdueReceivers(a, projectCache, memberCache);
            if (receivers.isEmpty()) {
                continue;
            }
            String actionName = a.getActionName() == null ? a.getActionCode() : a.getActionName();
            String title = "动作逾期: " + actionName;
            String content = "项目 " + a.getProjectId() + " 的阶段动作「" + actionName
                + "」已逾期（截止 " + new java.text.SimpleDateFormat("yyyy-MM-dd").format(a.getDueDate()) + "），请尽快处理。";
            String actionUrl = a.getProjectId() == null ? null : "/projects/" + a.getProjectId();
            for (Long receiverId : receivers) {
                notificationService.publishDaily(receiverId, NotificationService.Types.ACTION_OVERDUE,
                    NotificationService.KIND_ACTION, "stage_action", a.getId(), title, content, actionUrl, now);
                sent++;
            }
        }
        return sent;
    }

    private Set<Long> resolveOverdueReceivers(StageAction a, Map<Long, Project> projectCache,
                                              Map<Long, List<ProjectMember>> memberCache) {
        Set<Long> receivers = new HashSet<>();
        String role = a.getOwnerRole();
        if (role == null || a.getProjectId() == null) {
            return receivers;
        }
        if ("GROUP_LEADER".equals(role)) {
            Project project = projectCache.computeIfAbsent(a.getProjectId(), pid -> projectMapper.selectById(pid));
            Long groupId = project == null ? null : project.getMainGroupId();
            if (groupId != null) {
                org.ruoyi.ipd.domain.ProductGroup group = productGroupMapper.selectById(groupId);
                if (group != null && group.getLeaderPersonId() != null) {
                    receivers.add(group.getLeaderPersonId());
                }
            }
            return receivers;
        }
        List<String> roles = "BOTH".equals(role) ? List.of("MARKET_PM", "RD_PM") : List.of(role);
        List<ProjectMember> members = memberCache.computeIfAbsent(a.getProjectId(), pid ->
            projectMemberMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, pid)
                    .in(ProjectMember::getRole, roles)
                    .isNull(ProjectMember::getExitDate)));
        for (ProjectMember m : members) {
            if (m.getPersonId() != null) {
                receivers.add(m.getPersonId());
            }
        }
        return receivers;
    }
}
