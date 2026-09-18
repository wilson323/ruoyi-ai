package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * PM 人员目录（P0 域#2；对齐 ZK-IPD 原型 GET /api/pm-directory）。
 * 顶栏/招募/项目空间/协作圈选人下拉的统一数据源（含组名、序列、等级）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PmDirectoryController {

    private final PersonMapper personMapper;
    private final ProductGroupMapper productGroupMapper;

    /** 在职（ACTIVE 且非 MOCK）人员目录：id/姓名/工号/角色/等级/所属组。
     * 2026-09-18 补丁：R33 撞车接管验收 P1-1，实测项目移交接任人下拉出现 Mock-QA-SYNC-20260910B
     * （该账号也是 ACTIVE 状态，被原 .eq("ACTIVE") 过滤漏过）。
     * 加 .ne("MOCK") 排除测试种子账号；配套真库 SQL 见 docs/script/sql/update/2026-09-18-pm-directory-mock-filter/。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/pm-directory")
    public ApiV1Response<Map<String, Object>> directory() {
        List<Person> people = personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getAccountStatus, "ACTIVE")
// R113-A: 保留 main 状态过滤 + R46-A3 治本 前缀过滤(双保险)
            .ne(Person::getAccountStatus, "MOCK")
            // R46-A3 治本: 排除 Mock 测试数据 (name 前缀 Mock- 或 username 前缀 u_QA-SYNC-)
            .notLike(Person::getName, "Mock-%")
            .notLike(Person::getUsername, "u_QA-SYNC-%")
            .orderByAsc(Person::getId));
        Map<Long, String> groupNames = productGroupMapper.selectList(null).stream()
            .filter(g -> g.getGroupName() != null)
            .collect(Collectors.toMap(ProductGroup::getId, ProductGroup::getGroupName, (a, b) -> a));

        List<Map<String, Object>> directory = new ArrayList<>();
        for (Person p : people) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("employeeNo", p.getEmployeeNo());
            m.put("personType", p.getPersonType());
            m.put("level", p.getLevel());
            m.put("groupId", p.getGroupId());
            m.put("groupName", p.getGroupId() != null ? groupNames.get(p.getGroupId()) : null);
            directory.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", directory);
        result.put("total", directory.size());
        return ApiV1Response.ok(result);
    }
}
