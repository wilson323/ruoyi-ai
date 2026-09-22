package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.ProjectScore;
import org.ruoyi.ipd.domain.SwitchingAcceptance;
import org.ruoyi.ipd.dto.SwitchingAcceptanceReport.CheckResult;
import org.ruoyi.ipd.dto.SwitchingAcceptanceReport;
import org.ruoyi.ipd.dto.SwitchingAcceptanceUnlockReq;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectScoreMapper;
import org.ruoyi.ipd.mapper.SwitchingAcceptanceMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ISwitchingAcceptanceService 接口（paiban-05 接口化，实现见 {@link SwitchingAcceptanceService}）。
 */
public interface ISwitchingAcceptanceService {

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setAllowanceLedgerMapper(AllowanceLedgerMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setBonusPoolMapper(BonusPoolMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setProjectScoreMapper(ProjectScoreMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setNegativeFeedbackMapper(NegativeFeedbackMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setContributionMapper(ContributionMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setHandoverMapper(HandoverMapper m);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setAuditLogService(IAuditLogService auditLogService);

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    void setClock(java.time.Clock clock);

    /** * 运行对账（生成报告）。 */
    /** * <p>本期实现：5 类校验全跑 + 落 switching_acceptance 表（uk_month 唯一）。 */
    /** * <p>如该月已存在 run 记录，则更新（保留历史；不覆盖 lock 状态）。 */
    SwitchingAcceptanceReport run(String monthStr);

    /** * 月度锁定（仅超管；AC-INC-51 联动锁定月份所有账务写入）。 */
    /** * <p>前置条件：run 已执行且 passed=true。 */
    SwitchingAcceptanceReport lock(String monthStr);

    /** * 月度解锁（仅超管；事故恢复用）。 */
    /** * <p>必须附解锁理由（unlockReason >= 5 字符）。 */
    SwitchingAcceptanceReport unlock(String monthStr, SwitchingAcceptanceUnlockReq req);

    /** * 获取月份对账报告（已 run 过则直接返回）。 */
    SwitchingAcceptanceReport get(String monthStr);

    /** * 检查月份是否锁定（其他账务 Service 联动调用）。 */
    /** * <p>未 run 或未锁定 → 返回 false；run 后 passed=true 但未 lock → 返回 false（可继续写）。 */
    boolean isMonthLocked(String monthStr);

    /** * 列出已 run 月份（按月倒序）。 */
    List<SwitchingAcceptanceReport> list();

}
