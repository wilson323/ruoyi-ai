package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAllowanceLedgerService 接口（paiban-05 接口化，实现见 {@link AllowanceLedgerService}）。
 */
public interface IAllowanceLedgerService {

    /** * P3-3.1 多项目叠加 2 倍封顶计算。 */
    /** * <pre> */
    /** *   finalAmount = min(Σ baseAmount[i], baseAmount × capMultiplier) */
    /** *   capApplied  = "1" if 触发封顶 else "0" */
    /** * </pre> */
    /** * 其中 baseAmount = 单项目基础额（锁定评级对应），capMultiplier 默认 2.0。 */
    /** * */
    /** * @param baseAmountList 单项目基础额列表（同一人员的不同项目） */
    /** * @param capMultiplier  封顶倍数（可为 null，默认 2.0） */
    /** * @return AllowanceLedger 草稿（finalAmount + capApplied 已填充） */
    AllowanceLedger calcFinalAmount(
        AllowanceLedger draft,
        List<BigDecimal> baseAmountList,
        BigDecimal capMultiplier
    );

    /** * 草稿录入：绑定时锁定评级。 */
    AllowanceLedger draftBinding(AllowanceLedger draft);

    /** * W4-D 件 2 §1：月度津贴快照列表（按 period 必填 + 可选 personId 过滤）。 */
    /** * 端点 GET /api/v1/allowance/ledger 配套服务方法。 */
    /** * */
    /** * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数，service 层入口校验 actor 非空（UNAUTHORIZED）， */
    /** * 消除「无 actor 可披露全公司津贴明细」IDOR 缺口。读操作范围保持宽松： */
    /** * MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN 四角色由 Controller */
    /** * {@code @SaCheckPermission(OPERATION_KPI_QUERY)} + {@code requireInternal()} 双重守门（件 1.5）。 */
    /** * */
    /** * <p>排序：项目 ID 升序、人员 ID 升序；软删除（delFlag=0）由 MyBatis-Plus {@code @TableLogic} 自动过滤。 */
    /** * <p>DTO 字段对齐留作 W4-D' 单独任务；当前先以 AllowanceLedger 原样返回，前端 type 与后端 domain 字段名差异由前端适配层兜底。 */
    /** * */
    /** * @param actor   当前会话身份（必填，由 Controller {@code permission.requireInternal()} 传入） */
    /** * @param period  YYYY-MM（必填，违反格式抛 IpdBusinessException） */
    /** * @param personId 可选；为 null 时返回该月全员记录 */
    /** * @return AllowanceLedger 列表（可能为空但不会为 null） */
    List<AllowanceLedger> list(IpdActor actor, String period, Long personId);

    /** * W4-D 件 2 §2：待停发津贴列表（按 period 过滤；stopReason IS NOT NULL）。 */
    /** * 端点 GET /api/v1/allowance/pending-stop 配套服务方法。 */
    /** * */
    /** * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数，service 层入口校验 actor 非空（UNAUTHORIZED）， */
    /** * 消除「无 actor 可披露全公司停发明细」IDOR 缺口。读操作范围与 {@link #list} 同款 */
    /** * （四角色由 Controller 双重守门，件 1.5）。 */
    /** * */
    /** * <p>判定：{@code stopReason} 非空即视为「待停发」（P3-3.2 触发：SCORE_BELOW_60 / NO_OUTPUT_60_DAYS）。 */
    /** * <p>不输出已经 freeze 的台账（{@code finalAmount=0} 且 {@code stopReason} 非空 ⇒ 已停发确认）。 */
    /** * */
    /** * @param actor  当前会话身份（必填，由 Controller {@code permission.requireInternal()} 传入） */
    /** * @param period YYYY-MM */
    /** * @return 停发原因非空的 AllowanceLedger 列表 */
    List<AllowanceLedger> pendingStop(IpdActor actor, String period);

    /** * W4-D 件 2 §3：月度自动扫描（按 period；返回当月所有台账记录数）。 */
    /** * 端点 POST /api/v1/allowance/auto-scan 配套服务方法（仅超管）。 */
    /** * */
    /** * <p>W5-E-2.3 P0 #3 修复：签名加 {@code actor} 第一参数 + service 层两重守卫—— */
    /** * actor 非空（UNAUTHORIZED）与仅 SUPER_ADMIN（FORBIDDEN）。原注释明言「仅超管」但守卫只在 */
    /** * Controller（W4-D {@code requireAdmin()}），service 层裸奔（W5-E P0 #3 原话「controller 决定」）； */
    /** * 此处方法内兜底与 Controller 注解/requireAdmin 同严，防资金域操作失防（与 BonusPool */
    /** * compute/freeze/distribute 方法内兜底同款治理，SEC-06）。 */
    /** * */
    /** * <p>当前为「扫描 + 计数」简单委派实现，复杂扫描（绩效分 <60 / 60 天无产出判定）留待后续任务，调用方 AllowanceService.determineStopReasonP332 已就绪。 */
    /** * */
    /** * @param actor  当前会话身份（必填，须 SUPER_ADMIN；由 Controller {@code permission.requireAdmin()} 传入） */
    /** * @param period YYYY-MM */
    /** * @return 当月 AllowanceLedger 行数（Int 范围；>2^31 抛 IpdBusinessException） */
    Integer autoScan(IpdActor actor, String period);

}
