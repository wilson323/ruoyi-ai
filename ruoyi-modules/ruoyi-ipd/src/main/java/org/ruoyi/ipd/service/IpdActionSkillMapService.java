package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.mapper.IpdActionSkillMapMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 动作技能映射服务（元数据只读，无 Java 写入者；绑定写入 = owner 审核后的 seed SQL，写入即拍板）。
 * <p>目录读面（listAll/listBySubStage）只收 project_id=0 的全局默认行，保持 69 行目录视图不被项目级行污染；
 * 运行装配读面走 resolve（项目级绑定优先、无项目级绑定时回退全局默认）。
 * <p>归并序稳定：listAll 按 subStageCode + sort_order 升序（大阶段展示序由 IpdSubStageService 主导）。
 * skill_names 解析 fail-loud：JSON 非法抛 90001，不静默吞坏数据（silent-failure 红线）。
 */
@Service
@RequiredArgsConstructor
public class IpdActionSkillMapService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** project_id 哨兵：0 = 全局默认绑定（与表列 NOT NULL DEFAULT 0 对齐，见 2026-10-06 DDL）。 */
    public static final long GLOBAL_PROJECT_ID = 0L;

    private final IpdActionSkillMapMapper skillMapMapper;

    /** 全局默认映射（69 行，project_id=0），按小阶段码 + 小阶段内 sort_order 升序。 */
    public List<IpdActionSkillMap> listAll() {
        List<IpdActionSkillMap> rows = new ArrayList<>(skillMapMapper.selectList(globalRows()));
        rows.sort(Comparator.comparing(IpdActionSkillMap::getSubStageCode)
            .thenComparingInt(IpdActionSkillMap::getSortOrder));
        return rows;
    }

    /** 某小阶段的全局默认动作映射（project_id=0），按 sort_order 升序。 */
    public List<IpdActionSkillMap> listBySubStage(String subStageCode) {
        return skillMapMapper.selectList(globalRows()
            .eq(IpdActionSkillMap::getSubStageCode, subStageCode)
            .orderByAsc(IpdActionSkillMap::getSortOrder));
    }

    private static LambdaQueryWrapper<IpdActionSkillMap> globalRows() {
        return new LambdaQueryWrapper<IpdActionSkillMap>()
            .eq(IpdActionSkillMap::getProjectId, GLOBAL_PROJECT_ID);
    }

    /**
     * 运行装配解析（每次查库，不做进程内缓存）：项目级绑定优先，无项目级绑定时回退全局默认。
     *
     * <p>「绑定」= skill_names 非空的行：项目级行存在但未定稿（skill_names NULL）不算绑定，不拦截回退。
     *
     * @param actionCode 动作编码（如 C02）；空则返回 null
     * @param projectId 项目 ID；null/≤0 时只查全局默认
     * @return 命中的绑定行；两级均无绑定返回 null
     */
    public IpdActionSkillMap resolve(String actionCode, Long projectId) {
        if (actionCode == null || actionCode.isBlank()) {
            return null;
        }
        List<Long> candidates = projectId != null && projectId > 0
            ? List.of(projectId, GLOBAL_PROJECT_ID) : List.of(GLOBAL_PROJECT_ID);
        List<IpdActionSkillMap> rows = skillMapMapper.selectList(new LambdaQueryWrapper<IpdActionSkillMap>()
            .eq(IpdActionSkillMap::getActionCode, actionCode.trim())
            .in(IpdActionSkillMap::getProjectId, candidates));
        IpdActionSkillMap projectLevel = null;
        IpdActionSkillMap global = null;
        for (IpdActionSkillMap row : rows) {
            if (row.getSkillNames() == null || row.getSkillNames().isBlank()) {
                continue;
            }
            if (row.getProjectId() != null && row.getProjectId() > 0) {
                projectLevel = row;
            } else {
                global = row;
            }
        }
        return projectLevel != null ? projectLevel : global;
    }

    /**
     * skill_names JSON 数组文本 → List&lt;String&gt;。
     * NULL/空 → 空列表（§3 未定稿口径，前端按「无绑定技能」渲染）；JSON 非法抛 90001。
     */
    public static List<String> parseSkillNames(String skillNamesJson) {
        if (skillNamesJson == null || skillNamesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = JSON.readValue(skillNamesJson, new TypeReference<List<String>>() { });
            return names == null ? List.of() : List.copyOf(names);
        } catch (JsonProcessingException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "skill_names JSON 非法: " + skillNamesJson);
        }
    }
}
