package org.ruoyi.ipd.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IStageActionService 接口（paiban-05 接口化，实现见 {@link StageActionService}）。
 */
public interface IStageActionService {

    /** * 阶段动作实例服务：深轻管分离完成校验（BR-IPD-03/04/05，动作清单 v3） */
    /** * */
    /** * 校验矩阵（以 ActionCatalog 目录为 SSOT，不信任前端）： */
    /** * - 深管 DONE：至少 1 个未删交付物（del_flag=0）——BR-IPD-03 强制附件 */
    /** * - 轻管 DONE：actual_done_at 必填（BR-IPD-05）；且**不允许 DELAYED**（轻管枚举无延期） */
    /** * - 数值登记（valueFields）：D11=FAR,FRR；V02=CERT_NO,CERT_DATE */
    /** * - 阻断跳阶（is_blocking）由 P1-5 GateEngine 消费本表状态 */
    /** * */
    /** * P1-4.3 状态机（仅 /transit 入口，禁止 PATCH status 字段）： */
    /** * - 深管：NOT_STARTED → IN_PROGRESS → DONE / NA / DELAYED */
    /** * - 轻管：NOT_STARTED → IN_PROGRESS → DONE / NA（无 DELAYED） */
    /** * - NA 必传 reason（防绕过） */
    /** * - 幂等：同 id 同 target 重复 /transit 返回当前状态，不写新审计 */
    /** * - 乐观锁：@Version；并发同 id 仅 1 成功 */
    StageAction getById(Long id);

    /** * 阶段动作实例服务：深轻管分离完成校验（BR-IPD-03/04/05，动作清单 v3） */
    /** * */
    /** * 校验矩阵（以 ActionCatalog 目录为 SSOT，不信任前端）： */
    /** * - 深管 DONE：至少 1 个未删交付物（del_flag=0）——BR-IPD-03 强制附件 */
    /** * - 轻管 DONE：actual_done_at 必填（BR-IPD-05）；且**不允许 DELAYED**（轻管枚举无延期） */
    /** * - 数值登记（valueFields）：D11=FAR,FRR；V02=CERT_NO,CERT_DATE */
    /** * - 阻断跳阶（is_blocking）由 P1-5 GateEngine 消费本表状态 */
    /** * */
    /** * P1-4.3 状态机（仅 /transit 入口，禁止 PATCH status 字段）： */
    /** * - 深管：NOT_STARTED → IN_PROGRESS → DONE / NA / DELAYED */
    /** * - 轻管：NOT_STARTED → IN_PROGRESS → DONE / NA（无 DELAYED） */
    /** * - NA 必传 reason（防绕过） */
    /** * - 幂等：同 id 同 target 重复 /transit 返回当前状态，不写新审计 */
    /** * - 乐观锁：@Version；并发同 id 仅 1 成功 */
    List<StageAction> listByProject(Long projectId);

    /** * 状态迁移唯一入口（P1-4.3）。 */
    /** * - 状态机白名单（depth + 目标） */
    /** * - 幂等：当前态 == 目标态 → 直接返回，不写库、不写审计 */
    /** * - NA 必 reason */
    /** * - DONE 触发深度+数值双重校验 */
    /** * - 乐观锁：@Version，updateById 失败（version 冲突）抛 ServiceException */
    /** * - 每次成功迁移写审计 action=TRANSIT */
    StageAction transit(Long id, String target, String reason, String operator);

    /** * P1-4.1 / P1-8.2：录入轻管完成日 / BioCV FAR·FRR / 证书 / 算法分类。 */
    /** * 不改 status；完成仍须随后 /transit→DONE。Z 别名写入时归一为权威码。 */
    /** * */
    /** * @param id           动作实例 ID */
    /** * @param actualDoneAt 实际完成日（可空表示不改） */
    /** * @param farValue     FAR（与 frr 成对） */
    /** * @param frrValue     FRR */
    /** * @param certNo       证书编号 */
    /** * @param certPassedAt 证书通过日 */
    /** * @param algoType     算法分类（可空；传空串视为未提交） */
    /** * @param operator     操作者 Person id 字符串 */
    /** * @return 更新后实例 */
    StageAction recordFields(
        Long id,
        Date actualDoneAt,
        BigDecimal farValue,
        BigDecimal frrValue,
        String certNo,
        Date certPassedAt,
        String algoType,
        String operator
    );

    /** * P1-8.1 / AC-PROD-13：项目已有涉生物动作且缺少 C12 时，补挂到 CONCEPT 阶段。 */
    /** * 幂等：无涉生物 / 已有 C12 → 返回 0；新挂返回 1。 */
    /** * */
    /** * @param projectId 项目主键 */
    /** * @return 新建 C12 条数（0 或 1） */
    /**
     * R212-③（看板卡 dbe1b6a7）：新增 {@code actor} 形参——HTTP 入口必须下传会话身份，
     * 服务内做「操作人组 == 项目主组」断言（SUPER_ADMIN 豁免），消除跨组挂载 C12 的横向越权面。
     */
    int ensureBioComplianceMount(Long projectId, org.ruoyi.ipd.security.IpdActor actor);

    /** * 是否存在未删的涉生物动作（is_bio_feature=1）。 */
    /** * */
    /** * @param projectId 项目主键 */
    /** * @return true 表示已标记涉生物 */
    boolean hasBioFeatureActions(Long projectId);

    /** * 解析 CONCEPT 阶段实例 ID；缺失则拒绝补挂（避免 stage_id 空违反 NOT NULL）。 */
    /** * */
    /** * @param projectId 项目主键 */
    /** * @return CONCEPT 阶段主键 */
    Deliverable addDeliverable(Long actionId, String fileName, Long ossId, String operator);

    /** * PERF-03：批量实例化阶段动作，从 N 次 selectCount + N 次 insert 优化为 */
    /** * 1 次 selectList（取项目所有已有 action codes）+ 1 次 insertBatch（批量插入剩余）。 */
    /** * 69 动作 CONCEPT 阶段 = 138 IO → 2 IO，P99 下降 ~250ms → ~20ms。 */
    /**
     * R212-②（看板卡 dbe1b6a7）：新增 {@code actor} 形参——HTTP 入口必须下传会话身份，
     * 服务内做「操作人组 == 项目主组」断言（SUPER_ADMIN 豁免），消除跨组批量物化阶段动作的横向越权面。
     */
    int instantiate(Long projectId, Long stageId, String stage, org.ruoyi.ipd.security.IpdActor actor);

}
