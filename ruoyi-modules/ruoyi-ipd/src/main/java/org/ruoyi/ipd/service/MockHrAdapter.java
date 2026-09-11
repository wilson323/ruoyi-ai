package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock HR 适配器（仅 dev profile 装配；P2-2.2 真源切换前的开发/联调注入点）。
 *
 * <p>R30 生产就绪收紧（2026-09-11）：原先 @Component 无 profile 隔离，生产环境任意用户可经
 * POST /person-sync/jobs 触发本适配器向 persons 表写入 Mock-xxx L3 MARKET_PM 假人，
 * 污染津贴/KPI 真实核算。现加 {@code @Profile("dev")}：dev（含本机 ipd-local,dev）照常装配；
 * 生产无 dev profile ⇒ bean 不装配 ⇒ {@code PersonSyncService.processor==null} ⇒
 * attempt 显式抛 IllegalStateException fail-closed。
 *
 * <p>实现 {@link PersonSyncService.SyncProcessor}，以 Spring bean 形式被
 * {@code PersonSyncService} 的 {@code @Autowired(required=false)} processor 字段自动装配，
 * 消除「processor 未配置 → submit 抛 IllegalStateException」的开发阻断：提交同步任务即可
 * 按 employeeNo 幂等 upsert persons 表（新人插入、已存在视为同步成功）。
 *
 * <p>SEC 边界不变：单测环境无 Spring，processor 仍为 null，未注入即拒绝的旁路关闭逻辑
 * 由 {@code PersonSyncSecurityScenarioTest} 继续锁定；真 HR API 就绪后以真适配器 bean
 * 替换本类（同接口同装配路径，且不得再限定 dev），业务代码零改动。
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class MockHrAdapter implements PersonSyncService.SyncProcessor {

    private final PersonMapper personMapper;

    @Override
    public void process(PersonSyncService.SyncJob job) {
        Person p = personMapper.selectOne(new LambdaQueryWrapper<Person>()
            .eq(Person::getEmployeeNo, job.employeeNo)
            .last("LIMIT 1"));
        if (p != null) {
            // 幂等 upsert：工号已存在即同步成功，不重复插入
            return;
        }
        Person newcomer = Person.builder()
            .employeeNo(job.employeeNo)
            .name("Mock-" + job.employeeNo)
            .personType("MARKET_PM")
            .level("L3")
            .employmentStatus(PersonService.EM_ACTIVE)
            .accountStatus(PersonService.AC_ACTIVE)
            .username("u_" + job.employeeNo)
            .delFlag("0")
            .build();
        personMapper.insert(newcomer);
    }
}
