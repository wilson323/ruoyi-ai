package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BonusAllocation;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.BonusAllocationMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IBonusPoolService 接口（paiban-05 接口化，实现见 {@link BonusPoolService}）。
 */
public interface IBonusPoolService {

    /** P-DATA-gap-1 接线：测试显式注入入口（对齐 setStateMachineGuard 模式）。 */
    void setBonusAllocationMapper(BonusAllocationMapper bonusAllocationMapper);

    /** P-DATA-gap-1 接线：测试显式注入入口（对齐 setStateMachineGuard 模式）。 */
    void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper);

    /** P-DATA-gap-1 接线：测试显式注入入口（对齐 setStateMachineGuard 模式）。 */
    void setContributionMapper(ContributionMapper contributionMapper);

    /** ROOT-R3-P0-1 修复：Spring 注入 StateMachineGuard（fail-closed 改造后，测试可显式注入 mock） */
    void setStateMachineGuard(org.ruoyi.ipd.service.StateMachineGuard stateMachineGuard);

    /** * R149 A1：注入 ISystemConfigService（nullable，兼容旧测试）以读取奖金池窗口月数配置。 */
    /** * 配置键：{@code bonus.windowMonths}（system_configs 表），缺省 {@link #DEFAULT_WINDOW_MONTHS}=6。 */
    /** * 走 setter 模式，与 {@link #setStateMachineGuard} / {@link #setBusinessConfigService} 同型。 */
    void setSystemConfigService(ISystemConfigService systemConfigService);

    /** * AC-INC-17/17b~17h + AC-INC-18~21： */
    /** * 按 {@code 达成率 ≥ 阈值} 从高到低匹配，返回对应阶梯系数。 */
    /** * 区间下端点含（如 100%、120%）、上端点不含（如 120% 含但 120.01% 走 1.2 档）。 */
    BigDecimal tierCoefficientOf(BigDecimal achievementRate);

    /** * AC-INC-20：达成率 60% 命中 0.3 档，触发复盘检讨提醒 */
    /** * 阈值规则：达成率 ∈ [50%, 70%) 中命中 0.3 档 → 需复盘 */
    boolean isReviewRequired(BigDecimal achievementRate);

    /** * P3-4.5 AC-INC-22/23/24 + BR-INC-07：按综合得分 + 取数策略查项目绩效系数。 */
    /** * */
    /** * <p>策略路由（当前 MVP）： */
    /** * <ul> */
    /** *   <li>PROJECT_SCORE：调用方传入“当期”综合得分</li> */
    /** *   <li>WEIGHTED_AVG：调用方传入多期加权平均分</li> */
    /** *   <li>LAST_QUARTER：调用方传入“上季度”综合得分</li> */
    /** * </ul> */
    /** * 入参 score 的语义由 strategy 决定；本方法仅做参数校验 + 策略校验 + 委托分档计算。 */
    /** * */
    /** * <p>分档映射委托 {@link ProjectScoreService#projectPerformanceCoefficient(BigDecimal)}。 */
    /** * 严禁出现“以能力等级 L1–L5 代替绩效得分”的入参——能力等级与绩效得分独立（AC-INC-22b）。 */
    /** * */
    /** * @param score    综合得分（0~100） */
    /** * @param strategy 取数策略（PROJECT_SCORE / WEIGHTED_AVG / LAST_QUARTER） */
    /** * @return 项目绩效系数；score < 60 ⇒ 0 取消奖金资格 */
    /** * @throws ServiceException score 为 null / 越界 / strategy 非法 */
    BigDecimal calculatePerformanceCoefficient(BigDecimal score, String strategy);

    /** * P3-4.5 BR-INC-07：从 system_configs 读取当前取数策略。 */
    /** * */
    /** * <p>策略配置键：{@code bonus.performance.strategy}；缺省 = PROJECT_SCORE。 */
    /** * 配置读取失败（key 不存在 / 配置服务异常）静默回退默认策略（不阻塞业务）。 */
    /** * */
    /** * @param projectId 项目 ID（当前 MVP 未参与路由；预留给 P3-4.6 多项目叠加用） */
    /** * @param score     综合得分 */
    /** * @return 项目绩效系数 */
    BigDecimal resolvePerformanceCoefficient(Long projectId, BigDecimal score);

    /** * P3-4.5 AC-INC-22b + preview 端点契约：预览系数（仅查表/计算，不写 audit、不落库）。 */
    /** * */
    /** * <p>返回值：未持久化的 BonusPool（仅填 {@code tierCoefficient} 字段，其它字段保持原样）。 */
    /** * 用途：前端 P0-10.34 激励管理页签「试算」按钮，仅做即时反馈，不入奖金池实算。 */
    /** * */
    /** * @param projectId 项目 ID（预留给扩展：PROJECT_SCORE 策略下可顺带校验项目存在性） */
    /** * @param score     综合得分 */
    /** * @param strategy  取数策略 */
    /** * @param actor     当前操作人（仅日志；不落审计） */
    /** * @return 预览 BonusPool（tierCoefficient 已设置；finalPool = 0 表示无金额） */
    BonusPool previewCoefficient(
        Long projectId,
        BigDecimal score,
        String strategy,
        IpdActor actor
    );

    /** * @deprecated 口径废弃（[CONSISTENCY-1] 2026-09-06 owner 裁决）： */
    /** * 原 AC-INC-16「目标销售额 × 5%」被 ZK 完整版 Prompt「实际回款 × 5%」覆盖。 */
    /** * Controller {@link #compute} 主入口已迁移至 {@link #calculateBonusPoolByZkFormulaWithModifiers}， */
    /** * 本方法保留实现仅供旧 P343 测试与历史查询回放兼容；新代码禁止调用。 */
    /** * 替代：{@link #calculateBonusPoolByZkFormula(BigDecimal, BigDecimal)} */
    BigDecimal calculateBasePool(BigDecimal targetSales, BigDecimal poolRate);

    /** * R149 A1：读取奖金池窗口月数（上市后连续多少个月的实际回款计入基数）。 */
    /** * <p>配置键：{@code bonus.windowMonths}（Integer）；配置缺省/解析失败/服务未注入 ⇒ 回退 {@link #DEFAULT_WINDOW_MONTHS}。 */
    /** * */
    /** * @return 窗口月数；保证 ≥ 1（非法值回退默认） */
    int readWindowMonths();

    /** * ROOT-R1 P0-7：Spring 注入 IBusinessConfigService（nullable 兼容旧测试）。 */
    void setBusinessConfigService(IBusinessConfigService businessConfigService);

    /** * P3-4.5：项目绩效系数分档依赖注入（兼容旧测试构造器）。 */
    /** * 4 参构造器未注入时为 null；P345AcceptanceTest 等单测通过 setter 注入 mock。 */
    /** * 不可用 @Autowired(required=false) 直接标字段（与 BonusPoolMapper 等已有注入路径冲突）， */
    /** * 故走 setter 模式，与 setStateMachineGuard / setAuditLogService 同严。 */
    void setProjectScoreService(ProjectScoreService projectScoreService);

    /** * ROOT-R1 P0-7：读取奖金池比例（poolRate）。优先 IBusinessConfigService.BONUS_POOL_RATE，回退 DEFAULT_CONFIG_POOL_RATE。 */
    BigDecimal readActivePoolRate();

    /** * ROOT-R1 P0-7：读取奖金池比例（兼容旧 0.05 默认值；与 readActivePoolRate 同源）。 */
    BigDecimal readPoolRateZk();

    /** * @deprecated 口径废弃（[CONSISTENCY-1] 2026-09-06 owner 裁决）： */
    /** * 旧 P3-4.2「targetSales × poolRate」被 ZK 完整版 Prompt「actualReceipts × poolRate」覆盖（奖金池基数 = 上市后连续 N 个月实际回款，N 默认 6）。 */
    /** * 计算逻辑保留以便 P342 测试回归；新代码请改用 {@link #calculateBonusPoolByZkFormula}。 */
    BigDecimal calculateBasePoolConfigurable(BigDecimal targetSales);

    /** * @deprecated 口径废弃（[CONSISTENCY-1] 2026-09-06 owner 裁决）。保留实现供 P342/P343 历史用例回归； */
    /** * 新代码请改用 {@link #calculateBonusPoolByZkFormula}。 */
    BigDecimal calculateBasePoolWithRate(BigDecimal targetSales, BigDecimal poolRate);

    /** * P3-4.2 BR-INC-05：项目 S/A/B 系数（按 projectLevel 决定合法区段） */
    BigDecimal getDefaultCoefficientFor(String projectLevel);

    /** * AC-INC-16 + AC-INC-17：最终奖金池 = 基础奖金池 × 阶梯系数 */
    BigDecimal calculateFinalPool(BigDecimal basePool, BigDecimal tierCoefficient);

    /** * @deprecated 口径废弃（[CONSISTENCY-1] 2026-09-06 owner 裁决）。本方法走旧 targetSales 路径已被 ZK 实际回款路径替代； */
    /** * BonusPool 实体的 targetSales 字段仅作历史兼容保留，不再作为权威基数来源。新代码请改用 {@link #buildPoolFromProject}。 */
    BonusPool fillDerivedFields(BonusPool pool);

    /** * @deprecated 口径废弃（[CONSISTENCY-1] 2026-09-06 owner 裁决）。本方法走旧 targetSales 路径已被 ZK 实际回款路径替代； */
    /** * BonusPool 实体的 targetSales 字段仅作历史兼容保留，不再作为权威基数来源。新代码请改用 {@link #buildPoolFromProject}。 */
    BonusPool save(BonusPool pool);

    /** * P3-4.4 §2.5：按项目查询奖金池列表（按 calculatedAt 倒序）。 */
    /** * */
    /** * @deprecated 性能废弃（PERF-P0-2 2026-09-07）：全量 List 返回在大项目（1000+ 行）存在 OOM / 超时风险； */
    /** * web-antd 旧调用方暂保留兼容，新代码请改用 {@link #pageByProject(Long, Page)}。 */
    /** * 计划在 web-antd 全量切到 /page 端点后下线。 */
    List<BonusPool> listByProject(Long projectId);

    /** * PERF-P0-2：按项目分页查询奖金池（按 calculatedAt 倒序，物理分页）。 */
    /** * */
    /** * <p>与 {@link #listByProject(Long)} 的差异： */
    /** * <ul> */
    /** *   <li>入参携带 {@link Page}（pageNo/pageSize），DB 层 LIMIT/OFFSET 物理裁剪</li> */
    /** *   <li>返回 {@link IPage}，含 total / records / pages / current / size 完整分页元信息</li> */
    /** *   <li>无项目数据时返回空 records，total=0（不抛错）</li> */
    /** * </ul> */
    /** * */
    /** * <p>排序与软删语义与 {@link #listByProject} 完全一致（{@code @TableLogic} 自动过滤 del_flag=0）。 */
    /** * */
    /** * @param projectId 项目 ID（必填，null 抛 IpdBusinessException(PARAM_INVALID)） */
    /** * @param page      MyBatis-Plus 分页对象（pageNo ≥ 1 且 pageSize 1..200 由 Controller 端兜底） */
    /** * @return 分页结果（IPage<BonusPool>），records 已含当前页实体 */
    /** * @throws IpdBusinessException(PARAM_INVALID) projectId 为 null */
    IPage<BonusPool> pageByProject(Long projectId, Page<BonusPool> page);

    /** * ZK-IPD §三.2.1：奖金池 = 实际回款 × poolRate × S/A/B 差异化系数 */
    /** * */
    /** * <p>与既有 {@link #calculateBasePool} 的区别： */
    /** * <ul> */
    /** *   <li>基数：实际回款（actualReceipts），不是目标销售额（targetSales）</li> */
    /** *   <li>乘数：项目 S/A/B 差异化系数（coefficient），不是达成率阶梯（tierCoefficient）</li> */
    /** * </ul> */
    /** * */
    /** * @param actualReceipts   上市后连续 N 个月实际回款净额（N 由 {@code system_configs.config_key='bonus.windowMonths'} 控制，默认 6 个月，见 {@link #DEFAULT_WINDOW_MONTHS}/{@link #readWindowMonths()}） */
    /** * @param levelCoefficient 项目 S/A/B 差异化系数（S 1.5–2.0 / B 0.6–0.8 / A 固定 1.0） */
    /** * @return 奖金池金额；回款 ≤ 0 返回 ZERO；入参空抛 ServiceException */
    BigDecimal calculateBonusPoolByZkFormula(BigDecimal actualReceipts, BigDecimal levelCoefficient);

    /** * ZK-IPD §三.2.1：构造 BonusPool（系数取自 Project.levelCoefficient，非 tierCoefficient）。 */
    /** * */
    /** * <p>差异矩阵 2026-09-06 P0 项：既有 {@link #fillDerivedFields} 不读 project.levelCoefficient， */
    /** * 本方法补齐该字段孤岛——BonusPool.coefficient 必须 = Project.levelCoefficient。 */
    /** * */
    /** * @param projectId      项目 ID */
    /** * @param actualReceipts 实际回款金额 */
    /** * @param calculatedAt   计算时间 */
    /** * @param poolRate       奖金池比例（默认 5%） */
    /** * @return 新建 BonusPool（未持久化） */
    BonusPool buildPoolFromProject(
        Long projectId,
        BigDecimal actualReceipts,
        Date calculatedAt,
        BigDecimal poolRate
    );

    /** * ZK-IPD §三.2.1 + §三.2.5 完整公式：奖金池 = 实际回款 × 5% × S/A/B 系数 × 销售达成率阶梯 × 个人绩效。 */
    /** * */
    /** * <p>与 {@link #calculateBonusPoolByZkFormula} 的关系：本方法是"完整公式" 入口， */
    /** * 原方法是 §三.2.1 主公式（不带修正因子）的便捷重载；二者均属 ZK-IPD 合法路径。 */
    /** * */
    /** * <ul> */
    /** *   <li>tierCoefficient：销售达成率阶梯系数（0~1.2），由 {@link #tierCoefficientOf} 推出；缺省 = 1.0</li> */
    /** *   <li>personalCoefficient：个人绩效系数，缺省 = 1.0（中性）</li> */
    /** * </ul> */
    /** * */
    /** * @param actualReceipts     上市后连续 N 个月实际回款净额（N 由 {@code system_configs.config_key='bonus.windowMonths'} 控制，默认 6） */
    /** * @param levelCoefficient   项目 S/A/B 差异化系数 */
    /** * @param tierCoefficient    销售达成率阶梯系数（AC-INC-17h，0~1.2；null = 1.0） */
    /** * @param personalCoefficient 个人绩效系数（null = 1.0） */
    /** * @return 奖金池金额；回款 ≤ 0 返回 ZERO；任一非缺省入参空抛 ServiceException */
    BigDecimal calculateBonusPoolByZkFormulaWithModifiers(
        BigDecimal actualReceipts,
        BigDecimal levelCoefficient,
        BigDecimal tierCoefficient,
        BigDecimal personalCoefficient
    );

    /** * ZK-IPD §三.2.1 + §三.2.5 + §三.2.3 联动：构造 BonusPool，自动从 achievementRate 推 tierCoefficient。 */
    /** * */
    /** * <p>与 {@link #buildPoolFromProject} 的区别：本方法额外接收 achievementRate 与 personalCoefficient， */
    /** * 按 §三.2.5 修正因子叠加规则计算 finalPool，并写入 tierCoefficient 字段（消除"绿但对应错误实现"）。 */
    /** * */
    /** * @param projectId           项目 ID */
    /** * @param actualReceipts      实际回款金额 */
    /** * @param achievementRate     销售达成率（%），由 {@link #tierCoefficientOf} 推 tierCoefficient；null = 1.0 */
    /** * @param personalCoefficient 个人绩效系数；null = 1.0 */
    /** * @param calculatedAt        计算时间 */
    /** * @param poolRate            奖金池比例（默认 5%） */
    /** * @return 新建 BonusPool（未持久化），finalPool 已按 §三.2.5 完整公式计算 */
    BonusPool buildPoolFromProjectWithAchievement(
        Long projectId,
        BigDecimal actualReceipts,
        BigDecimal achievementRate,
        BigDecimal personalCoefficient,
        Date calculatedAt,
        BigDecimal poolRate
    );

    /** * ZK-IPD §三.2.4：校验市场 PM / 研发 PM 分配比例 */
    /** * <ul> */
    /** *   <li>市场 PM 占比 ∈ [40%, 65%]</li> */
    /** *   <li>研发 PM 占比 ∈ [35%, 60%]</li> */
    /** *   <li>市场 + 研发 = 100%（容差 0.0001）</li> */
    /** *   <li>上市 90 天复盘后由双 PM + 上级三方最终评定</li> */
    /** * </ul> */
    /** * */
    /** * @param marketShare 市场 PM 分配比例（0.40–0.65） */
    /** * @param rdShare     研发 PM 分配比例（0.35–0.60） */
    /** * @return {marketShare, rdShare, sum} 不可变映射 */
    /** * @throws ServiceException 任一校验失败 */
    java.util.Map<String, BigDecimal> calculateDistribution(BigDecimal marketShare, BigDecimal rdShare);

    /** * ZK-IPD §三.2.4：按市场/研发分配比例把奖金池拆分到两位 PM */
    /** * */
    /** * @param pool        总奖金池 */
    /** * @param marketShare 市场 PM 占比 */
    /** * @param rdShare     研发 PM 占比 */
    /** * @return {marketAmount, rdAmount} 不可变映射 */
    java.util.Map<String, BigDecimal> applyDistribution(BigDecimal pool, BigDecimal marketShare, BigDecimal rdShare);

    /** * P3-4.4：注入审计服务（Spring 装配入口）。 */
    /** * 测试构造器 {@link #BonusPoolService(BonusPoolMapper)} / {@link #BonusPoolService(BonusPoolMapper, ProjectMapper)} */
    /** * 维持不变，新 auditLogService 默认 null——审计相关测试需用 setAuditLogService 注入 mock。 */
    void setAuditLogService(IAuditLogService auditLogService);

    /** * [SEC-FIX-HIGH-5.2] 个人绩效系数自动推导——查最新 kpi_records 综合得分，按 ZK 5 档分档。 */
    /** * */
    /** * <p>[SEC-FIX-HIGH-5.2-FOLLOWUP] selectList 全表扫描修复：原实现 {@code findFirst().orElse(null)} */
    /** * 在数据库无索引优化时退化为全表扫；现改为： */
    /** * <ol> */
    /** *   <li>{@code selectCount} 预检，{@code FINAL} 状态同一 {@code (projectId, period)} 行数若超 */
    /** *       {@link #RESOLVE_PERSONAL_COEFFICIENT_MAX_ROWS}（合理上限 = 一期一个 PM ≈ 12 行）则 fail-fast， */
    /** *       防止下游分档被异常数据污染</li> */
    /** *   <li>{@code selectList + orderByDesc(comprehensive_score) + LIMIT 1} 取最高分；LIMIT 1 保证 DB */
    /** *       层裁剪（不拉全表），按综合得分而非 createTime 排序更贴合「max 分」语义</li> */
    /** * </ol> */
    /** * */
    /** * @param projectId 项目 ID */
    /** * @param period YYYY-MM */
    /** * @return 个人绩效系数（0/0.3/0.6/0.8/1.0）；无记录回退 1.0（中性） */
    BigDecimal resolvePersonalCoefficient(Long projectId, String period);

    /** * [SEC-FIX-HIGH-5.2] 个人绩效系数自动推导——查最新 kpi_records 综合得分，按 ZK 5 档分档。 */
    /** * */
    /** * <p>[SEC-FIX-HIGH-5.2-FOLLOWUP] selectList 全表扫描修复：原实现 {@code findFirst().orElse(null)} */
    /** * 在数据库无索引优化时退化为全表扫；现改为： */
    /** * <ol> */
    /** *   <li>{@code selectCount} 预检，{@code FINAL} 状态同一 {@code (projectId, period)} 行数若超 */
    /** *       {@link #RESOLVE_PERSONAL_COEFFICIENT_MAX_ROWS}（合理上限 = 一期一个 PM ≈ 12 行）则 fail-fast， */
    /** *       防止下游分档被异常数据污染</li> */
    /** *   <li>{@code selectList + orderByDesc(comprehensive_score) + LIMIT 1} 取最高分；LIMIT 1 保证 DB */
    /** *       层裁剪（不拉全表），按综合得分而非 createTime 排序更贴合「max 分」语义</li> */
    /** * </ol> */
    /** * */
    /** * @param projectId 项目 ID */
    /** * @param period YYYY-MM */
    /** * @return 个人绩效系数（0/0.3/0.6/0.8/1.0）；无记录回退 1.0（中性） */
    BonusPool compute(
        Long projectId,
        BigDecimal actualReceipts,
        BigDecimal achievementRate,
        BigDecimal personalCoefficient,
        BigDecimal poolRate,
        IpdActor actor
    );

    /** * P3-4.4 §2.2：冻结/确认奖金池（DRAFT → CONFIRMED）。 */
    /** * */
    /** * <p>幂等：已是 CONFIRMED/DISTRIBUTED 直接返回当前实体，不写第二条审计。 */
    /** * */
    /** * @param id     奖金池 ID */
    /** * @param reason 冻结理由（可空） */
    /** * @param actor  当前操作人（审计落名） */
    /** * @return 更新后的 BonusPool */
    BonusPool freeze(Long id, String reason, IpdActor actor);

    /** * P3-4.4 §2.3：分配奖金池（DRAFT/CONFIRMED → DISTRIBUTED）。 */
    /** * */
    /** * <p>幂等：已是 DISTRIBUTED 直接返回当前实体，不重写审计。 */
    /** * 比例校验走 §三.2.4 calculateDistribution（区段 + 总和双重护栏）。 */
    /** * */
    /** * @param id          奖金池 ID */
    /** * @param marketShare 市场 PM 占比 */
    /** * @param rdShare     研发 PM 占比 */
    /** * @param actor       当前操作人（审计落名） */
    /** * @return 更新后的 BonusPool */
    BonusPool distribute(
        Long id,
        BigDecimal marketShare,
        BigDecimal rdShare,
        IpdActor actor
    );

    /** * P3-4.4 §2.4：查询奖金池详情（带软删过滤）。 */
    BonusPool getById(Long id);

    /** * 注册 postCommit 副作用（事务提交后触发，避免回滚后污染） */
}
