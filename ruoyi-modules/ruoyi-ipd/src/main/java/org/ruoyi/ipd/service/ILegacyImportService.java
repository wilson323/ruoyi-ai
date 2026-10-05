package org.ruoyi.ipd.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.dto.LegacyImportReq;
import org.ruoyi.ipd.dto.LegacyImportResult;
import org.ruoyi.ipd.dto.LegacyImportRowResult;
import org.springframework.dao.DataAccessException;

/**
 * ILegacyImportService 接口（paiban-05 接口化，实现见 {@link LegacyImportService}）。
 */
public interface ILegacyImportService {

    /** * 单条存量导入。 */
    /** * */
    /** * @param req        白名单请求 */
    /** * @param operatorId 超管操作人 */
    /** * @return 项目 + 标记编码列表 */
    LegacyImportResult importOne(LegacyImportReq req, Long operatorId);

    /** * Round 8 / R8-P0-10：并行导入——按行提交 CompletableFuture（无并行度限流，仅受 MAX_BATCH_SIZE 上限约束）。 */
    /** * 业务异常（ServiceException）和数据访问异常（DataAccessException）逐行捕获不影响其他行； */
    /** * 其它 RuntimeException（连接池耗尽 / DB 挂 / OOM）向上抛，由 Controller 统一处理。 */
    List<LegacyImportRowResult> importBatch(List<LegacyImportReq> rows, Long operatorId);

}
