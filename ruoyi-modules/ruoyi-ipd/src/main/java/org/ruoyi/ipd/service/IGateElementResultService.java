package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IGateElementResultService 接口（paiban-05 接口化，实现见 {@link GateElementResultService}）。
 */
public interface IGateElementResultService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** 要素清单（含当前判定）：33 要素按 Gate 展示，未判定项 result=null 供前端高亮缺失。 */
    List<Map<String, Object>> checklist(Long gateId);

    /** 逐项判定写入（同要素重复提交 = 改判更新，审计留痕）。 */
    /** * <p>AC-GATE-16：CONDITIONAL 必填说明 + 责任人 + 关闭期限（缺一拒绝，规格 40002 口径）。 */
    GateElementResult judge(
        Long gateId,
        Long elementId,
        String result,
        String conditionNote,
        String evidenceRef,
        Integer verifications,
        Integer writtenIntents,
        Long responsiblePersonId,
        Date closeDeadline,
        IpdActor operator
    );

    /** * 提交 Gate 评审：[SEC-FIX-HIGH-1.1] 全要素已判 + 否决项未 FAIL + */
    /** * 强制输出物（评审材料 + 会议纪要）+ 冻结要素定义快照。 */
    Gate submit(
        Long gateId,
        Long materialsOssId,
        Long meetingMinutesOssId,
        IpdActor operator
    );

    /** 遗留清单（含已关与未关）：遗留查询仅依赖本表，要素停用/删除不消除遗留（AC-GATE-17 防线）。 */
    List<Map<String, Object>> legacyList(Long gateId);

    /** 关闭遗留项：仅责任人本人或超管；证据必填（AC「关闭需证据」）。 */
    GateElementResult close(
        Long gateId,
        Long resultId,
        String evidence,
        IpdActor operator
    );

    /** 逾期扫描（AC-GATE-17 前半）：OPEN 且期限已过 → 通知责任人（publishDaily 每日去重）。 */
    /** * <p>ops cron（OPS 卡）与超管手工触发共用本方法；返回本次命中的遗留项数。 */
    int scanOverdue(IpdActor operator);

}
