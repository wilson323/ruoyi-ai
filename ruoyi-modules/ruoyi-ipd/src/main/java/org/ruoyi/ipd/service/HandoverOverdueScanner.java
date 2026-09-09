package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 移交 DRAFT 超时扫描升级调度器（2026-09-09 治理轮补齐；AC-HAND 漏斗恢复）。
 *
 * <p>每日 09:05（错开 PersonResignEscalator 的 09:00）扫描 {@code handover_records}
 * 中 status=DRAFT 的记录：发起超过 {@link #REMIND_AFTER_HOURS} 小时通知承接人催办，
 * 超过 {@link #ESCALATE_AFTER_HOURS} 小时升级给全部 ACTIVE SUPER_ADMIN。
 * 通知走 {@link NotificationService#publishDaily}（dedupKey 追加自然日，同日重扫不重发）。
 *
 * <p>调度启用条件：需应用配置 {@code @EnableScheduling}（OPS-04 scheduler 卡合入主树后生效；
 * 未启用时 @Scheduled 注解被 Spring 忽略，无副作用）。手动验证可直调 {@link #dailyOverdueScan()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoverOverdueScanner {

    /** 催办阈值（小时）：DRAFT 发起超过该时长通知承接人。 */
    public static final int REMIND_AFTER_HOURS = 24;

    /** 升级阈值（小时）：DRAFT 发起超过该时长升级全部 SUPER_ADMIN。 */
    public static final int ESCALATE_AFTER_HOURS = 48;

    private final HandoverMapper handoverMapper;
    private final PersonMapper personMapper;
    private final NotificationService notificationService;

    /**
     * 每日 09:05 扫描 DRAFT 移交超时并催办/升级（@EnableScheduling 启用后生效）。
     */
    @Scheduled(cron = "0 5 9 * * ?")
    public void dailyOverdueScan() {
        Date now = new Date();
        List<HandoverRecord> drafts = handoverMapper.selectList(
            new LambdaQueryWrapper<HandoverRecord>().eq(HandoverRecord::getStatus, "DRAFT"));
        if (drafts.isEmpty()) {
            log.info("HandoverOverdueScanner: 无 DRAFT 移交记录");
            return;
        }
        int reminded = 0;
        int escalated = 0;
        for (HandoverRecord rec : drafts) {
            Date created = rec.getCreateTime();
            if (created == null) {
                continue;
            }
            long ageHours = (now.getTime() - created.getTime()) / 3_600_000L;
            if (ageHours < REMIND_AFTER_HOURS) {
                continue;
            }
            String projectDesc = rec.getProjectId() == null
                ? "（非项目移交：" + rec.getHandoverType() + "）"
                : "项目 #" + rec.getProjectId();
            if (ageHours >= ESCALATE_AFTER_HOURS) {
                escalated += escalate(rec, projectDesc, ageHours, now);
            } else {
                reminded += remind(rec, projectDesc, ageHours, now);
            }
        }
        log.info("HandoverOverdueScanner: drafts={}, reminded={}, escalated={}",
            drafts.size(), reminded, escalated);
    }

    /** 催办承接人（24h~48h 窗口）。 */
    private int remind(HandoverRecord rec, String projectDesc, long ageHours, Date day) {
        if (rec.getToPersonId() == null) {
            return 0;
        }
        notificationService.publishDaily(rec.getToPersonId(), "HANDOVER_DUE_SOON",
            NotificationService.KIND_ACTION, "handover_records", rec.getId(),
            "移交待确认已超 " + ageHours + " 小时",
            "有一条发往您的移交记录（" + projectDesc + "）已发起 " + ageHours
                + " 小时未确认，请在 48 小时升级线前处理。",
            null, day);
        return 1;
    }

    /** 升级全部 ACTIVE SUPER_ADMIN（48h 线）。 */
    private int escalate(HandoverRecord rec, String projectDesc, long ageHours, Date day) {
        List<Long> admins = personMapper.selectList(
                new LambdaQueryWrapper<Person>()
                    .eq(Person::getPersonType, "SUPER_ADMIN")
                    .eq(Person::getEmploymentStatus, "ACTIVE")
                    .eq(Person::getAccountStatus, "ACTIVE")
                    .eq(Person::getDelFlag, "0"))
            .stream().map(Person::getId).toList();
        for (Long adminId : admins) {
            notificationService.publishDaily(adminId, "HANDOVER_OVERDUE_ESCALATION",
                NotificationService.KIND_ACTION, "handover_records", rec.getId(),
                "移交 DRAFT 超时升级（" + projectDesc + "）",
                "移交记录 #" + rec.getId() + "（" + projectDesc + "）DRAFT 已挂起 "
                    + ageHours + " 小时（升级线 " + ESCALATE_AFTER_HOURS + "h），请介入处理。",
                null, day);
        }
        return 1;
    }
}
