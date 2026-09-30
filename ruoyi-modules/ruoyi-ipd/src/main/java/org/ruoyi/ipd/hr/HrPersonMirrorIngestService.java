package org.ruoyi.ipd.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.HrPersonMirror;
import org.ruoyi.ipd.hr.HrResponse.BasicInfo;
import org.ruoyi.ipd.mapper.HrPersonMirrorMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * HR 人员档案镜像落库服务（D10 落地，2026-09-29）。
 *
 * <p>接收 HR §3.2.1 的 {@link BasicInfo}（xlsx「产品IPD」员工信息 20 字段 + 口径列），
 * 按 {@code pernr} 幂等 upsert 到 {@code hr_person_mirror} 表：
 * <ul>
 *   <li>新行 INSERT（pernr 不存在）；已存在行 UPDATE（任何字段差异 + last_sync_at + trigger_by）</li>
 *   <li>幂等：同 pernr 二次写不产生重复行（uk_hr_person_pernr 唯一索引兜底）</li>
 *   <li>DEL_FLAG 不落表（IPD 口径：删除标识 ×）；离职语义只保留在 leave_flag/stat2 原始值列</li>
 * </ul>
 *
 * <p><b>防双轨边界</b>：本表只是 HR 真源字段快照，登录/权限仍走 {@code persons}；
 * 入库前调用方须先过 {@link HrSyncRules#isEligible}（员工类型过滤，非正式工不落任何表）。
 *
 * <p>装配闸门：{@code @ConditionalOnProperty(ipd.hr.enabled=true)}（与 RealHrSyncAdapter 一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ipd.hr.enabled", havingValue = "true")
public class HrPersonMirrorIngestService {

    private final HrPersonMirrorMapper mapper;

    /**
     * 行级 upsert：返回是否实际写入（INSERT 或差异 UPDATE）。
     * {@code pernr} 为空或 BasicInfo 为空直接跳过（由调用方计数）。
     */
    public boolean upsert(String pernr, BasicInfo info, String triggerBy) {
        if (pernr == null || pernr.isBlank() || info == null) {
            return false;
        }
        Date now = new Date();
        HrPersonMirror existing = mapper.selectOne(new LambdaQueryWrapper<HrPersonMirror>()
            .eq(HrPersonMirror::getPernr, pernr)
            .last("LIMIT 1"));
        if (existing == null) {
            HrPersonMirror newcomer = HrPersonMirror.builder()
                .pernr(pernr)
                .nachn(info.nachn)
                .rufnm(info.rufnm)
                .gesch(info.gesch)
                .natio(info.natio)
                .hireDate(info.hireDate)
                .leaveDate(info.leaveDate)
                .phone(info.phone)
                .emailCom(info.emailCom)
                .orgeh(info.orgeh)
                .deptname(info.deptname)
                .plans(info.plans)
                .positionname(info.positionname)
                .positiongrade(info.positiongrade)
                .qualificationlevel(info.qualificationlevel)
                .supervisorno(info.supervisorno)
                .supervisorname(info.supervisorname)
                .certificate(info.certificate)
                .insitute(info.insitute)
                .lineOfStudy(info.lineOfStudy)
                .empcategory(info.empcategory)
                .stat2(info.stat2)
                .leaveFlag(info.leaveFlag)
                .lastSyncAt(now)
                .triggerBy(triggerBy)
                .delFlag("0")
                .build();
            mapper.insert(newcomer);
            log.debug("ipd_hr_mirror INSERT: pernr={}", pernr);
            return true;
        }
        LambdaUpdateWrapper<HrPersonMirror> uw = new LambdaUpdateWrapper<HrPersonMirror>()
            .eq(HrPersonMirror::getPernr, pernr);
        boolean changed = false;
        changed |= setIfDiff(uw, HrPersonMirror::getNachn, existing.getNachn(), info.nachn);
        changed |= setIfDiff(uw, HrPersonMirror::getRufnm, existing.getRufnm(), info.rufnm);
        changed |= setIfDiff(uw, HrPersonMirror::getGesch, existing.getGesch(), info.gesch);
        changed |= setIfDiff(uw, HrPersonMirror::getNatio, existing.getNatio(), info.natio);
        changed |= setIfDiff(uw, HrPersonMirror::getHireDate, existing.getHireDate(), info.hireDate);
        changed |= setIfDiff(uw, HrPersonMirror::getLeaveDate, existing.getLeaveDate(), info.leaveDate);
        changed |= setIfDiff(uw, HrPersonMirror::getPhone, existing.getPhone(), info.phone);
        changed |= setIfDiff(uw, HrPersonMirror::getEmailCom, existing.getEmailCom(), info.emailCom);
        changed |= setIfDiff(uw, HrPersonMirror::getOrgeh, existing.getOrgeh(), info.orgeh);
        changed |= setIfDiff(uw, HrPersonMirror::getDeptname, existing.getDeptname(), info.deptname);
        changed |= setIfDiff(uw, HrPersonMirror::getPlans, existing.getPlans(), info.plans);
        changed |= setIfDiff(uw, HrPersonMirror::getPositionname, existing.getPositionname(), info.positionname);
        changed |= setIfDiff(uw, HrPersonMirror::getPositiongrade, existing.getPositiongrade(), info.positiongrade);
        changed |= setIfDiff(uw, HrPersonMirror::getQualificationlevel, existing.getQualificationlevel(), info.qualificationlevel);
        changed |= setIfDiff(uw, HrPersonMirror::getSupervisorno, existing.getSupervisorno(), info.supervisorno);
        changed |= setIfDiff(uw, HrPersonMirror::getSupervisorname, existing.getSupervisorname(), info.supervisorname);
        changed |= setIfDiff(uw, HrPersonMirror::getCertificate, existing.getCertificate(), info.certificate);
        changed |= setIfDiff(uw, HrPersonMirror::getInsitute, existing.getInsitute(), info.insitute);
        changed |= setIfDiff(uw, HrPersonMirror::getLineOfStudy, existing.getLineOfStudy(), info.lineOfStudy);
        changed |= setIfDiff(uw, HrPersonMirror::getEmpcategory, existing.getEmpcategory(), info.empcategory);
        changed |= setIfDiff(uw, HrPersonMirror::getStat2, existing.getStat2(), info.stat2);
        changed |= setIfDiff(uw, HrPersonMirror::getLeaveFlag, existing.getLeaveFlag(), info.leaveFlag);
        if (changed) {
            uw.set(HrPersonMirror::getLastSyncAt, now);
            uw.set(HrPersonMirror::getTriggerBy, triggerBy);
            mapper.update(null, uw);
            log.debug("ipd_hr_mirror UPDATE: pernr={}", pernr);
        }
        return changed;
    }

    /** 差异检测 + 委托 set；空串/ null 归一化后比较（HR 出参常见 "" 与 null 交替）。 */
    private static <T> boolean setIfDiff(LambdaUpdateWrapper<HrPersonMirror> uw,
                                         com.baomidou.mybatisplus.core.toolkit.support.SFunction<HrPersonMirror, T> getter,
                                         String existing, String incoming) {
        String a = existing == null ? "" : existing;
        String b = incoming == null ? "" : incoming;
        if (a.equals(b)) {
            return false;
        }
        uw.set(getter, incoming);
        return true;
    }
}
