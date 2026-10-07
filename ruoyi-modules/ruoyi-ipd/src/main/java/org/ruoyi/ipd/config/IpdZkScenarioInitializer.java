package org.ruoyi.ipd.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.service.ISystemConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * ZK-IPD 场景种子（G4 数据门；对齐原型 scenario.mjs + 前端 workbench 原硬编码 demo 数据）。
 *
 * <p>内容（幂等，按 employee_no/group_name/code/project+code 判存在即跳过）：
 * <ul>
 *   <li>6 产品组（市场组×3 + 研发线×3）+ 13 人（傅志谦超管 + 6 组长 + 6 PM），对齐原型 PEOPLE；</li>
 *   <li>3 个已完结项目（ENT-AC-100 / ZK-IAT-ATT / VIS-RD-100，原型 PROJECTS）：ARCHIVED + 六阶段 DONE + DONE 动作；</li>
 *   <li>2 个运行态项目（如门禁测试 / 熵基互联+智能锁联动）：ACTIVE + 未完成动作（含超期 IN_PROGRESS/DELAYED、
 *       临期 NOT_STARTED），供工作台待办/临期超期/责任过滤真实测试；</li>
 *   <li>相对日期（now±N 天）保证超期/临期场景不随时间失效。</li>
 * </ul>
 *
 * <p>密码：BCrypt（cost 10）运行时生成，来源 {@code ipd.security.initial-password}（SEC-HIGH-2，源码无字面量），
 * 首登强制改密 must_change_pwd=1。
 *
 * <p><b>装配范围（2026-10-07 改）</b>：过去挂 {@code @Profile("dev")}，生产装完是空库；
 * 现全环境装配，由 {@code ipd.seed.zk-scenario.enabled}（默认 true）控制。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class IpdZkScenarioInitializer implements ApplicationRunner {

    /**
     * ZK-IPD 基线场景是否随应用启动自动灌入。
     *
     * <p><b>2026-10-07 owner 要求「确保后续上线安装自动初始化好」</b>：过去本类挂
     * {@code @Profile("dev")}，所以生产装完是<b>空库</b>——没有人员、没有产品组、
     * 没有项目，登录进去什么都不是。改为默认开启，落到可配置开关上：
     * 需要纯空库的部署可显式关掉。
     */
    @Value("${ipd.seed.zk-scenario.enabled:true}")
    private boolean enabled;

    @Value("${ipd.security.initial-password}")
    private String runtimeInitialPwd;

    private final ProductGroupMapper productGroupMapper;
    private final PersonMapper personMapper;
    private final ProductMapper productMapper;
    private final ProjectMapper projectMapper;
    private final ProjectStageMapper projectStageMapper;
    private final StageActionMapper stageActionMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ISystemConfigService systemConfigService;
    /**
     * 真事务边界。用 {@code TransactionTemplate} 而非把 {@code @Transactional} 挪到
     * {@code seed()}：Spring 的事务靠代理生效，{@code run()} 内部自调用 seed() 不走代理，
     * 注解会静默失效（2026-10-07 改版时踩到）。走 TransactionTemplate 还能让
     * 「回滚」发生在事务内、「记 ERROR」发生在事务外——两者都成立。
     */
    private final TransactionTemplate transactionTemplate;

    private static final List<String> SIX_STAGES = List.of("CONCEPT", "PLAN", "DEV", "VALID", "LAUNCH", "LIFECYCLE");

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[IPD] ZK-IPD 基线场景初始化已按配置跳过（ipd.seed.zk-scenario.enabled=false）");
            return;
        }
        // 种子失败绝不能拖垮应用启动：基线数据缺失应表现为「数据没灌上」，
        // 而不是「服务起不来」。故整体兜住异常，只记 ERROR。
        // 幂等由本类内部 exists/selectCount 保证，失败后下次启动会重试。
        try {
            transactionTemplate.executeWithoutResult(status -> seed());
        } catch (Exception ex) {
            log.error("[IPD] ZK-IPD 基线场景初始化失败（不影响应用启动；下次启动会因幂等重试）", ex);
        }
    }

    /** 在 {@link #run} 传入的事务内执行。自身不加 {@code @Transactional}（自调用不走代理）。 */
    private void seed() {
        Long gMarketHw = ensureGroup("时间安全管理产品组");
        Long gMarketSw = ensureGroup("云应用产品组");
        Long gMarketIt = ensureGroup("项目集成产品组");
        Long gRdHw = ensureGroup("时间安全管理产品线");
        Long gRdSw = ensureGroup("云应用产品线");
        Long gRdIt = ensureGroup("项目集成产品线");

        Long fuZhiqian = ensurePerson("傅志谦", "GMP-001", "SUPER_ADMIN", null, "L5", "超级管理员（ZK-IPD 场景）");
        Long yangBo = ensurePerson("杨波", "MHW-L01", "GROUP_LEADER", gMarketHw, "L5", "时间安全管理产品组组长");
        Long duanJinke = ensurePerson("段进科", "MHW-P01", "MARKET_PM", gMarketHw, "L3", "市场PM（ZK-IPD 场景）");
        Long wenYuanbiao = ensurePerson("文元彪", "MSW-L01", "GROUP_LEADER", gMarketSw, "L4", "云应用产品组组长");
        Long huJiaolu = ensurePerson("胡蛟露", "MSW-P01", "MARKET_PM", gMarketSw, "L4", "市场PM（ZK-IPD 场景）");
        Long chenZepeng = ensurePerson("陈泽鹏", "MIT-L01", "GROUP_LEADER", gMarketIt, "L4", "项目集成产品组组长");
        Long chenBiqin = ensurePerson("陈必勤", "MIT-P01", "MARKET_PM", gMarketIt, "L3", "市场PM（ZK-IPD 场景）");
        Long xiaoJinglong = ensurePerson("肖敬龙", "RHW-L01", "GROUP_LEADER", gRdHw, "L5", "时间安全管理产品线组长");
        Long chengLong = ensurePerson("程龙", "RHW-P01", "RD_PM", gRdHw, "L4", "研发PM（ZK-IPD 场景）");
        ensurePerson("杨志君", "RSW-L01", "GROUP_LEADER", gRdSw, "L4", "云应用产品线组长");
        Long linLijie = ensurePerson("林立杰", "RSW-P01", "RD_PM", gRdSw, "L3", "研发PM（ZK-IPD 场景）");
        Long shangguanZhichang = ensurePerson("上官志昌", "RIT-L01", "GROUP_LEADER", gRdIt, "L4", "项目集成产品线组长");
        ensurePerson("方武略", "RIT-P01", "RD_PM", gRdIt, "L4", "研发PM（ZK-IPD 场景）");

        // ---- 3 个已完结项目（ARCHIVED + 全阶段 DONE + DONE 动作）----
        // 出处：wss 原型 scenario.mjs 的 PROJECTS（ENT-AC-100 / ZK-IAT-ATT / VIS-RD-100），
        // 路径 /Users/mac/Documents/wss/产品流程细化管理工具 2/server/scenario.mjs。
        // 注意该源数据**不在本仓库内**，本类是其 Java 侧镜像；改动请两边同步。
        // 逐条核对结论见 docs/ipd-系统说明/验收/wss-需求对照-20261006/110-系统基线数据来源台账.md。
        // 以下数值逐条取自原型 scenario.mjs 的 PROJECTS（A 级按系统规则不记差异化系数，见 seedActiveProject javadoc）：
        //   ENT-AC-100 : targetSales 8,000,000 channels 30 nps 45 scenes 4 coeff 1.0(A级固定) launch 2024-10-15
        //   ZK-IAT-ATT  : targetSales 12,000,000 channels 60 nps 50 scenes 6 coeff 1.5   launch 2025-01-20
        //   VIS-RD-100  : targetSales 6,000,000  channels 20 nps 45 scenes 5 coeff 1.0(A级固定) launch 2025-05-30
        seedCompletedProject("ENT-AC-100", "入门级门禁产品", "HARDWARE", "A",
            new BigDecimal("8000000"), 30, 45, 4, new BigDecimal("1.0"), "2024-10-15",
            duanJinke, chengLong, gMarketHw);
        seedCompletedProject("ZK-IAT-ATT", "熵基互联考勤模块", "SOFTWARE", "S",
            new BigDecimal("12000000"), 60, 50, 6, new BigDecimal("1.5"), "2025-01-20",
            huJiaolu, linLijie, gMarketSw);
        seedCompletedProject("VIS-RD-100", "访客机＋万傲瑞达", "SOLUTION", "A",
            new BigDecimal("6000000"), 20, 45, 5, new BigDecimal("1.0"), "2025-05-30",
            chenBiqin, shangguanZhichang, gMarketIt);

        // ---- 2 个运行态项目（原 workbench 硬编码 demo；供待办/超期/责任过滤真实测试）----
        // 这两个在途项目在**原型真实数据库里也存在**（data/ipd.sqlite 的 projects 表共 5 行），
        // 只是编码与 scenario.mjs 的 PROJECTS 常量不同——常量只声明 3 个已完结项目，
        // 两个在途项目是原型库里的既有数据。2026-10-07 已按原型真实库的编码对齐：
        //   原型 pm2008  如门禁测试        → 系统 pm2008   （原误写 ZK-GATE-TEST）
        //   原型 PM00085 熵基互联+智能锁  → 系统 PM00085  （原误写 ZK-IAT-LOCK，名称也多了「联动」二字）
        // ⚠️ 教训：核对基线数据要看**原型真实数据库**，不能只看 scenario.mjs 的种子常量——
        //   我先前只读常量就下了「这两个项目不在 wss 里」的错误结论。
        //   逐条核对见 docs/ipd-系统说明/验收/wss-需求对照-20261006/110-系统基线数据来源台账.md。
        // 原型库 data/ipd.sqlite：pm2008「如门禁测试」product_type=软硬件融合 → 系统 SOLUTION
        // （系统模板类型词表 HARDWARE|SOFTWARE|SOLUTION，「软硬件一体/融合」对应 SOLUTION），
        // target_launch_date=2027-06-30。四基准原型未给，留空不臆造。
        Project gateTest = seedActiveProject("pm2008", "如门禁测试", "SOLUTION", "B",
            "PLAN", duanJinke, chengLong, gMarketHw, null, null, null, null, new BigDecimal("0.8"), "2027-06-30");
        action(gateTest, "PLAN", "P01", "市场准入合规清单梳理", "MARKET_PM", "IN_PROGRESS", daysFromNow(-5));
        action(gateTest, "PLAN", "P02", "渠道商对接名单确认", "MARKET_PM", "DELAYED", daysFromNow(-10));
        action(gateTest, "PLAN", "P03", "门禁协议对接联调", "RD_PM", "NOT_STARTED", daysFromNow(14));
        action(gateTest, "PLAN", "P04", "认证送样准备", "BOTH", "NOT_STARTED", daysFromNow(30));
        action(gateTest, "CONCEPT", "C01", "概念立项评审", "BOTH", "DONE", null);
        action(gateTest, "CONCEPT", "C02", "目标市场与竞品分析", "MARKET_PM", "DONE", null);

        // 原型库：PM00085「熵基互联+智能锁」product_type=软硬件融合 → SOLUTION，target_launch_date=2027-06-30。
        Project iatLock = seedActiveProject("PM00085", "熵基互联+智能锁", "SOLUTION", "A",
            "DEV", huJiaolu, linLijie, gMarketSw, null, null, null, null, new BigDecimal("1.0"), "2027-06-30");
        action(iatLock, "DEV", "D01", "智能锁通信协议评审", "RD_PM", "IN_PROGRESS", daysFromNow(7));
        action(iatLock, "DEV", "D02", "联动场景用例设计", "MARKET_PM", "NOT_STARTED", daysFromNow(10));
        action(iatLock, "DEV", "D03", "固件联调计划", "RD_PM", "NOT_STARTED", daysFromNow(-2));
        action(iatLock, "PLAN", "P01", "项目计划评审", "BOTH", "DONE", null);
        action(iatLock, "CONCEPT", "C01", "概念立项评审", "BOTH", "DONE", null);

        log.info("[IPD] ZK-IPD 场景种子完成（6组/13人/3完结项目/2运行态项目；幂等跳过已存在）");
    }

    /** 已完结项目：ARCHIVED + 六阶段 DONE + 每阶段 2 个 DONE 动作（completed 统计可测）。 */
    private void seedCompletedProject(String code, String name, String templateType, String level,
                                      BigDecimal targetSales, Integer targetChannels, Integer targetNps,
                                      Integer targetScenes, BigDecimal levelCoefficient, String launchDate,
                                      Long marketPmId, Long rdPmId, Long mainGroupId) {
        Project project = seedActiveProject(code, name, templateType, level, "LIFECYCLE", marketPmId, rdPmId,
                mainGroupId, targetSales, targetChannels, targetNps, targetScenes, levelCoefficient, launchDate);
        project.setStatus("ARCHIVED");
        project.setLifecycleStatus("ARCHIVED");
        // 上市日期已按原型 launch 落库（见调用处）；这里不再用 daysFromNow(-180) 覆盖——
        // 原来那行会把三个项目都写成同一个「今天减 180 天」，使四基准与上市日期对不上原型。
        projectMapper.updateById(project);
        for (String stage : SIX_STAGES) {
            action(project, stage, "A01", stageNameOf(stage) + "阶段评审", "BOTH", "DONE", null);
            action(project, stage, "A02", stageNameOf(stage) + "阶段交付物归档", "BOTH", "DONE", null);
        }
    }

    /** 运行态项目：product 1:1 + 六阶段实例（当前阶段前 DONE/当前 IN_PROGRESS）+ 双PM成员。 */
    private Project seedActiveProject(String code, String name, String templateType, String level,
                                      String currentStage, Long marketPmId, Long rdPmId, Long mainGroupId,
                                      BigDecimal targetSales) {
        return seedActiveProject(code, name, templateType, level, currentStage, marketPmId, rdPmId,
                mainGroupId, targetSales, null, null, null, null, null);
    }

    /**
     * 写入项目并带上原型的业务基准值。
     *
     * <p><b>字段映射按语义对齐，不照抄</b>（原型 scenario.mjs 与系统 Project 结构不同，
     * 同名字段只有 6 个）：</p>
     * <pre>
     * 原型 targetSales/targetChannels/targetNps/targetScenarios
     *   → 系统 targetSalesAmount / targetChannelCount / targetNps / targetSceneCount  （四基准，直接对应）
     * 原型 strategic: "A"|"S"
     *   → 系统 level: S|A|B                                                          （直接对应）
     * 原型 launch
     *   → 系统 launchDate                                                          （直接对应）
     * </pre>
     *
     * <p><b>唯一不能照抄的是津贴系数</b>：原型 {@code allowanceCoeff} 是「津贴系数」，
     * 系统 {@code levelCoefficient} 是「<b>差异化系数</b>」——两者不是同一概念。
     * 且系统规则明写「S 记 1.5–2.0 / B 记 0.6–0.8 / <b>A 固定不录</b>」，
     * 原型那两个 A 级项目写的 {@code allowanceCoeff: 1} 照抄过来就是违规。
     * 故只对 S 级传系数，A 级一律留空。
     */
    private Project seedActiveProject(String code, String name, String templateType, String level,
                                      String currentStage, Long marketPmId, Long rdPmId, Long mainGroupId,
                                      BigDecimal targetSales, Integer targetChannels, Integer targetNps,
                                      Integer targetScenes, BigDecimal levelCoefficient, String launchDate) {
        Project exist = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
            .eq(Project::getCode, code).last("limit 1"));
        if (exist != null) {
            return exist;
        }
        Product product = ensureProduct(name, mainGroupId);
        Project project = Project.builder()
            .code(code).name(name)
            .productId(product.getId())
            .templateType(templateType)
            .level(level)
            // 原型未给销售目标的在途项目**保持 null**，不要强转 0：
            // targetSalesAmount 是「立项目标销售额（奖金池基数）」，0 表示「目标就是 0」，
            // null 表示「尚未设定」——两者语义不同，混同会让奖金池基数被当成 0 参与计算。
            .targetSalesAmount(targetSales)
            .targetChannelCount(targetChannels)
            .targetNps(targetNps)
            .targetSceneCount(targetScenes)
            .levelCoefficient(levelCoefficient)
            .launchDate(launchDate == null ? null : java.sql.Date.valueOf(launchDate))
            .currentStage(currentStage)
            .source("NEW")
            .status("ACTIVE")
            .mainGroupId(mainGroupId)
            .build();
        project.setCreateTime(new Date());
        projectMapper.insert(project);
        product.setProjectId(project.getId());
        productMapper.updateById(product);

        for (int i = 0; i < SIX_STAGES.size(); i++) {
            String stageCode = SIX_STAGES.get(i);
            int order = SIX_STAGES.indexOf(currentStage);
            String stageStatus = i < order ? "DONE" : i == order ? "IN_PROGRESS" : "NOT_STARTED";
            projectStageMapper.insert(ProjectStage.builder()
                .projectId(project.getId())
                .stageCode(stageCode)
                .stageName(stageNameOf(stageCode))
                .sortOrder(i + 1)
                .status(stageStatus)
                .build());
        }
        member(project.getId(), marketPmId, "MARKET_PM");
        member(project.getId(), rdPmId, "RD_PM");
        return project;
    }

    private void member(Long projectId, Long personId, String role) {
        Long exists = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, personId));
        if (exists > 0) {
            return;
        }
        // BR-INC-02：绑定成员必须落评级快照（locked_level NOT NULL 无默认值，fail-fast 设计）。
        // 缺此赋值时，一旦 seed 项目行被清（如测试清理）重 seed 即炸启动
        // （2026-09-19 实测 Field 'locked_level' doesn't have a default value → Application run failed）。
        Person person = personMapper.selectById(personId);
        if (person == null || person.getLevel() == null || person.getLevel().isBlank()) {
            throw new IllegalStateException(
                "ZK seed member: person " + personId + " missing level for locked_level snapshot (BR-INC-02)");
        }
        // BR-INC-02：锁定额与 P2-4.1 绑定流程同源（system_configs allowance.<level>），非硬编码
        int amount = systemConfigService.getIntValue("allowance." + person.getLevel(), -1);
        if (amount <= 0) {
            throw new IllegalStateException("ZK seed member: 津贴参数缺失 allowance." + person.getLevel());
        }
        ProjectMember m = ProjectMember.builder()
            .projectId(projectId).personId(personId).role(role)
            .memberType("PRIMARY").joinDate(new Date()).bonusEligible("1")
            .lockedLevel(person.getLevel())
            .lockedAmount(BigDecimal.valueOf(amount))
            .build();
        m.setCreateTime(new Date());
        projectMemberMapper.insert(m);
    }

    /** 阶段动作（幂等：project+actionCode 已存在即跳过）。 */
    private void action(Project project, String stageCode, String actionCode, String actionName,
                        String ownerRole, String status, Date dueDate) {
        Long exists = stageActionMapper.selectCount(new LambdaQueryWrapper<StageAction>()
            .eq(StageAction::getProjectId, project.getId())
            .eq(StageAction::getActionCode, actionCode));
        if (exists > 0) {
            return;
        }
        ProjectStage stage = projectStageMapper.selectOne(new LambdaQueryWrapper<ProjectStage>()
            .eq(ProjectStage::getProjectId, project.getId())
            .eq(ProjectStage::getStageCode, stageCode).last("limit 1"));
        StageAction a = StageAction.builder()
            .projectId(project.getId())
            .stageId(stage != null ? stage.getId() : null)
            .actionCode(actionCode)
            .actionName(actionName)
            .ownerRole(ownerRole)
            .depth("DEEP")
            .status(status)
            .isBlocking("0")
            .dueDate(dueDate)
            .actualDoneAt("DONE".equals(status) ? new Date() : null)
            .build();
        a.setCreateTime(new Date());
        stageActionMapper.insert(a);
    }

    private Product ensureProduct(String productName, Long groupId) {
        Product exist = productMapper.selectOne(new LambdaQueryWrapper<Product>()
            .eq(Product::getProductName, productName).last("limit 1"));
        if (exist != null) {
            return exist;
        }
        Product fresh = Product.builder()
            .productName(productName)
            .source(Product.SRC_PM_NEW)
            .groupId(groupId)
            .status(Product.ST_ACTIVE)
            .build();
        fresh.setCreateTime(new Date());
        productMapper.insert(fresh);
        return fresh;
    }

    private Long ensureGroup(String name) {
        ProductGroup g = productGroupMapper.selectOne(new LambdaQueryWrapper<ProductGroup>()
            .eq(ProductGroup::getGroupName, name).last("limit 1"));
        if (g != null) {
            return g.getId();
        }
        ProductGroup fresh = ProductGroup.builder()
            .groupName(name).description("ZK-IPD 场景种子")
            .build();
        fresh.setCreateTime(new Date());
        productGroupMapper.insert(fresh);
        return fresh.getId();
    }

    private Long ensurePerson(String name, String employeeNo, String personType, Long groupId, String level, String remark) {
        Person exist = personMapper.selectOne(new LambdaQueryWrapper<Person>()
            .eq(Person::getEmployeeNo, employeeNo).last("limit 1"));
        if (exist != null) {
            return exist.getId();
        }
        Person p = Person.builder()
            .name(name)
            .employeeNo(employeeNo)
            .personType(personType)
            .groupId(groupId)
            .level(level)
            .levelSource("MOCK")
            .accountStatus("ACTIVE")
            .employmentStatus("ACTIVE")
            .username(name)
            // SEC-HIGH-1/2：BCrypt cost 10；密码走 ipd.security.initial-password 注入，源码无字面量
            .passwordHash(BCrypt.hashpw(runtimeInitialPwd, BCrypt.gensalt(10)))
            .mustChangePwd("1")
            .remark(remark)
            .build();
        p.setCreateTime(new Date());
        personMapper.insert(p);
        return p.getId();
    }

    private String stageNameOf(String stageCode) {
        return switch (stageCode) {
            case "CONCEPT" -> "概念";
            case "PLAN" -> "计划";
            case "DEV" -> "开发";
            case "VALID" -> "验证";
            case "LAUNCH" -> "发布";
            case "LIFECYCLE" -> "生命周期";
            default -> stageCode;
        };
    }

    private Date daysFromNow(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }
}
