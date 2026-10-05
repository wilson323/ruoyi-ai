package org.ruoyi.ipd.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.ruoyi.ipd.domain.RecoveryWarning;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IRecoveryWarningService 接口（paiban-05 接口化，实现见 {@link RecoveryWarningService}）。
 */
public interface IRecoveryWarningService {

    /** 可注入时钟（R156-A 根除债，仿 KpiRawRecordService 模式）。 */
    void setClock(java.time.Clock clock);

    /** * 扫描上市后未满 90 日的项目，回款比例低于阈值的写入预警表（归属范围内）。 */
    /** * */
    /** * @param actor 服务端会话身份：SUPER_ADMIN 扫全库，其他角色只扫本组项目 */
    /** * @param today 扫描当日（可空；为空时取系统当前日期） */
    /** * @return 新增预警条数（幂等去重后） */
    int checkAndGenerate(IpdActor actor, LocalDate today);

    /** * 按项目列出预警（按 warning_date DESC）。 */
    /** * */
    /** * @param projectId 项目ID（可空；为空时返回全部） */
    List<RecoveryWarning> list(Long projectId);

}
