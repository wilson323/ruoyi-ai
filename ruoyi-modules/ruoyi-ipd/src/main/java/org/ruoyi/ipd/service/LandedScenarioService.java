package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.LandedScenario;
import org.ruoyi.ipd.mapper.LandedScenarioMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 落地场景登记服务（A4 落地场景登记，R149 batch2a）。
 *
 * <p>核心契约（用户拍板简化版）：
 * <ul>
 *   <li>{@link #record(LandedScenario, IpdActor)}：单条录入；同 (project_id, scenario_code) 重复抛 STATE_CONFLICT</li>
 *   <li>{@link #importBatch(List, IpdActor)}：批量录入（JSON 数组导入）；同一批次内重复抛 STATE_CONFLICT</li>
 *   <li>{@link #list(Long, LocalDate)}：按项目 + 月份（landed_date 落该月）列出</li>
 * </ul>
 *
 * <p>不做（用户拍板简化）：
 * <ul>
 *   <li>不做销售报备表 JOIN 验证</li>
 *   <li>不做交付验收表 JOIN 验证</li>
 *   <li>不做双认定（dual-cert）流程</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LandedScenarioService implements ILandedScenarioService {

    /** 批量导入上限（防误传百万行打爆 DB；按 PM 实际使用场景，200 条足够一个月补登） */
    public static final int BATCH_IMPORT_MAX = 200;

    private final LandedScenarioMapper landedScenarioMapper;
    /**
     * 归属守卫所需（守卫3 {@link IpdIdorGuard#requireProjectMemberOrSuperAdmin}）。
     *
     * <p>2026-10-03 归属收口：{@code record} / {@code importBatch} 原先零对象级判定——
     * 只做字段校验与查重，actor 仅落成 {@code recorded_by}，任何内部用户可对任意项目登记落地场景。
     *
     * <p><b>为什么是守卫3（在职项目成员）而不是守卫6（{@code assertSameGroupIpd} 同组）</b>：
     * BR-ORG-06 三层权限矩阵「编辑项目」一行是普通PM「仅本人负责」/ 组长「仅本人名下」，不含组维；
     * BR-ORG-01 定义主组＝市场PM 组、协同组＝研发PM 组，研发PM 天然不在主组。
     * 用「同组」会把协同组研发PM 挡在门外——那是把功能弄坏而不是修好安全问题。
     */
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /**
     * 单条录入落地场景。
     */
    @Transactional(rollbackFor = Exception.class)
    public LandedScenario record(LandedScenario draft, IpdActor actor) {
        // 归属守卫前置：先判归属再判参数，未授权者不触达字段级校验信息（也让守卫不可被参数异常绕过）。
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, draft.getProjectId(), projectMemberMapper, projectMapper);
        validate(draft);
        KpiDuplicateCheck dup = (projectId, scenarioCode) -> landedScenarioMapper.selectOne(
            Wrappers.<LandedScenario>lambdaQuery()
                .eq(LandedScenario::getProjectId, projectId)
                .eq(LandedScenario::getScenarioCode, scenarioCode)
                .last("LIMIT 1")
        );
        ensureNoDuplicate(draft.getProjectId(), draft.getScenarioCode(), dup);
        draft.setRecordedBy(actor != null ? actor.id() : null);
        landedScenarioMapper.insert(draft);
        log.debug("LandedScenario 录入 id={} projectId={} scenarioCode={}",
            draft.getId(), draft.getProjectId(), draft.getScenarioCode());
        return draft;
    }

    /**
     * 批量导入落地场景（同一事务）。
     *
     * @param items 场景列表（≤ {@link #BATCH_IMPORT_MAX} 条）
     * @param actor 当前操作人
     * @return 成功导入条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int importBatch(List<LandedScenario> items, IpdActor actor) {
        if (items == null || items.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "导入列表不能为空");
        }
        if (items.size() > BATCH_IMPORT_MAX) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "批量导入超过上限 " + BATCH_IMPORT_MAX + " 条（实际 " + items.size() + "）");
        }

        // 归属守卫（守卫3）：按**去重后**的 projectId 各校验一次，且排在任何写入之前。
        // 为什么不逐条校验：BATCH_IMPORT_MAX = 200，逐条 = 最多 400 次查询（N+1）。
        // 为什么放在写入之前：本方法 @Transactional，但早失败比回滚更省。
        items.stream().map(LandedScenario::getProjectId).distinct()
            .forEach(pid -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                actor, pid, projectMemberMapper, projectMapper));

        // 同批次内先做内存级去重，再入库前做 DB 级去重
        java.util.Set<String> seenInBatch = new java.util.HashSet<>();
        int saved = 0;
        KpiDuplicateCheck dup = (projectId, scenarioCode) -> landedScenarioMapper.selectOne(
            Wrappers.<LandedScenario>lambdaQuery()
                .eq(LandedScenario::getProjectId, projectId)
                .eq(LandedScenario::getScenarioCode, scenarioCode)
                .last("LIMIT 1")
        );
        for (LandedScenario item : items) {
            validate(item);
            String dupKey = item.getProjectId() + "::" + item.getScenarioCode();
            if (!seenInBatch.add(dupKey)) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                    "批次内重复 scenarioCode：projectId=" + item.getProjectId()
                        + " scenarioCode=" + item.getScenarioCode());
            }
            ensureNoDuplicate(item.getProjectId(), item.getScenarioCode(), dup);
            item.setRecordedBy(actor != null ? actor.id() : null);
            landedScenarioMapper.insert(item);
            saved++;
        }
        log.info("LandedScenario 批量导入 saved={} actor={}", saved, actor != null ? actor.id() : null);
        return saved;
    }

    /**
     * 按项目 + 月份查询落地场景。
     *
     * @param projectId 项目ID（必填）
     * @param period    月份（YYYY-MM-01 形式，可空；为空时返回该项目全部）
     */
    public List<LandedScenario> list(Long projectId, LocalDate period) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        LambdaQueryWrapper<LandedScenario> wrapper = Wrappers.<LandedScenario>lambdaQuery()
            .eq(LandedScenario::getProjectId, projectId)
            .orderByDesc(LandedScenario::getLandedDate);
        if (period != null) {
            // period 必为当月 1 号；以 [period, period+1月) 区间查询
            LocalDate nextMonth = period.plusMonths(1);
            wrapper.between(LandedScenario::getLandedDate, period, nextMonth.minusDays(1));
        }
        return landedScenarioMapper.selectList(wrapper);
    }

    private void validate(LandedScenario s) {
        if (s == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "落地场景不能为空");
        }
        if (s.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        if (s.getScenarioCode() == null || s.getScenarioCode().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scenarioCode 不能为空");
        }
        if (s.getScenarioCode().length() > 64) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scenarioCode 长度不能超过 64");
        }
        if (s.getScenarioName() == null || s.getScenarioName().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scenarioName 不能为空");
        }
        if (s.getScenarioName().length() > 200) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scenarioName 长度不能超过 200");
        }
        if (s.getLandedDate() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "landedDate 不能为空");
        }
        if (s.getLandedAmount() != null && s.getLandedAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "landedAmount 不能为负数");
        }
    }

    /** 内部函数式接口：用于去重查询（避免在 lambda 中捕获 mapper） */
    @FunctionalInterface
    private interface KpiDuplicateCheck {
        LandedScenario findOne(Long projectId, String scenarioCode);
    }

    private void ensureNoDuplicate(Long projectId, String scenarioCode, KpiDuplicateCheck checker) {
        LandedScenario existed = checker.findOne(projectId, scenarioCode);
        if (existed != null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "落地场景重复登记：projectId=" + projectId
                    + " scenarioCode=" + scenarioCode
                    + "（已存在 id=" + existed.getId() + "）");
        }
    }

    /** 仅暴露用于测试的空安全 list 助手（生产路径未使用） */
    static List<LandedScenario> emptyIfNull(List<LandedScenario> items) {
        return items == null ? Collections.emptyList() : new ArrayList<>(items);
    }
}
