package org.ruoyi.ipd.service;

import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.ProductLineMember;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductLineMemberMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.dto.ProjectListItemView;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 项目服务（核心实体）
 * - 编码 PRJ-YYYY-NNN 自动生成（按年递增）
 * - 级别 S|A|B：差异化系数 S∈[1.5,2.0] / B∈[0.6,0.8]（v3 G1 特殊规则）/ A 不录（固定语义）
 * - S/B 系数理由必填（写审计）
 * - 状态机 DRAFT→TEAMING→ACTIVE→SUSPENDED/ARCHIVED（迁移表守卫）
 * - 阶段线性推进 CONCEPT→PLAN→DEV→VALID→LAUNCH→LIFECYCLE；Gate 硬门禁由 P1-5 GateEngine 接管
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService implements IProjectService {

    public static final Map<String, String> NEXT_STAGE = Map.of(
        "CONCEPT", "PLAN", "PLAN", "DEV", "DEV", "VALID", "VALID", "LAUNCH", "LAUNCH", "LIFECYCLE");
    public static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
        "DRAFT", Set.of("TEAMING", "ARCHIVED"),
        "TEAMING", Set.of("ACTIVE", "ARCHIVED"),
        "ACTIVE", Set.of("SUSPENDED", "ARCHIVED"),
        "SUSPENDED", Set.of("ACTIVE", "ARCHIVED"),
        "ARCHIVED", Set.of());

    private final ProjectMapper projectMapper;
    private final ProductMapper productMapper;
    private final StageActionMapper stageActionMapper;
    private final KpiRecordMapper kpiRecordMapper;
    /** R149 B2：PM 维度项目列表角色过滤（在职 MARKET_PM/RD_PM）所需 mapper。
     * 走 setter 模式，
     * nullable 兼容 P122AcceptanceTest / P131DatabaseIntegrationTest 等
     * 旧 10 参构造器入口（不破坏既有兄弟测试）。 */
    @Autowired(required = false)
    private ProjectMemberMapper projectMemberMapper;

    @Autowired(required = false)
    private ProductRetirementService retirementService;

    public void setProductRetirementService(ProductRetirementService retirementService) {
        this.retirementService = retirementService;
    }
    public void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }
    private final IAuditLogService auditLogService;
    private final GateEngine gateEngine;
    private final PlatformTransactionManager transactionManager;
    /** P2-6.2：阶段门禁 —— 跳阶前查询未闭环需求变更单（含 DRAFT / PENDING_SIGN）。 */
    private final RequirementChangeService requirementChangeService;

    /** 大阶段验收。未注入时旧测试仍走原来的阶段推进。生产由 Spring 注入。 */
    @Autowired(required = false)
    private StageAcceptanceService stageAcceptanceService;

    /**
     * 装配大阶段验收。
     *
     * @param stageAcceptanceService 验收服务
     */
    public void setStageAcceptanceService(StageAcceptanceService stageAcceptanceService) {
        this.stageAcceptanceService = stageAcceptanceService;
    }

    /** 奖金池比例（BR-INC-04）：目标销售额 × 5% × 差异化系数 */

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();
    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }
    private Date now() { return Date.from(clock.instant()); }

    private static final Set<String> TEMPLATE_TYPES = Set.of("HARDWARE", "SOFTWARE", "SOLUTION");
    /**
     * S/B 差异化系数区间（随「算钱」层下线，{@code BonusPoolService} 已删除，
     * 区间面值在此就地保留，避免校验静默放宽）。口径未变：S ∈ [1.5, 2.0]、B ∈ [0.6, 0.8]。
     * 待 owner 裁决 D-2：本字段是否随算钱层彻底移除（涉及 REST 契约 + G1 双签历史，见 /tmp/teardown-backend.md）。
     */
    private static final BigDecimal COEF_S_MIN = new BigDecimal("1.5");
    private static final BigDecimal COEF_S_MAX = new BigDecimal("2.0");
    private static final BigDecimal COEF_B_MIN = new BigDecimal("0.6");
    private static final BigDecimal COEF_B_MAX = new BigDecimal("0.8");

    private static final BigDecimal DEFAULT_COEF_S = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_COEF_A = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_COEF_B = new BigDecimal("0.8");
    /** P1-9.2：存量场景复核周期 14 天 */
    public static final int LEGACY_SCENARIO_DAYS = 14;
    /** P1-9.2：临界阈值（剩余 ≤ 3 天触发通知 MARKET_PM + PRODUCT_LEADER） */
    public static final int LEGACY_SCENARIO_CRITICAL_DAYS = 3;
    /** 编码冲突（TOCTOU：nextCode 与 insert 非同一原子临界区）最大重试次数 */
    private static final int CODE_CONFLICT_MAX_RETRY = 8;


    /**
     * D-1 批次（补遗 §5-2 二波接线）：状态机守卫，对齐 StageActionService 金样板。
     * setter 注入（@Autowired(required=false)）不扩构造签名——既有测试 new 不破；
     * 生产路径 Spring 必装配，缺失时迁移 fail-closed（防 state-machine-bypass）。
     */
    private StateMachineGuard stateMachineGuard;

    /** entityType 词表与其他机器一致：小写下划线。 */
    private static final String PROJECT_ENTITY_TYPE = "project";

    @Autowired(required = false)
    public void setStateMachineGuard(StateMachineGuard stateMachineGuard) {
        this.stateMachineGuard = stateMachineGuard;
    }

    private ProductLineMapper productLineMapper;
    private ProductLineMemberMapper productLineMemberMapper;

    /** 生产环境注入后，立项才校验产品线和在职成员。 */
    @Autowired(required = false)
    public void setProductLineMapper(ProductLineMapper productLineMapper) {
        this.productLineMapper = productLineMapper;
    }

    /** 生产环境注入后，非超管立项必须已是该产品线在职成员。 */
    @Autowired(required = false)
    public void setProductLineMemberMapper(ProductLineMemberMapper productLineMemberMapper) {
        this.productLineMemberMapper = productLineMemberMapper;
    }

    /** 立项写入成员时用来读取人员等级。旧测试不注入，插入仍不带锁定列。 */
    private PersonMapper personMapper;
    private ISystemConfigService systemConfigService;

    /**
     * 装配人员查询，供立项成员锁定津贴。
     *
     * @param personMapper 人员表
     */
    @Autowired(required = false)
    public void setPersonMapper(PersonMapper personMapper) {
        this.personMapper = personMapper;
    }

    /**
     * 装配系统配置，供立项成员读取 allowance.L1 到 L5。
     *
     * @param systemConfigService 配置服务
     */
    @Autowired(required = false)
    public void setSystemConfigService(ISystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 守卫 preCheck 包装（fail-closed：守卫 null = 装配缺失，拒绝迁移）。 */
    private void preCheckGuard(String fromState, String toState, String trigger) {
        if (stateMachineGuard == null) {
            throw new IpdBusinessException("状态机守卫未装配 entityType=" + PROJECT_ENTITY_TYPE
                + " from=" + fromState + " to=" + toState);
        }
        stateMachineGuard.preCheck(PROJECT_ENTITY_TYPE, fromState, toState, trigger);
    }

    /** 注册 postCommit 副作用（事务提交后触发；无守卫降级 no-op；无事务上下文直接执行）。 */
    private void registerPostCommit(String fromState, String toState, String trigger,
                                    Long operatorId, Long entityId) {
        if (stateMachineGuard == null) {
            return;
        }
        Date occurredAt = now();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stateMachineGuard.postCommit(PROJECT_ENTITY_TYPE, fromState, toState, trigger,
                        operatorId, entityId, occurredAt);
                }
            });
        } else {
            stateMachineGuard.postCommit(PROJECT_ENTITY_TYPE, fromState, toState, trigger,
                operatorId, entityId, occurredAt);
        }
    }

    /**
     * 创建项目（P1-2.1：四基准 + 模板/市场必填；系数默认/区间；状态强制 DRAFT）。
     * <p>2026-09-11 owner 拍板：主组可选——客户端未选时服务端权威自动归属
     * {@code fallbackMainGroupId}（页08 传操作人所在产品组，即 BR-ORG-01 字面语义），
     * 避免 null mainGroupId 污染下游 SEC-02 按组归属校验链（changeStatus /
     * updateBaselines / advanceStage / bindProject / CoefficientChange / Handover /
     * LaunchDateChange / RequirementStateMachine 全链 assertSameGroupIpd）。
     * <p>对 {@code uk_projects_code} 冲突做独立事务重试：READ_COMMITTED 下
     * synchronized(nextCode) 无法覆盖「取号→提交」窗口，HTTP 并发会撞号。
     *
     * @param project             客户端白名单字段已映射的实体
     * @param operatorId          操作人
     * @param fallbackMainGroupId 客户端未选主组时的缺省归属组
     * @return 落库后的项目（含编码与 CONCEPT/DRAFT）
     */
    public Project create(Project project, Long operatorId, Long fallbackMainGroupId) {
        return create(project, operatorId, fallbackMainGroupId, null, null);
    }

    /**
     * 创建立项并绑定双 PM。同一人不可兼市场 PM 与研发 PM。
     */
    public Project create(Project project, Long operatorId, Long fallbackMainGroupId,
                          Long marketPmId, Long rdPmId) {
        return create(project, operatorId, fallbackMainGroupId, marketPmId, rdPmId, null, null);
    }

    /**
     * 创建立项。新品只记产品线，产品行在批准开工时生成；迭代必须指向该线的在售产品。
     */
    public Project create(Project project, Long operatorId, Long fallbackMainGroupId,
                          Long marketPmId, Long rdPmId, Long productLineId, String operatorRole) {
        if (marketPmId != null && marketPmId.equals(rdPmId)) {
            throw new IpdBusinessException(ApiV1ErrorCode.ROLE_LOCKED, "同一人不可同时担任市场PM与研发PM");
        }
        validateBaselinesAndTemplate(project);
        if (project.getMainGroupId() == null) {
            project.setMainGroupId(fallbackMainGroupId);
        }
        if (project.getMainGroupId() == null) {
            throw new ServiceException("主组缺失：未选产品组且操作人无所属产品组，无法自动归属（BR-ORG-01）");
        }
        applyLevelCoefficientDefaults(project);
        validateLevelAndCoefficient(project);
        if (project.getProductId() == null && productLineId == null) {
            throw new ServiceException("新品立项必须选择产品线");
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (int attempt = 1; attempt <= CODE_CONFLICT_MAX_RETRY; attempt++) {
            try {
                return tx.execute(status -> insertNewProject(
                    project, operatorId, marketPmId, rdPmId, productLineId, operatorRole));
            } catch (DuplicateKeyException ex) {
                // R179-P0：不再静默——DuplicateKeyException 可能来自任何物理 uk（非只有
                // code）；无日志曾让「软删行占号」问题排查成本极高（靠 general_log 才定位）。
                log.warn("[IPD] project create DuplicateKey (attempt {}/{}): {}",
                    attempt, CODE_CONFLICT_MAX_RETRY, ex.getMessage());
                project.setId(null);
                project.setCode(null);
            }
        }
        throw new ServiceException("项目编码冲突，请重试");
    }

    /**
     * 单次事务内：校验产品、取号、插入。产品上的首个项目指针只在为空时回填。
     *
     * @param project    待插入项目（无 id/code）
     * @param operatorId 操作人
     * @return 落库项目
     */
    private Project insertNewProject(Project project, Long operatorId, Long marketPmId, Long rdPmId,
                                     Long productLineId, String operatorRole) {
        Product product = null;
        if (project.getProductId() != null) {
            product = productMapper.selectById(project.getProductId());
            if (product == null || "1".equals(product.getDelFlag())) {
                throw new ServiceException("归属产品不存在: " + project.getProductId());
            }
            if (Product.SRC_GUEST_OTHER.equals(product.getSource())) {
                throw new ServiceException("游客「其他」占位产品不可关联项目");
            }
        }
        Long lineId = product != null && product.getProductLineId() != null
            ? product.getProductLineId() : productLineId;
        enforceProductLine(product, productLineId, lineId, operatorId, operatorRole);
        if (product != null) {
            if ("1".equals(product.getRetirementLocked())
                || productMapper.isRetirementLockedForUpdate(product.getId())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品已退市并只读，不能新建关联项目");
            }
            if (retirementService != null && (retirementService.isOrderStopped(product.getId()) || retirementService.isProductionStopped(product.getId()))) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品订货或生产已截止，不能新建关联项目");
            }
        }
        project.setCode(nextCode());
        preCheckGuard(null, "PENDING_START", "create");
        project.setStatus("PENDING_START");
        project.setCreateBy(operatorId);
        if (isBlank(project.getSource())) {
            project.setSource("NEW");
        }
        project.setCreateTime(now());
        projectMapper.insert(project);
        if (lineId != null) {
            projectMapper.assignProductLine(project.getId(), lineId);
        }
        if (product != null && product.getProjectId() == null) {
            product.setProjectId(project.getId());
            productMapper.updateById(product);
        }
        bindProjectPm(project.getId(), marketPmId, "MARKET_PM");
        bindProjectPm(project.getId(), rdPmId, "RD_PM");
        if (projectMemberMapper != null && operatorId != null
            && !operatorId.equals(marketPmId) && !operatorId.equals(rdPmId)) {
            bindProjectPm(project.getId(), operatorId, "MEMBER");
        }
        audit(project.getId(), project.getName(), operatorId, "PROJECT_CREATE");
        registerPostCommit(null, "PENDING_START", "create", operatorId, project.getId());
        return project;
    }

    /** 生产环境注入产品线后才校验在售产品和在职成员。旧单测不注入，避免把两套创建路径写死。 */
    private void enforceProductLine(Product product, Long requestedLineId, Long lineId,
                                    Long operatorId, String operatorRole) {
        if (productLineMapper == null) {
            return;
        }
        if (product != null) {
            if (!Product.ST_ON_SALE.equals(product.getStatus())) {
                throw new ServiceException("迭代必须选择该产品线上的在售产品");
            }
            if (product.getProductLineId() == null) {
                throw new ServiceException("产品尚未归属产品线");
            }
            if (requestedLineId != null && !requestedLineId.equals(product.getProductLineId())) {
                throw new ServiceException("产品不属于所选产品线");
            }
        }
        if (lineId == null) {
            throw new ServiceException("新品立项必须选择产品线");
        }
        ProductLine line = productLineMapper.selectById(lineId);
        if (line == null || "1".equals(line.getDelFlag()) || !"ACTIVE".equals(line.getStatus())) {
            throw new ServiceException("产品线不存在或已停用");
        }
        if ("SUPER_ADMIN".equals(operatorRole)) {
            return;
        }
        if (productLineMemberMapper == null || operatorId == null) {
            throw new ServiceException("产品线成员校验不可用");
        }
        Long members = productLineMemberMapper.selectCount(new LambdaQueryWrapper<ProductLineMember>()
            .eq(ProductLineMember::getProductLineId, lineId)
            .eq(ProductLineMember::getPersonId, operatorId)
            .eq(ProductLineMember::getStatus, "ACTIVE"));
        if (members == null || members == 0) {
            throw new ServiceException("只有该产品线的在职成员可以立项");
        }
    }

    /**
     * 立项时写入一名在职成员。未指定人员则跳过。
     * 生产环境同时注入人员和配置后，按该人当前等级锁定津贴，满足 locked_level / locked_amount 非空。
     *
     * @param projectId 项目
     * @param personId 人员，空则跳过
     * @param role MARKET_PM、RD_PM 或 MEMBER
     */
    private void bindProjectPm(Long projectId, Long personId, String role) {
        if (personId == null) {
            return;
        }
        if (projectMemberMapper == null) {
            throw new ServiceException("项目成员写入不可用，无法绑定 " + role);
        }
        ProjectMember.ProjectMemberBuilder builder = ProjectMember.builder()
            .projectId(projectId)
            .personId(personId)
            .role(role)
            .memberType("PRIMARY")
            .joinDate(now())
            .bonusEligible("1");
        if (personMapper != null && systemConfigService != null) {
            Person person = personMapper.selectById(personId);
            if (person == null || person.getLevel() == null || person.getLevel().isBlank()) {
                throw new ServiceException("该人员等级未同步（L1-L5），无法锁定津贴基准");
            }
            int amount = systemConfigService.getIntValue("allowance." + person.getLevel(), -1);
            if (amount <= 0) {
                throw new ServiceException("津贴参数缺失: allowance." + person.getLevel());
            }
            builder.lockedLevel(person.getLevel()).lockedAmount(BigDecimal.valueOf(amount));
        }
        projectMemberMapper.insert(builder.build());
    }


    /**
     * 状态机迁移（非法迁移拒绝）；归档不可再迁出。
     * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免）。
     * ZK-IPD §二.10：归档后只读下沉 service 层——归档状态禁一切编辑类状态变更。
     *
     * @param projectId    项目 ID
     * @param target       目标状态
     * @param operatorId   操作人 ID（来自会话）
     * @param actorGroupId 操作人所属产品组（横向越权防护用）
     * @param actorRole    操作人角色（SUPER_ADMIN 豁免判断）
     */
    @Transactional(rollbackFor = Exception.class)
    public Project changeStatus(Long projectId, String target, Long operatorId,
                                Long actorGroupId, String actorRole) {
        Project project = require(projectId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            project.getMainGroupId());
        // ZK-IPD §二.10：归档后只读——禁所有迁出（即使变更到 SUSPENDED/ACTIVE 也拒）
        if ("ARCHIVED".equals(project.getStatus()) && !"ARCHIVED".equals(target)) {
            throw new ServiceException("项目已归档（ZK-IPD §二.10），资料只读，禁止迁出");
        }
        Set<String> allowed = STATUS_TRANSITIONS.getOrDefault(project.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new ServiceException("状态机非法迁移: " + project.getStatus() + " → " + target);
        }
        // D-1 接线：changeStatus 迁移守卫（白名单检查后、setStatus 前；8 条边 trigger=changeStatus）
        String guardFrom = project.getStatus();
        preCheckGuard(guardFrom, target, "changeStatus");
        project.setStatus(target);
        projectMapper.updateById(project);
        audit(projectId, project.getName(), operatorId, "PROJECT_STATUS_" + target);
        registerPostCommit(guardFrom, target, "changeStatus", operatorId, projectId);
        return project;
    }

    /**
     * P1-2.2：DRAFT 期内可改四基准；立项后锁定。
     * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免）
     *                  + before/after 审计（4 个基准字段值变化可追溯）。
     *
     * @param projectId    项目 ID
     * @param patch        含四基准字段的补丁
     * @param operatorId   操作人 ID（来自会话）
     * @param actorGroupId 操作人所属产品组
     * @param actorRole    操作人角色
     * @return 更新后项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project updateBaselines(Long projectId, Project patch, Long operatorId,
                                   Long actorGroupId, String actorRole) {
        Project project = require(projectId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            project.getMainGroupId());
        // ZK-IPD §二.10：归档后只读——禁四基准修改
        if ("ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("项目已归档（ZK-IPD §二.10），资料只读，禁止修改四基准");
        }
        if (!"DRAFT".equals(project.getStatus())) {
            throw new ServiceException("四基准在立项后锁定，不可直接修改（P1-2.2）");
        }
        // R8X-CONT-1 P0-1：审计 before/after 镜像（4 个基准字段）
        String before = AuditEventData.json(
            "targetSalesAmount", project.getTargetSalesAmount(),
            "targetChannelCount", project.getTargetChannelCount(),
            "targetNps", project.getTargetNps(),
            "targetSceneCount", project.getTargetSceneCount());
        if (patch.getTargetSalesAmount() != null) {
            project.setTargetSalesAmount(patch.getTargetSalesAmount());
        }
        if (patch.getTargetChannelCount() != null) {
            project.setTargetChannelCount(patch.getTargetChannelCount());
        }
        if (patch.getTargetNps() != null) {
            project.setTargetNps(patch.getTargetNps());
        }
        if (patch.getTargetSceneCount() != null) {
            project.setTargetSceneCount(patch.getTargetSceneCount());
        }
        validateBaselinesAndTemplate(project);
        String after = AuditEventData.json(
            "targetSalesAmount", project.getTargetSalesAmount(),
            "targetChannelCount", project.getTargetChannelCount(),
            "targetNps", project.getTargetNps(),
            "targetSceneCount", project.getTargetSceneCount());
        projectMapper.updateById(project);
        auditBaselines(projectId, project.getName(), operatorId, before, after);
        return project;
    }

    /**
     * 阶段推进：门禁校验（BR-IPD-06，P1-5 GateEngine 接管）+ LAUNCH 前置上市日期（BR-IPD-08）。
     * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免）
     *                  + 审计含 prior + new currentStage。
     *
     * <p>P2-6.2 强化：跳阶前先查需求变更单（{@link RequirementChangeService#hasOpenChange}），
     * 存在未闭环变更单（DRAFT 或 PENDING_SIGN）⇒ 拒绝推进，AC-GATE-11 跳阶拒绝语义。
     * 拒绝路径写 STAGE_GUARD_BLOCKED 审计（before/after 镜像 + operatorId + reason），
     * 不抛 GATE_NOT_PASSED 而抛 STATE_CONFLICT 区分「未闭环变更」与「Gate 要素不齐」。
     */
    @Transactional(rollbackFor = Exception.class)
    public Project advanceStage(Long projectId, Long operatorId, Long actorGroupId, String actorRole) {
        Project project = require(projectId);
        if (projectMemberMapper == null && !"SUPER_ADMIN".equals(actorRole)) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN, "非项目成员，无权访问");
        }
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            new IpdActor(operatorId, null, actorRole, actorGroupId), projectId,
            projectMemberMapper, projectMapper);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            project.getMainGroupId());
        // ZK-IPD §二.10：归档后只读——禁阶段推进
        if ("ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("项目已归档（ZK-IPD §二.10），资料只读，禁止推进阶段");
        }
        if ("SUSPENDED".equals(project.getStatus()) || "ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("暂停/归档项目禁止推进阶段");
        }
        // P2-6.2：未闭环变更单门禁（任一 DRAFT / PENDING_SIGN 存在 ⇒ 拒绝）
        // 顺序先于 NEXT_STAGE/gateEngine —— 即使是「最终阶段 LIFECYCLE」也要先审计/拒绝，
        // 让审计链记录「操作人试图越界跳阶」便于事后追责。
        // null-safe：单测环境下部分用例（历史 advanceStage 测试）不挂载 RequirementChangeService
        // mock 仍可继续工作（跳过本门禁），生产环境由 Spring DI 注入必有非 null。
        if (requirementChangeService != null && requirementChangeService.hasOpenChange(projectId)) {
            int openCount = requirementChangeService.countOpenByProject(projectId);
            String prior = project.getCurrentStage();
            String nextAttempt = NEXT_STAGE.get(prior);
            auditStageGuardBlocked(projectId, project.getName(), operatorId,
                prior, nextAttempt, openCount);
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT,
                "存在未闭环需求变更单（" + openCount + " 张），需先关闭（P2-6.2 阶段门禁）");
        }
        String prior = project.getCurrentStage();
        String next = NEXT_STAGE.get(prior);
        if (next == null) {
            throw new ServiceException("已处于最终阶段 LIFECYCLE");
        }
        if (stageAcceptanceService != null) {
            stageAcceptanceService.assertBigStageApprovable(project, operatorId, actorRole);
        }
        gateEngine.check(project, prior);
        if ("LAUNCH".equals(next) && project.getLaunchDate() == null) {
            throw new ServiceException("进入 LAUNCH 前必须录入上市日期（后置指标起算原点）");
        }
        project.setCurrentStage(next);
        projectMapper.updateById(project);
        if (stageAcceptanceService != null) {
            stageAcceptanceService.completeBigStage(projectId, prior);
        }
        auditStage(projectId, project.getName(), operatorId, prior, next);
        return project;
    }

    public Project getById(Long id) {
        return projectMapper.selectById(id);
    }

    /**
     * AC-AUTH-09（看板卡 96b7b157，R218 lane1 缺陷#1）：项目详情可见性谓词，fail-closed，
     * 与 {@link #listWithScenario} 同口径收口「同组非成员 PM 越权读他人项目全量详情」的 IDOR 读腿
     * （写腿已有互斥/归属校验，读腿此前完全裸奔）。
     *
     * <p>放行序（任一即通过）：
     * <ol>
     *   <li>SUPER_ADMIN：全部；</li>
     *   <li>GROUP_LEADER 且 {@code actor.groupId == project.mainGroupId}：本组（AC-AUTH-10 组长可看本组）；</li>
     *   <li>该项目在职成员（{@code project_members} 未退出未删）：本人负责/参与的项目。</li>
     * </ol>
     * 其余一律 FORBIDDEN。项目不存在与无权限统一文案，不泄漏存在性（对齐 {@code IpdIdorGuard}）。
     *
     * @param id    项目 ID
     * @param actor 服务端会话身份
     * @return 可见时返回项目实体
     * @throws org.ruoyi.ipd.common.IpdBusinessException FORBIDDEN(30001) 当不可见 / 项目不存在
     */
    public Project getVisibleById(Long id, IpdActor actor) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
        }
        if (isUnstarted(project)) {
            if (canSeeUnstarted(project, actor)) {
                return project;
            }
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
        }
        String role = actor == null ? null : actor.role();
        if ("SUPER_ADMIN".equals(role)) {
            return project;
        }
        if (isProductLineLeader(project, actor)) {
            return project;
        }
        if ("GROUP_LEADER".equals(role) && actor.groupId() != null
                && actor.groupId().equals(project.getMainGroupId())) {
            return project;
        }
        // 在职成员维度：projectMemberMapper 为可选注入（旧构造器兼容），缺失时非超管/组长一律拒（fail-closed）
        if (projectMemberMapper != null && actor != null && actor.id() != null) {
            Long cnt = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id)
                .eq(ProjectMember::getPersonId, actor.id())
                .isNull(ProjectMember::getExitDate)
                .eq(ProjectMember::getDelFlag, "0"));
            if (cnt != null && cnt > 0) {
                return project;
            }
        }
        throw new org.ruoyi.ipd.common.IpdBusinessException(
            org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
    }

    public List<Project> list(String keyword) {
        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>().eq(Project::getDelFlag, "0");
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Project::getName, keyword);
        }
        // PERF-P1-1：硬上限 1000 防 ≥10k 项目 OOM（IPD 单企业 ≥10k 项目场景）
        return projectMapper.selectList(qw.orderByDesc(Project::getId).last("LIMIT 1000"));
    }

    /**
     * P1-9.2：项目列表（含 scenarioDaysRemaining 派生字段 + 临界告警标记）。
     * <p>R149 B2 升级：新增 {@link #listWithScenario(String, IpdActor)} 按角色硬过滤版本；
     * 本单参签名 <b>保留向后兼容</b>（P192AcceptanceTest 等历史测试入口），
     * 内部委派给双参版本并传 {@code actor=null}（等价于全量，不做角色过滤）。
     *
     * @param keyword 项目名关键字
     * @return 列表视图（含派生字段；全量）
     */
    public List<ProjectListItemView> listWithScenario(String keyword) {
        return listWithScenario(keyword, null);
    }

    /**
     * R149 B2：项目列表（按角色硬过滤 + 派生字段）。
     *
     * <p>角色过滤矩阵（前后端对齐）：
     * <ul>
     *   <li>SUPER_ADMIN：全量（无过滤）</li>
     *   <li>GROUP_LEADER：本组（{@code projects.main_group_id = actor.groupId()}）</li>
     *   <li>MARKET_PM / RD_PM：本人负责的（{@code project_members.person_id = actor.id()
     *       AND role IN (MARKET_PM, RD_PM) AND exit_date IS NULL AND del_flag='0'}）</li>
     *   <li>其他角色 / null actor：空列表（安全默认，避免泄漏全量）</li>
     * </ul>
     *
     * <p>提示横幅由前端维持（前端不改）；本方法只负责<b>权威服务端硬过滤</b>，
     * 即使前端绕过横幅直接调接口也只能取到授权范围内的项目。
     *
     * @param keyword 项目名关键字（可空）
     * @param actor   当前操作人；null ⇒ 等价于全量（向后兼容）
     * @return 列表视图（含 scenarioDaysRemaining / critical 派生字段）
     */
    public List<ProjectListItemView> listWithScenario(String keyword, IpdActor actor) {
        List<Project> projects = listProjectsForActor(keyword, actor);
        if (projects.isEmpty()) {
            return List.of();
        }
        // 批量查 stage_action / kpi_record 的最新 update_time，按 projectId 分组
        Map<String, Date> stageActivity = batchLastStageActivity(projects);
        Map<String, Date> kpiActivity = batchLastKpiActivity(projects);
        List<ProjectListItemView> out = new java.util.ArrayList<>(projects.size());
        for (Project p : projects) {
            Date lastActivity = computeLastActivity(p, stageActivity, kpiActivity);
            Integer remaining = null;
            Boolean critical = null;
            if ("LEGACY".equals(p.getSource()) && "IN_PROGRESS".equals(p.getCatchupStatus())) {
                if (lastActivity != null) {
                    long diffDays = TimeUnit.MILLISECONDS.toDays(now().getTime() - lastActivity.getTime());
                    remaining = (int) Math.max(0, LEGACY_SCENARIO_DAYS - diffDays);
                    critical = remaining <= LEGACY_SCENARIO_CRITICAL_DAYS;
                } else {
                    // 无活动日视为第一天起算，剩余 14
                    remaining = LEGACY_SCENARIO_DAYS;
                    critical = false;
                }
            }
            out.add(new ProjectListItemView(p, lastActivity, remaining, critical));
        }
        return out;
    }

    /**
     * R149 B2：按 actor 角色从 projects 取数（不过滤 delFlag=0 已统一在外层 where）。
     * SUPER_ADMIN 走全量 {@link #list}；GROUP_LEADER 按 {@code main_group_id}；
     * PM 按 {@code project_members} 在职 role 匹配。
     */
    private List<Project> listProjectsForActor(String keyword, IpdActor actor) {
        if (actor == null) {
            return list(keyword);
        }
        String role = actor.role();
        if ("SUPER_ADMIN".equals(role)) {
            return list(keyword);
        }
        List<Project> rows;
        if ("GROUP_LEADER".equals(role)) {
            rows = listByGroup(keyword, actor.groupId());
        } else if ("MARKET_PM".equals(role) || "RD_PM".equals(role)) {
            rows = listByActorPm(keyword, actor.id());
        } else {
            rows = List.of();
        }
        return filterListed(mergeLedProjects(rows, actor), actor);
    }

    private boolean isUnstarted(Project project) {
        String status = project.getStatus();
        return "PENDING_START".equals(status) || "START_REJECTED".equals(status);
    }

    /** 未开工项目只给创建人、该线负责人和超管。组织组长和普通成员看不到。 */
    private boolean canSeeUnstarted(Project project, IpdActor actor) {
        if (actor == null) {
            return false;
        }
        if ("SUPER_ADMIN".equals(actor.role())) {
            return true;
        }
        if (actor.id() != null && actor.id().equals(project.getCreateBy())) {
            return true;
        }
        return isProductLineLeader(project, actor);
    }

    private boolean isProductLineLeader(Project project, IpdActor actor) {
        if (productLineMapper == null || actor == null || actor.id() == null || project.getId() == null) {
            return false;
        }
        Long lineId = projectMapper.findProductLineId(project.getId());
        if (lineId == null && project.getProductId() != null) {
            Product product = productMapper.selectById(project.getProductId());
            lineId = product == null ? null : product.getProductLineId();
        }
        if (lineId == null) {
            return false;
        }
        ProductLine line = productLineMapper.selectById(lineId);
        return line != null && actor.id().equals(line.getLeaderPersonId()) && !"1".equals(line.getDelFlag());
    }

    private List<Project> mergeLedProjects(List<Project> rows, IpdActor actor) {
        if (productLineMapper == null || actor == null || actor.id() == null) {
            return rows;
        }
        List<ProductLine> led = productLineMapper.selectList(new LambdaQueryWrapper<ProductLine>()
            .eq(ProductLine::getLeaderPersonId, actor.id())
            .eq(ProductLine::getStatus, "ACTIVE")
            .eq(ProductLine::getDelFlag, "0"));
        if (led.isEmpty()) {
            return rows;
        }
        java.util.LinkedHashMap<Long, Project> merged = new java.util.LinkedHashMap<>();
        for (Project row : rows) {
            if (row.getId() != null) {
                merged.put(row.getId(), row);
            }
        }
        for (ProductLine line : led) {
            List<Long> ids = projectMapper.findIdsByProductLine(line.getId());
            if (ids != null && !ids.isEmpty()) {
                for (Project row : projectMapper.selectBatchIds(ids)) {
                    if (row.getId() != null) {
                        merged.put(row.getId(), row);
                    }
                }
            }
            List<Long> productIds = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getProductLineId, line.getId())
                .eq(Product::getDelFlag, "0")).stream().map(Product::getId).toList();
            if (!productIds.isEmpty()) {
                for (Project row : projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .in(Project::getProductId, productIds).eq(Project::getDelFlag, "0"))) {
                    if (row.getId() != null) {
                        merged.put(row.getId(), row);
                    }
                }
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    private List<Project> filterListed(List<Project> rows, IpdActor actor) {
        if (actor == null || "SUPER_ADMIN".equals(actor.role())) {
            return rows;
        }
        return rows.stream().filter(project -> !isUnstarted(project) || canSeeUnstarted(project, actor)).toList();
    }

    /** R149 B2：组长维度 —— 按 {@code projects.main_group_id} 过滤。null groupId ⇒ 空列表。 */
    private List<Project> listByGroup(String keyword, Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>()
            .eq(Project::getDelFlag, "0")
            .eq(Project::getMainGroupId, groupId);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Project::getName, keyword);
        }
        // PERF-P1-1：硬上限 1000 防 ≥10k 项目 OOM
        return projectMapper.selectList(qw.orderByDesc(Project::getId).last("LIMIT 1000"));
    }

    /**
     * R149 B2：双 PM 维度 —— 取 actor 在职 MARKET_PM/RD_PM 角色对应的项目 ID 集合，
     * 再按 ID 列表 + 关键字 + delFlag=0 过滤。actorId null 或 无在职项目 ⇒ 空列表。
     */
    private List<Project> listByActorPm(String keyword, Long actorId) {
        if (actorId == null) {
            return List.of();
        }
        List<Long> pmProjectIds = projectMemberMapper.selectList(
            new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getPersonId, actorId)
                .in(ProjectMember::getRole, List.of("MARKET_PM", "RD_PM"))
                .isNull(ProjectMember::getExitDate)
                .eq(ProjectMember::getDelFlag, "0")
                .select(ProjectMember::getProjectId))
            .stream()
            .map(ProjectMember::getProjectId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (pmProjectIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<Project>()
            .eq(Project::getDelFlag, "0")
            .in(Project::getId, pmProjectIds);
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Project::getName, keyword);
        }
        // PERF-P1-1：硬上限 1000 防 ≥10k 项目 OOM
        return projectMapper.selectList(qw.orderByDesc(Project::getId).last("LIMIT 1000"));
    }

    /**
     * P1-9.2：批量查 stage_action 的最近 update_time（按 projectId 分组）。
     * N+1 防护：单 SQL IN (...) + ORDER BY update_time DESC + GROUP BY。
     */
    private Map<String, Date> batchLastStageActivity(List<Project> projects) {
        List<Long> ids = projects.stream().map(Project::getId).toList();
        if (ids.isEmpty()) return Map.of();
        // MyBatis-Plus 无法直接 group by + max；走自定义 mapper 方法（Mapper 提供 maxUpdateTimeByProjectIds）
        // 兜底实现：selectList 全量后内存聚合（适合 ≤ 1000 项目场景）
        List<StageAction> actions = stageActionMapper.selectList(new LambdaQueryWrapper<StageAction>()
            .in(StageAction::getProjectId, ids)
            .isNotNull(StageAction::getUpdateTime)
            .orderByDesc(StageAction::getUpdateTime)
            .select(StageAction::getProjectId, StageAction::getUpdateTime));
        Map<String, Date> map = new HashMap<>();
        for (StageAction a : actions) {
            if (a.getProjectId() != null && a.getUpdateTime() != null) {
                String k = String.valueOf(a.getProjectId());
                Date cur = map.get(k);
                if (cur == null || a.getUpdateTime().after(cur)) {
                    map.put(k, a.getUpdateTime());
                }
            }
        }
        return map;
    }

    private Map<String, Date> batchLastKpiActivity(List<Project> projects) {
        List<Long> ids = projects.stream().map(Project::getId).toList();
        if (ids.isEmpty()) return Map.of();
        List<KpiRecord> records = kpiRecordMapper.selectList(new LambdaQueryWrapper<KpiRecord>()
            .in(KpiRecord::getProjectId, ids)
            .isNotNull(KpiRecord::getUpdateTime)
            .orderByDesc(KpiRecord::getUpdateTime)
            .select(KpiRecord::getProjectId, KpiRecord::getUpdateTime));
        Map<String, Date> map = new HashMap<>();
        for (KpiRecord r : records) {
            if (r.getProjectId() != null && r.getUpdateTime() != null) {
                String k = String.valueOf(r.getProjectId());
                Date cur = map.get(k);
                if (cur == null || r.getUpdateTime().after(cur)) {
                    map.put(k, r.getUpdateTime());
                }
            }
        }
        return map;
    }

    private Date computeLastActivity(Project p, Map<String, Date> stageMap, Map<String, Date> kpiMap) {
        String key = String.valueOf(p.getId());
        Date s = stageMap.get(key);
        Date k = kpiMap.get(key);
        Date max = p.getLastActivityAt();
        if (s != null && (max == null || s.after(max))) max = s;
        if (k != null && (max == null || k.after(max))) max = k;
        return max;
    }

    /**
     * P1-9.2：扫描临界（remaining ≤ 3）的 LEGACY 项目，通知 MARKET_PM + PRODUCT_LEADER。
     * 由 CronTaskService / 调度任务调用（每天 02:00）。
     */
    public int scanLegacyCriticalProjects() {
        List<Project> legacyInProgress = projectMapper.selectList(new LambdaQueryWrapper<Project>()
            .eq(Project::getSource, "LEGACY")
            .eq(Project::getCatchupStatus, "IN_PROGRESS")
            .eq(Project::getDelFlag, "0"));
        if (legacyInProgress.isEmpty()) return 0;
        int notified = 0;
        for (Project p : legacyInProgress) {
            Date lastActivity = computeLastActivity(p,
                batchLastStageActivity(List.of(p)),
                batchLastKpiActivity(List.of(p)));
            if (lastActivity == null) continue;
            long diffDays = TimeUnit.MILLISECONDS.toDays(now().getTime() - lastActivity.getTime());
            int remaining = (int) Math.max(0, LEGACY_SCENARIO_DAYS - diffDays);
            if (remaining > LEGACY_SCENARIO_CRITICAL_DAYS) continue;
            // 通知 MARKET_PM（项目主组 GROUP_LEADER 视作 PRODUCT_LEADER 角色；
            // 此处简化为发到 mainGroupId 对应 GROUP_LEADER 与项目主负责人）
            if (p.getMainGroupId() != null) {
                // R239 消重：委托 append(Long,...) 共享重载；createTime 由注入 Clock（业务时间）回归真实写入时刻（审计语义更准，生产墙钟等价，无测试断言审计时间，行为可证等价）
                auditLogService.append(0L, "LEGACY_SCENARIO_CRITICAL", "projects", p.getId(),
                    "LEGACY 场景复核临界：" + p.getName() + " 剩余 " + remaining + " 天（lastActivityAt=" + lastActivity + "）");
                notified++;
            }
        }
        return notified;
    }

    /**
     * PRJ-YYYY-NNN：取当年最大序号 +1。
     * <p>PERF-01 / RISK-01：双层保护——
     * <ul>
     *   <li>{@code @Lock4j}：Redisson 跨 JVM（生产多实例经 Spring 代理生效）</li>
     *   <li>{@code synchronized}：同 JVM 兜底（单测 {@code new ProjectService()} 无 AOP 时仍原子）</li>
     *   <li>DB：{@code uk_projects_code(code)} UNIQUE KEY 最终兜底</li>
     * </ul>
     */
    @Lock4j(keys = {"'ipd:project:code'"}, expire = 5000, acquireTimeout = 3000)
    public synchronized String nextCode() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String prefix = "PRJ-" + year + "-";
        // R179-P0（2026-09-22）：改原生 SQL 取号（selectMaxCodeSeqByYear，绕过 @TableLogic）。
        // 根因：MP selectList 自动追加 del_flag='0'，软删行不可见，序号回退到已软删但
        // 物理 uk_projects_code 仍占用的编码——实测软删 PRJ-2026-033 后 nextCode 恒生成
        // 033，INSERT 撞物理 uk，8 次重试确定性全败，创建项目 API 整体不可用
        // （HTTP 400「项目编码冲突」，P131 集成测试 2 失败同源）。
        Integer max = projectMapper.selectMaxCodeSeqByYear(year);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    /**
     * P1-2.1：模板类型 / 目标市场 / 四基准值必填与范围。
     * 主组校验不在其中：2026-09-11 起主组可选，由 {@link #create} 权威填充（BR-ORG-01）。
     *
     * @param project 待校验项目
     */
    private void validateBaselinesAndTemplate(Project project) {
        if (isBlank(project.getName())) {
            throw new ServiceException("项目名称必填");
        }
        if (isBlank(project.getTemplateType()) || !TEMPLATE_TYPES.contains(project.getTemplateType())) {
            throw new ServiceException("模板类型非法（允许 HARDWARE|SOFTWARE|SOLUTION）");
        }
        if (isBlank(project.getTargetMarkets())) {
            throw new ServiceException("目标市场必填（驱动认证清单 M1）");
        }
        if (project.getTargetSalesAmount() == null
            || project.getTargetSalesAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException("立项目标销售额必填且须大于 0（四基准/奖金池基数）");
        }
        if (project.getTargetChannelCount() == null || project.getTargetChannelCount() < 0) {
            throw new ServiceException("立项目标渠道商数必填且不可为负");
        }
        if (project.getTargetNps() == null) {
            throw new ServiceException("立项 NPS 目标必填（四基准）");
        }
        if (project.getTargetSceneCount() == null || project.getTargetSceneCount() < 0) {
            throw new ServiceException("立项目标场景数必填且不可为负");
        }
    }

    /**
     * AC-INC-12/13/14：未录入系数时写入级别默认值（S=1.5 / A=1.0 / B=0.8）。
     *
     * @param project 待填默认系数的项目
     */
    private void applyLevelCoefficientDefaults(Project project) {
        String level = project.getLevel();
        if (project.getLevelCoefficient() != null) {
            return;
        }
        switch (level == null ? "" : level) {
            case "S" -> project.setLevelCoefficient(DEFAULT_COEF_S);
            case "A" -> project.setLevelCoefficient(DEFAULT_COEF_A);
            case "B" -> project.setLevelCoefficient(DEFAULT_COEF_B);
            default -> { /* 非法级别留给 validateLevelAndCoefficient */ }
        }
    }

    private void validateLevelAndCoefficient(Project project) {
        String level = project.getLevel();
        if (!"S".equals(level) && !"A".equals(level) && !"B".equals(level)) {
            throw new ServiceException("项目级别非法: " + level + "（允许 S|A|B）");
        }
        BigDecimal coefficient = project.getLevelCoefficient();
        switch (level) {
            case "S" -> requireCoefficient("S", coefficient, COEF_S_MIN, COEF_S_MAX);
            case "B" -> requireCoefficient("B", coefficient, COEF_B_MIN, COEF_B_MAX);
            case "A" -> {
                // AC-INC-15b：A 固定 1.0；客户端显式录入非 1.0 拒绝；服务端默认已写 1.0
                if (coefficient == null || coefficient.compareTo(DEFAULT_COEF_A) != 0) {
                    throw new ServiceException("A 级为固定 1.0 不可改");
                }
            }
            default -> throw new ServiceException("项目级别非法");
        }
        // AC-INC-15c：立项仅落默认档；非默认须走双PM提议+产品组长确认
        if ("S".equals(level) && coefficient.compareTo(DEFAULT_COEF_S) != 0) {
            throw new ServiceException("S/B 非默认系数须走双PM提议+产品组长确认（AC-INC-15c）");
        }
        if ("B".equals(level) && coefficient.compareTo(DEFAULT_COEF_B) != 0) {
            throw new ServiceException("S/B 非默认系数须走双PM提议+产品组长确认（AC-INC-15c）");
        }
    }

    /**
     * 校验 S/B 系数区间（AC-INC-15 / AC-INC-15c 共用文案）。
     *
     * @param level       S|B
     * @param coefficient 提议系数
     */
    public static void validateCoefficientRange(String level, BigDecimal coefficient) {
        switch (level == null ? "" : level) {
            case "S" -> requireCoefficient("S", coefficient, COEF_S_MIN, COEF_S_MAX);
            case "B" -> requireCoefficient("B", coefficient, COEF_B_MIN, COEF_B_MAX);
            default -> throw new ServiceException("仅 S/B 级可校验差异化系数区间");
        }
    }

    /** 区间边界改 BigDecimal，文案按实际边界拼装。 */
    private static void requireCoefficient(String level, BigDecimal coefficient, BigDecimal min, BigDecimal max) {
        if (coefficient == null) {
            throw new ServiceException("该级别差异化系数必填");
        }
        if (coefficient.compareTo(min) < 0 || coefficient.compareTo(max) > 0) {
            throw new ServiceException(level + " 级系数区间为 " + min.toPlainString() + "–" + max.toPlainString());
        }
    }

    private Project require(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + id);
        }
        return project;
    }

    private void audit(Long id, String name, Long operatorId, String action) {
        // R239 消重：委托 append(Long,...) 共享重载；createTime 由注入 Clock（业务时间）回归
        // 真实写入时刻（审计语义更准，生产墙钟等价，无测试断言审计时间，行为可证等价）。
        auditLogService.append(operatorId, action, "projects", id, name);
    }

    /** R8X-CONT-1 P0-1：四基准 before/after 审计（PATCH 触发变更时镜像新旧值） */
    private void auditBaselines(Long id, String name, Long operatorId, String before, String after) {
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("PROJECT_BASELINE_UPDATE")
            .entityType("projects").entityId(id).reason(name)
            .beforeData(before).afterData(after)
            .createTime(now()).build());
    }

    /** R8X-CONT-1 P0-1：阶段推进审计（含 prior + new currentStage） */
    private void auditStage(Long id, String name, Long operatorId, String prior, String next) {
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("PROJECT_STAGE_" + next)
            .entityType("projects").entityId(id).reason(name)
            .beforeData(AuditEventData.json("currentStage", prior))
            .afterData(AuditEventData.json("currentStage", next))
            .createTime(now()).build());
    }

    /**
     * P2-6.2：阶段门禁拒绝审计（未闭环需求变更单导致跳阶被拒）。
     *
     * <p>审计链需在 {@code IpdBusinessException} 抛出之前落库 —— 由于 advanceStage 整体被
     * {@code @Transactional} 包裹，audit 写入走 {@code IAuditLogService} 的 REQUIRES_NEW 通道
     * （基线约定），主事务回滚不影响审计可见性。
     *
     * <p>字段约定：
     * <ul>
     *   <li>action = {@code STAGE_GUARD_BLOCKED}（区别于 PROJECT_STAGE_* 成功审计）</li>
     *   <li>reason = 项目名（与 audit/auditStage 对齐，便于查询）</li>
     *   <li>beforeData = { currentStage, attemptedNext, openChangeCount } 镜像</li>
     *   <li>afterData = null（拒绝路径无新值写入）</li>
     * </ul>
     */
    private void auditStageGuardBlocked(Long id, String name, Long operatorId,
                                        String prior, String attemptedNext, int openCount) {
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("STAGE_GUARD_BLOCKED")
            .entityType("projects").entityId(id).reason(name)
            .beforeData(AuditEventData.json(
                "currentStage", prior,
                "attemptedNext", attemptedNext,
                "openChangeCount", openCount))
            .createTime(now()).build());
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}