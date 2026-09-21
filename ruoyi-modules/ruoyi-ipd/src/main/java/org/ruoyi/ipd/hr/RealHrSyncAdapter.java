package org.ruoyi.ipd.hr;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.hr.HrResponse.OrgRow;
import org.ruoyi.ipd.hr.HrResponse.PersonRow;
import org.ruoyi.ipd.hr.HrResponse.SyncBody;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.service.PersonService;
import org.ruoyi.ipd.service.PersonSyncService;
import org.ruoyi.ipd.service.PersonSyncService.FailureKind;
import org.ruoyi.ipd.service.PersonSyncService.SyncFailureException;
import org.ruoyi.ipd.service.PersonSyncService.SyncJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 真源 HR 适配器（R149-v1 D1；FA-HR-Sync A4→D1 进化；P2-2.2 真源切换的实现端）。
 *
 * <p>实现 {@link PersonSyncService.SyncProcessor}（R149 v0.2 防双轨原则：复用既有 PersonSyncService
 * 的 processor 注入点 + person_sync_jobs 台账，不再单独 hr_sync_runs）：
 * <ul>
 *   <li>{@link #process(SyncJob)} 按 {@code job.employeeNo} 调 HR 真源，按 employeeNo
 *       幂等 upsert {@code persons} 表</li>
 *   <li>{@link #syncAll(String)} / {@link #syncOne(String, String)} 给 HrSyncJob / Controller 直接调</li>
 * </ul>
 *
 * <p><b>装配闸门</b>：{@code @ConditionalOnProperty(ipd.hr.enabled=true)}，
 * 配合既有 {@code MockHrAdapter} {@code @Profile("dev")} 形成隔离：
 * <ul>
 *   <li>dev profile + hr.enabled=false → Mock 装配（开发联调，Mock 数据入 persons）</li>
 *   <li>dev profile + hr.enabled=true → Real 装配（开发联调真源；走 ipd-local 真凭据）</li>
 *   <li>prod profile + hr.enabled=true → Real 装配（生产真源）</li>
 *   <li>prod profile + hr.enabled=false（默认）→ 都不装配 → processor=null → fail-closed</li>
 * </ul>
 *
 * <p><b>登录契约</b>（R149 v0.2 用户决策 1+2）：username = employeeNo（PERNR），默认密码 = employeeNo
 * 经 PasswordEncoder.encode 写入 password_hash + must_change_pwd='1' 强制首次登录改密。
 * 已存在 persons 行的 password_hash 不覆盖（保留用户改密后的 hash）。
 *
 * <p><b>防双轨</b>（R149 v0.2 用户决策 3）：不修改 persons.person_type（HR 同步后 person_type
 * 仍按 IPD 既有规则由业务侧维护）；HR §3.2.1 的 orgeh/deptname/positionname 字段因 persons 表无对应
 * 列，**不写 persons**（避免 ALTER persons 表破坏 v3 TS-06 全字段契约），仅落 hr_organizations 全公司组织树。
 * 已存在 persons 行的 {@code groupId}（关联 IPD product_groups）保留不动，由业务侧按需手动绑定。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ipd.hr.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RealHrSyncAdapter implements PersonSyncService.SyncProcessor {

    private final HrApiClient hrApiClient;
    private final PersonMapper personMapper;
    private final HrOrganizationIngestService orgIngestService;

    /** processor.process 入口（被 PersonSyncService.attempt 在 synchronized(job) 内调用）。 */
    @Override
    public void process(SyncJob job) {
        try {
            UpsertResult r = upsertOne(job.employeeNo, "PERSON_SYNC_JOB");
            log.info("R149-v1 process OK: jobId={} employeeNo={} result={}",
                job.jobId, job.employeeNo, r);
        } catch (HrApiException e) {
            if (e.isPermanent()) {
                throw new SyncFailureException(FailureKind.PERMANENT,
                    "HR 永久性失败: " + e.getMessage());
            }
            throw new SyncFailureException(FailureKind.TRANSIENT,
                "HR 临时失败: " + e.getMessage());
        }
    }

    /** HR 全量同步：调真源拿全公司人员 + 组织 → 全表 upsert。 */
    public SyncStats syncAll(String triggerBy) {
        long start = System.currentTimeMillis();
        SyncBody body = SyncBody.full();
        log.info("R149-v1 syncAll start: triggerBy={}", triggerBy);

        List<PersonRow> persons = hrApiClient.fetchUserInfo(body);
        List<OrgRow> orgs = hrApiClient.fetchOrganizationInfo(body);

        int personUpserts = 0;
        int personSkips = 0;
        int personFailures = 0;
        for (PersonRow row : persons) {
            try {
                UpsertResult r = upsertRow(row, triggerBy);
                if (r.created()) personUpserts++; else personSkips++;
            } catch (Exception e) {
                personFailures++;
                log.warn("R149-v1 person upsert FAILED: pernr={} err={}",
                    row.pernr, e.getMessage());
            }
        }
        int orgUpserts = orgIngestService.ingest(orgs, triggerBy);
        long costMs = System.currentTimeMillis() - start;
        SyncStats stats = new SyncStats(persons.size(), personUpserts, personSkips, personFailures,
            orgs.size(), orgUpserts, costMs);
        log.info("R149-v1 syncAll done: {}", stats);
        return stats;
    }

    /** 单人同步：按 PERNR 调一次，按 employeeNo 幂等 upsert persons。 */
    public SyncStats syncOne(String employeeNo, String triggerBy) {
        long start = System.currentTimeMillis();
        SyncBody body = SyncBody.single(employeeNo);
        log.info("R149-v1 syncOne start: employeeNo={} triggerBy={}", employeeNo, triggerBy);
        List<PersonRow> rows = hrApiClient.fetchUserInfo(body);
        if (rows.isEmpty()) {
            log.warn("R149-v1 syncOne: HR returned empty for employeeNo={}", employeeNo);
            return new SyncStats(0, 0, 0, 0, 0, 0, System.currentTimeMillis() - start);
        }
        UpsertResult r = upsertRow(rows.get(0), triggerBy);
        long costMs = System.currentTimeMillis() - start;
        SyncStats stats = new SyncStats(1, r.created() ? 1 : 0, r.created() ? 0 : 1, 0,
            0, 0, costMs);
        log.info("R149-v1 syncOne done: {}", stats);
        return stats;
    }

    /** 公开 upsert：被 controller/job 调（无需 SyncJob 包装）。 */
    public UpsertResult upsertOne(String employeeNo, String triggerBy) {
        SyncBody body = SyncBody.single(employeeNo);
        List<PersonRow> rows = hrApiClient.fetchUserInfo(body);
        if (rows.isEmpty()) {
            throw new HrApiException(HrApiException.PERMANENT, "HR-EMPTY",
                "HR 平台无该工号人员: " + employeeNo, null);
        }
        return upsertRow(rows.get(0), triggerBy);
    }

    /** 行级 upsert（employeeNo 主键；不覆盖 password_hash；person_type 不修改；groupId 不改）。 */
    private UpsertResult upsertRow(PersonRow row, String triggerBy) {
        Person existing = personMapper.selectOne(new LambdaQueryWrapper<Person>()
            .eq(Person::getEmployeeNo, row.pernr)
            .last("LIMIT 1"));
        if (existing == null) {
            Person newcomer = Person.builder()
                .employeeNo(row.pernr)
                .name(safeName(row))
                .personType("STAFF")
                .level(safeLevel(row))
                .employmentStatus(employmentStatusOf(row))
                .accountStatus(PersonService.AC_ACTIVE)
                .username(row.pernr)
                .passwordHash(BCrypt.hashpw(row.pernr, BCrypt.gensalt(10)))
                .mustChangePwd("1")
                .delFlag("0")
                .remark("HR_SYNC:" + triggerBy)
                .build();
            personMapper.insert(newcomer);
            log.info("R149-v1 person INSERT: pernr={} name={} triggerBy={}",
                newcomer.getEmployeeNo(), newcomer.getName(), triggerBy);
            return new UpsertResult(true, true, newcomer.getId(), newcomer.getName());
        }
        boolean changed = false;
        LambdaUpdateWrapper<Person> uw = new LambdaUpdateWrapper<Person>()
            .eq(Person::getEmployeeNo, row.pernr);
        if (different(existing.getName(), safeName(row))) { uw.set(Person::getName, safeName(row)); changed = true; }
        if (different(existing.getEmploymentStatus(), employmentStatusOf(row))) {
            uw.set(Person::getEmploymentStatus, employmentStatusOf(row));
            changed = true;
        }
        if (different(existing.getLevel(), safeLevel(row))) { uw.set(Person::getLevel, safeLevel(row)); changed = true; }
        if (changed) {
            uw.set(Person::getRemark, "HR_SYNC:" + triggerBy);
            personMapper.update(null, uw);
            log.info("R149-v1 person UPDATE: pernr={} triggerBy={}", row.pernr, triggerBy);
        } else {
            log.debug("R149-v1 person SKIP (no diff): pernr={}", row.pernr);
        }
        return new UpsertResult(false, changed, existing.getId(), existing.getName());
    }

    private static String safeName(PersonRow row) {
        String n = (row.basicInfo != null) ? row.basicInfo.nachn : null;
        return (n == null || n.isBlank()) ? "HR-" + row.pernr : n.trim();
    }

    private static String safeLevel(PersonRow row) {
        String lv = (row.basicInfo != null) ? row.basicInfo.positiongrade : null;
        return (lv == null || lv.isBlank()) ? "L1" : lv.trim();
    }

    /** HR 离职判定：stat2=0 或 leaveFlag=X ⇒ RESIGNED；否则 ACTIVE。 */
    private static String employmentStatusOf(PersonRow row) {
        if (row.basicInfo == null) return PersonService.EM_ACTIVE;
        String stat2 = row.basicInfo.stat2;
        String leaveFlag = row.basicInfo.leaveFlag;
        if ("0".equals(stat2) || "X".equalsIgnoreCase(leaveFlag)) {
            return PersonService.EM_RESIGNED;
        }
        return PersonService.EM_ACTIVE;
    }

    private static boolean different(Object a, Object b) {
        return a == null ? b != null : !a.equals(b);
    }

    /** 行级 upsert 结果。 */
    public record UpsertResult(boolean created, boolean changed, Long personId, String name) { }

    /** 同步统计（同步日志 + 看板 last-runs 用）。 */
    public record SyncStats(int personsFetched, int personsUpserted, int personsSkipped,
                            int failures, int orgsFetched, int orgsUpserted, long costMs) { }
}
