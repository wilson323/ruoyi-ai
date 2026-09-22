package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRuleSnapshot;
import org.ruoyi.ipd.mapper.KpiRuleSnapshotMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IKpiRecordRuleVersionService 接口（paiban-05 接口化，实现见 {@link KpiRecordRuleVersionService}）。
 */
public interface IKpiRecordRuleVersionService {

    /** * 落盘新规则版本快照，自动闭合前一版本 effectiveTo，返回新版本号。 */
    /** * */
    /** * <p>版本号生成规则：当前最大版本号 + 1；首条版本号 = 1。 */
    /** * 同一规则 JSON 重复提交视为错误（NO-OP 不创造新版本，避免版本污染）。 */
    /** * */
    /** * @param snapshot 规则快照输入（version/effectiveFrom/effectiveTo/ruleJson/createdBy 必填） */
    /** * @return 新版本号 */
    Long snapshotRuleVersion(KpiRuleSnapshot snapshot);

    /** * 按版本号取规则快照；不存在时抛 IpdBusinessException(NOT_FOUND)。 */
    KpiRuleSnapshot getRuleVersion(Long version);

    /** * 列出全部规则版本（仅超管），按 version DESC。 */
    List<KpiRuleSnapshot> listVersions();

}
