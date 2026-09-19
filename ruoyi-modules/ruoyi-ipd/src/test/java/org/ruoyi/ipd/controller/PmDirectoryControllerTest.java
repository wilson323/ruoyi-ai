package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-F PmDirectoryController 单测（GET /pm-directory P1-1 R33 撞车接管验收）。
 *
 * <p>覆盖 2 维度（件 3 必查）：
 * <ol>
 *   <li>主路径：mapper 返回纯 ACTIVE 人员（已过滤掉 MOCK）→ controller 正常组装 directory 并含 groupName</li>
 *   <li>边界：mapper 空 → 返 total=0，directory=[] 不抛</li>
 * </ol>
 *
 * <p>P1-1 背景：原实现仅 .eq("ACTIVE")，Mock-QA-SYNC-20260910B 因状态也是 ACTIVE 被错误返回到
 * 项目移交接任人下拉。补丁加 .ne("MOCK") + 真库打 MOCK 标（SQL 已 apply）。
 *
 * <p>契约测试（wrapper 必须含 ne MOCK）受 MyBatis-Plus lambda cache 硬约束，纯 Mockito 单测
 * 渲染 SQL 会崩——此类契约留给 dev profile 的集成测试 {@code IpdPermissionIntegrationTest}
 * + 真库 SQL 断言兜底。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class PmDirectoryControllerTest {

    @Mock
    private PersonMapper personMapper;

    @Mock
    private ProductGroupMapper productGroupMapper;

    @InjectMocks
    private PmDirectoryController controller;

    /* ====================== 1. 主路径：返回 ACTIVE 人员，组名映射 ====================== */

    @Test
    @DisplayName("[W4-F-1] directory 主路径：mock 已过滤 ACTIVE+非 MOCK，controller 正确组装 + groupName 联表")
    void directory_buildsDirectoryWithGroupNames() {
        // 模拟 mapper 已按 SQL 过滤：只返 ACTIVE + 非 MOCK
        Person activeReal = buildPerson(101L, "张三", "Z001", "MARKET_PM", "P5", 10L, "ACTIVE");
        when(personMapper.selectList(any())).thenReturn(List.of(activeReal));
        ProductGroup g = ProductGroup.builder().id(10L).groupName("产品A组").build();
        when(productGroupMapper.selectList(null)).thenReturn(List.of(g));

        ApiV1Response<Map<String, Object>> resp = controller.directory();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directory =
            (List<Map<String, Object>>) resp.getData().get("directory");
        assertThat(directory).hasSize(1);
        Map<String, Object> row = directory.get(0);
        assertThat(row.get("id")).isEqualTo(101L);
        assertThat(row.get("name")).isEqualTo("张三");
        assertThat(row.get("employeeNo")).isEqualTo("Z001");
        assertThat(row.get("personType")).isEqualTo("MARKET_PM");
        assertThat(row.get("level")).isEqualTo("P5");
        assertThat(row.get("groupId")).isEqualTo(10L);
        assertThat(row.get("groupName")).isEqualTo("产品A组");
        assertThat(resp.getData().get("total")).isEqualTo(1);
    }

    /* ====================== 2. 边界：空 ====================== */

    @Test
    @DisplayName("[W4-F-2] directory 空：mapper 无数据 → total=0, directory=[] 不抛")
    void directory_emptyResult() {
        when(personMapper.selectList(any())).thenReturn(List.of());
        when(productGroupMapper.selectList(null)).thenReturn(List.of());

        ApiV1Response<Map<String, Object>> resp = controller.directory();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().get("total")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> directory =
            (List<Map<String, Object>>) resp.getData().get("directory");
        assertThat(directory).isEmpty();
    }

    /* ====================== 3. 主路径双调用：personMapper + productGroupMapper 各 1 次 ====================== */

    @Test
    @DisplayName("[W4-F-3] directory 调用 personMapper.selectList 1 次 + productGroupMapper.selectList 1 次")
    void directory_callsBothMappersOnce() {
        when(personMapper.selectList(any())).thenReturn(List.of());
        when(productGroupMapper.selectList(null)).thenReturn(List.of());

        controller.directory();

        verify(personMapper, times(1)).selectList(any());
        verify(productGroupMapper, times(1)).selectList(null);
    }

    /* ====================== 测试工具 ====================== */

    private Person buildPerson(Long id, String name, String employeeNo, String personType,
                               String level, Long groupId, String accountStatus) {
        return Person.builder()
            .id(id)
            .name(name)
            .employeeNo(employeeNo)
            .personType(personType)
            .level(level)
            .groupId(groupId)
            .accountStatus(accountStatus)
            .build();
    }
}
