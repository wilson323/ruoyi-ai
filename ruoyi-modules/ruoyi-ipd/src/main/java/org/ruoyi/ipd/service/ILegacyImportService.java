package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.LegacyImport;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.dto.LegacyImportReq;
import org.ruoyi.ipd.dto.LegacyImportResult;
import org.ruoyi.ipd.dto.LegacyImportRowResult;
import org.ruoyi.ipd.mapper.LegacyImportMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** * Round 8 / R8-P0-10：并行导入——按行提交 CompletableFuture，受 IMPORT_BATCH_PARALLELISM 限流。 */
    /** * 业务异常（ServiceException）和数据访问异常（DataAccessException）逐行捕获不影响其他行； */
    /** * 其它 RuntimeException（连接池耗尽 / DB 挂 / OOM）向上抛，由 Controller 统一处理。 */
    List<LegacyImportRowResult> importBatch(List<LegacyImportReq> rows, Long operatorId);

}
