package org.ruoyi.ipd.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.HrOrganization;
import org.ruoyi.ipd.hr.HrResponse.OrgRow;
import org.ruoyi.ipd.mapper.HrOrganizationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * HR 组织落库服务（R149-v1 D6）。
 *
 * <p>接收 HR §3.2.2 给的 org 列表，按 {@code orgeh} 幂等 upsert 到 hr_organizations 表：
 * <ul>
 *   <li>新组织 INSERT（orgeh 不存在）</li>
 *   <li>已存在组织 UPDATE（任何字段差异 + last_sync_at + trigger_by）</li>
 *   <li>差异检测：stext/shortName/parentOrgeh/bmfzr/zbmcj/hrDelFlag/expirationFlag</li>
 * </ul>
 *
 * <p>装配闸门：{@code @ConditionalOnProperty(ipd.hr.enabled=true)}（与 RealHrSyncAdapter 一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ipd.hr.enabled", havingValue = "true")
public class HrOrganizationIngestService {

    private final HrOrganizationMapper mapper;

    /** 批量入库：返回实际 upsert 数（INSERT + UPDATE 之和）。 */
    public int ingest(List<OrgRow> rows, String triggerBy) {
        int upserts = 0;
        Date now = new Date();
        for (OrgRow row : rows) {
            if (row == null || row.orgeh == null || row.orgeh.isBlank()) {
                continue;
            }
            try {
                if (upsertRow(row, triggerBy, now)) upserts++;
            } catch (Exception e) {
                log.warn("R149-v1 org upsert FAILED: orgeh={} err={}", row.orgeh, e.getMessage());
            }
        }
        log.info("R149-v1 org ingest: total={} upserts={}", rows.size(), upserts);
        return upserts;
    }

    /** 行级 upsert。 */
    private boolean upsertRow(OrgRow row, String triggerBy, Date now) {
        HrOrganization existing = mapper.selectOne(new LambdaQueryWrapper<HrOrganization>()
            .eq(HrOrganization::getOrgeh, row.orgeh)
            .last("LIMIT 1"));
        if (existing == null) {
            HrOrganization newcomer = HrOrganization.builder()
                .orgeh(row.orgeh)
                .stext(row.stext)
                .shortName(row.shortName)
                .begda(row.begda)
                .endda(row.endda)
                .parentOrgeh(row.orgehPup)
                .bmfzr(row.bmfzr)
                .zbmcj(row.zbmcj)
                .hrDelFlag(row.delFlag)
                .expirationFlag(row.expirationflag)
                .lastSyncAt(now)
                .triggerBy(triggerBy)
                .delFlag("0")
                .build();
            mapper.insert(newcomer);
            log.debug("R149-v1 org INSERT: orgeh={} stext={}", row.orgeh, row.stext);
            return true;
        }
        boolean changed = false;
        LambdaUpdateWrapper<HrOrganization> uw = new LambdaUpdateWrapper<HrOrganization>()
            .eq(HrOrganization::getOrgeh, row.orgeh);
        if (diff(existing.getStext(), row.stext)) { uw.set(HrOrganization::getStext, row.stext); changed = true; }
        if (diff(existing.getShortName(), row.shortName)) { uw.set(HrOrganization::getShortName, row.shortName); changed = true; }
        if (diff(existing.getBegda(), row.begda)) { uw.set(HrOrganization::getBegda, row.begda); changed = true; }
        if (diff(existing.getEndda(), row.endda)) { uw.set(HrOrganization::getEndda, row.endda); changed = true; }
        if (diff(existing.getParentOrgeh(), row.orgehPup)) { uw.set(HrOrganization::getParentOrgeh, row.orgehPup); changed = true; }
        if (diff(existing.getBmfzr(), row.bmfzr)) { uw.set(HrOrganization::getBmfzr, row.bmfzr); changed = true; }
        if (diff(existing.getZbmcj(), row.zbmcj)) { uw.set(HrOrganization::getZbmcj, row.zbmcj); changed = true; }
        if (diff(existing.getHrDelFlag(), row.delFlag)) { uw.set(HrOrganization::getHrDelFlag, row.delFlag); changed = true; }
        if (diff(existing.getExpirationFlag(), row.expirationflag)) { uw.set(HrOrganization::getExpirationFlag, row.expirationflag); changed = true; }
        if (changed) {
            uw.set(HrOrganization::getLastSyncAt, now);
            uw.set(HrOrganization::getTriggerBy, triggerBy);
            mapper.update(null, uw);
            log.debug("R149-v1 org UPDATE: orgeh={}", row.orgeh);
            return true;
        }
        return false;
    }

    private static boolean diff(String a, String b) {
        if (a == null) return b != null && !b.isBlank();
        return !a.equals(b == null ? "" : b);
    }
}
