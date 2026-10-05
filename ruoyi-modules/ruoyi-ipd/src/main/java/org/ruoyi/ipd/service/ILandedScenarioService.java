package org.ruoyi.ipd.service;

import java.time.LocalDate;
import java.util.List;
import org.ruoyi.ipd.domain.LandedScenario;
import org.ruoyi.ipd.security.IpdActor;

/**
 * ILandedScenarioService 接口（paiban-05 接口化，实现见 {@link LandedScenarioService}）。
 */
public interface ILandedScenarioService {

    /** * 单条录入落地场景。 */
    LandedScenario record(LandedScenario draft, IpdActor actor);

    /** * 批量导入落地场景（同一事务）。 */
    /** * */
    /** * @param items 场景列表（≤ {@link #BATCH_IMPORT_MAX} 条） */
    /** * @param actor 当前操作人 */
    /** * @return 成功导入条数 */
    int importBatch(List<LandedScenario> items, IpdActor actor);

    /** * 按项目 + 月份查询落地场景。 */
    /** * */
    /** * @param projectId 项目ID（必填） */
    /** * @param period    月份（YYYY-MM-01 形式，可空；为空时返回该项目全部） */
    List<LandedScenario> list(Long projectId, LocalDate period);

}
