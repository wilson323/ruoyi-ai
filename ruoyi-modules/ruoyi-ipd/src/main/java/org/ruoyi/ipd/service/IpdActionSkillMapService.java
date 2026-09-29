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
 * 动作技能映射服务（69 行元数据只读）。
 * <p>归并序稳定：listAll 按 subStageCode + sort_order 升序（大阶段展示序由 IpdSubStageService 主导）。
 * skill_names 解析 fail-loud：JSON 非法抛 90001，不静默吞坏数据（silent-failure 红线）。
 */
@Service
@RequiredArgsConstructor
public class IpdActionSkillMapService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IpdActionSkillMapMapper skillMapMapper;

    /** 全量映射（69 行），按小阶段码 + 小阶段内 sort_order 升序。 */
    public List<IpdActionSkillMap> listAll() {
        List<IpdActionSkillMap> rows = new ArrayList<>(skillMapMapper.selectList(null));
        rows.sort(Comparator.comparing(IpdActionSkillMap::getSubStageCode)
            .thenComparingInt(IpdActionSkillMap::getSortOrder));
        return rows;
    }

    /** 某小阶段的动作映射，按 sort_order 升序。 */
    public List<IpdActionSkillMap> listBySubStage(String subStageCode) {
        return skillMapMapper.selectList(new LambdaQueryWrapper<IpdActionSkillMap>()
            .eq(IpdActionSkillMap::getSubStageCode, subStageCode)
            .orderByAsc(IpdActionSkillMap::getSortOrder));
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
