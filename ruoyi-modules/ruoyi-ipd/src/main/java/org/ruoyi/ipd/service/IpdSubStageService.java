package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.mapper.IpdSubStageMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 小阶段目录服务（22 行元数据只读）。
 * <p>排序口径固化（§2.8 不变量②的运行时面）：大阶段顺序 CONCEPT→…→LIFECYCLE，KPI 常驻恒最后，
 * 阶段内按 sort_order 升序。目录行由 owner DDL 维护，本服务无写方法（单写者纪律）。
 */
@Service
@RequiredArgsConstructor
public class IpdSubStageService {

    /** 大阶段固定顺序（KPI=常驻伪阶段恒最后，见 §6.4.2 设计决策）。 */
    static final List<String> STAGE_ORDER =
        List.of("CONCEPT", "PLAN", "DEV", "VALID", "LAUNCH", "LIFECYCLE", "KPI");

    private final IpdSubStageMapper subStageMapper;

    /** 全量目录（22 行），按大阶段顺序 + 阶段内 sort_order 升序。 */
    public List<IpdSubStage> listAll() {
        List<IpdSubStage> rows = new ArrayList<>(subStageMapper.selectList(null));
        rows.sort(Comparator.comparingInt((IpdSubStage s) -> stageRank(s.getStageCode()))
            .thenComparingInt(IpdSubStage::getSortOrder));
        return rows;
    }

    /** 按小阶段码取目录行；未命中抛 50001（fail-loud，与 IpdResources 语义一致）。 */
    public IpdSubStage getByCode(String code) {
        IpdSubStage row = subStageMapper.selectOne(
            new LambdaQueryWrapper<IpdSubStage>().eq(IpdSubStage::getCode, code));
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "小阶段不存在: " + code);
        }
        return row;
    }

    /** 大阶段排序权重；未知阶段排最后（排序仅影响展示序，不吞数据不抛错）。 */
    static int stageRank(String stageCode) {
        int idx = STAGE_ORDER.indexOf(stageCode);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }
}
