package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IBidResponseService 接口（paiban-05 接口化，实现见 {@link BidResponseService}）。
 */
public interface IBidResponseService {

    /** * 提交应标（研发PM）。rdPmId 以会话用户为准（服务端权威），不信任请求体； */
    /** * BR-REC-BID-03：同一研发PM同一招标单仅一份最新有效应标，重复提交覆盖更新不产生第二行。 */
    /** * */
    /** * @param actor    会话用户身份（W5-E-2.4：actor 入口校验，取 actor.id() 为应标人） */
    /** * @param response 应标内容（decision=accept|reject；accept 时 responseNote 承载方案摘要） */
    BidResponse submit(IpdActor actor, BidResponse response);

    /** * 撤回应标（仅应标本人；横向越权防御）。 */
    /** * W5-E-2.4：actor 入口校验（UNAUTHORIZED 兜底），撤回人取 actor.id()，归属校验逻辑零改。 */
    BidResponse withdraw(IpdActor actor, Long id);

    /** * 查询某研发PM的所有应标（W5-E-2.4 P0 #5 IDOR 修复）。 */
    /** * 三分支放行：本人（actor.id == rdPmId）/ SUPER_ADMIN / 关联项目在职 ProjectMember */
    /** * （该研发PM应标所隶属招标单 → 项目 → project_member 在职行，KpiSharedCollectionService 同款 exitDate IS NULL 口径）； */
    /** * 其余一律 FORBIDDEN——目标无应标行时第三方同样拒绝（fail-closed，不泄露「有无应标」布尔 oracle）。 */
    /** * */
    /** * <p>PERF-P0-4 兼容保留：不带分页，调用方需注意应标量过大时内存压力。 */
    /** * 新代码请优先用 {@link #listByRdPmPaged(IpdActor, Long, Integer, Integer)}。 */
    List<BidResponse> listByRdPm(IpdActor actor, Long rdPmId);

    /** * PERF-P0-4：分页查询某研发PM的所有应标（IDOR 三分支放行不变 + IPage 物理分页）。 */
    /** * */
    /** * <ul> */
    /** *   <li>走 {@code idx_br_rd_pm(rd_pm_id, create_time)} 复合索引，等值 + ORDER BY 一并覆盖</li> */
    /** *   <li>{@code pageSize} 上限 200 防滥用；null → 默认 20；&lt;1 → 1</li> */
    /** *   <li>三分支放行同 {@link #listByRdPm(IpdActor, Long)}，复用现有探测逻辑</li> */
    /** * </ul> */
    /** * */
    /** * @param actor    会话用户 */
    /** * @param rdPmId   被查询研发PM ID */
    /** * @param pageNo   页码（从 1 开始；&lt;1 → 1） */
    /** * @param pageSize 每页条数（&lt;1 → 1；&gt;200 → 200；null → 20） */
    IPage<BidResponse> listByRdPmPaged(
        IpdActor actor,
        Long rdPmId,
        Integer pageNo,
        Integer pageSize
    );

}
