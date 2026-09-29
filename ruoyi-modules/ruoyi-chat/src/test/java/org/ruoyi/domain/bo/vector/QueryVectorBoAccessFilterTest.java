package org.ruoyi.domain.bo.vector;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.enums.KnowledgeSensitivity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 四刀之三（C4 防透传）：QueryVectorBo 的 6 个仅后端装配过滤参数
 * （maxSensitivity/personId/scopeTypes/groupId/projectId/ownerAgentIds）
 * 不得存在前端注入口。
 * <p>
 * 防护机制 = @Setter(AccessLevel.NONE)（无公开 setter，Jackson/Spring 绑定失效）
 * + 唯一写入口 {@code applyBackendAccessFilters}（枚举/字符集校验）。
 * 现网空态（knowledge_fragment 0 行、share=1 0 行）：参数全 null = 不加谓词 =
 * B1 前检索行为逐字节一致，属空态用例 nullMeansNoFilter。
 */
@Tag("dev")
class QueryVectorBoAccessFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void frontendJsonCannotInjectAccessFilters() throws Exception {
        String malicious = "{\"query\":\"q\",\"kid\":\"1\",\"maxSensitivity\":\"SECRET\","
            + "\"personId\":1,\"scopeTypes\":[\"GLOBAL\"],\"groupId\":2,\"projectId\":3,"
            + "\"ownerAgentIds\":[9]}";
        QueryVectorBo bo = MAPPER.readValue(malicious, QueryVectorBo.class);
        // 既有字段仍可绑定（防护不得扩大到旧契约）
        assertEquals("q", bo.getQuery());
        assertEquals("1", bo.getKid());
        // 6 个过滤参数前端注入一律不生效
        assertNull(bo.getMaxSensitivity(), "maxSensitivity 不得由前端 JSON 注入");
        assertNull(bo.getPersonId(), "personId 不得由前端 JSON 注入");
        assertNull(bo.getScopeTypes(), "scopeTypes 不得由前端 JSON 注入");
        assertNull(bo.getGroupId(), "groupId 不得由前端 JSON 注入");
        assertNull(bo.getProjectId(), "projectId 不得由前端 JSON 注入");
        assertNull(bo.getOwnerAgentIds(), "ownerAgentIds 不得由前端 JSON 注入");
    }

    @Test
    void accessFiltersHaveNoPublicSetters() throws Exception {
        List<String> guarded = List.of("setMaxSensitivity", "setPersonId", "setScopeTypes",
            "setGroupId", "setProjectId", "setOwnerAgentIds");
        for (Method method : QueryVectorBo.class.getMethods()) {
            // 结构断言：公开方法面不存在 6 个过滤参数的任何 setter（Lombok AccessLevel.NONE）
            assertTrue(!guarded.contains(method.getName()),
                "不得存在公开 setter: " + method.getName());
        }
        // 反向兜底：字段确实存在（防止改名为假绿）
        for (String field : List.of("maxSensitivity", "personId", "scopeTypes",
            "groupId", "projectId", "ownerAgentIds")) {
            assertNotEquals(null, QueryVectorBo.class.getDeclaredField(field));
        }
    }

    @Test
    void backendAssemblyNormalizesAndCopies() {
        QueryVectorBo bo = new QueryVectorBo();
        bo.applyBackendAccessFilters("internal", 5L, List.of("person ", "GLOBAL", "person"), 1L, 2L, List.of(9L));
        assertEquals("INTERNAL", bo.getMaxSensitivity(), "敏感级应归一为大写枚举名");
        assertEquals(List.of("PERSON", "GLOBAL"), bo.getScopeTypes(), "作用域应 trim+大写+去重");
        assertEquals(5L, bo.getPersonId());
        assertEquals(1L, bo.getGroupId());
        assertEquals(2L, bo.getProjectId());
        assertEquals(List.of(9L), bo.getOwnerAgentIds());
    }

    @Test
    void nullMeansNoFilter() {
        // 空态语义：全 null = 不启用任何谓词（B1 装配点不装配 → 运行态零行为变化）
        QueryVectorBo bo = new QueryVectorBo();
        bo.applyBackendAccessFilters(null, null, null, null, null, null);
        assertNull(bo.getMaxSensitivity());
        assertNull(bo.getPersonId());
        assertNull(bo.getScopeTypes());
        assertNull(bo.getGroupId());
        assertNull(bo.getProjectId());
        assertNull(bo.getOwnerAgentIds());
        // 空 ownerAgentIds / 纯空白 scopeTypes 同样归 null
        bo.applyBackendAccessFilters(" ", 1L, List.of("  "), 2L, 3L, List.of());
        assertNull(bo.getMaxSensitivity());
        assertNull(bo.getScopeTypes());
        assertNull(bo.getOwnerAgentIds());
    }

    @Test
    void illegalSensitivityIsRejected() {
        QueryVectorBo bo = new QueryVectorBo();
        ServiceException ex = assertThrows(ServiceException.class,
            () -> bo.applyBackendAccessFilters("CONFIDENTIAL", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("CONFIDENTIAL"), "报错应携带原值，实际=" + ex.getMessage());
    }

    @Test
    void illegalScopeCharsetIsRejected() {
        QueryVectorBo bo = new QueryVectorBo();
        // GraphQL where / SQL 注入面：小写可归一，但引号/运算符/空格非法
        assertThrows(ServiceException.class,
            () -> bo.applyBackendAccessFilters(null, null, List.of("group' OR '1'='1"), null, null, null));
        assertThrows(ServiceException.class,
            () -> bo.applyBackendAccessFilters(null, null, List.of("GLOBAL; DROP"), null, null, null));
    }

    @Test
    void copyOverloadSyncsAllFilters() {
        QueryVectorBo source = new QueryVectorBo();
        source.applyBackendAccessFilters("SECRET", 7L, List.of("AGENT"), 3L, 4L, List.of(11L, 12L));
        QueryVectorBo target = new QueryVectorBo();
        target.applyBackendAccessFilters(source);
        assertEquals("SECRET", target.getMaxSensitivity());
        assertEquals(7L, target.getPersonId());
        assertEquals(List.of("AGENT"), target.getScopeTypes());
        assertEquals(3L, target.getGroupId());
        assertEquals(4L, target.getProjectId());
        assertEquals(List.of(11L, 12L), target.getOwnerAgentIds());
    }

    @Test
    void sensitivityAllowedSetSemanticsHold() {
        // 集合语义（Validator P0 修复）：「≤上限」以允许值集合表达，禁字符串序比较——
        // 字典序 INTERNAL<PUBLIC<SECRET 与敏感级升序 PUBLIC<INTERNAL<SECRET 相反。
        assertEquals(List.of("PUBLIC"), KnowledgeSensitivity.allowedNamesUpTo("PUBLIC"),
            "cap=PUBLIC 仅允许 PUBLIC（不得放行 INTERNAL/SECRET）");
        assertEquals(List.of("PUBLIC", "INTERNAL"), KnowledgeSensitivity.allowedNamesUpTo("INTERNAL"),
            "cap=INTERNAL 允许 PUBLIC+INTERNAL（序比较会丢 PUBLIC 库）");
        assertEquals(List.of("PUBLIC", "INTERNAL", "SECRET"), KnowledgeSensitivity.allowedNamesUpTo("SECRET"),
            "cap=SECRET 允许全部三值");
        assertEquals(List.of("PUBLIC"), KnowledgeSensitivity.allowedNamesUpTo(" public "),
            "展开前做宽松归一（trim+大小写），与 parse 语义一致");
        // P0 根因锚点：字典序恰与敏感级升序相反——任何回到 <= 序比较的实现都会在此翻车
        assertTrue("INTERNAL".compareTo("PUBLIC") < 0,
            "锚点：'INTERNAL'<'PUBLIC'（字典序），故序比较不可表达敏感级升序");
        // 枚举声明序无关紧要，names 全集即白名单
        assertEquals(List.of("PUBLIC", "INTERNAL", "SECRET"), KnowledgeSensitivity.NAMES);
    }

    @Test
    void illegalSensitivityCapFailsFastAtSetExpansion() {
        // 集合展开的快速失败防线：上游 applyBackendAccessFilters 已收口枚举校验，
        // 展开方法对 null/空白/非法值宁可炸也不静默放行（fail-noisy 优于越权放大）
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSensitivity.allowedNamesUpTo(null));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSensitivity.allowedNamesUpTo(" "));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSensitivity.allowedNamesUpTo("CONFIDENTIAL"));
        // 枚举形态重载的分档边界
        assertEquals(List.of(org.ruoyi.enums.KnowledgeSensitivity.PUBLIC),
            org.ruoyi.enums.KnowledgeSensitivity.allowedValuesUpTo(org.ruoyi.enums.KnowledgeSensitivity.PUBLIC));
        assertEquals(java.util.List.of(org.ruoyi.enums.KnowledgeSensitivity.PUBLIC,
                org.ruoyi.enums.KnowledgeSensitivity.INTERNAL),
            org.ruoyi.enums.KnowledgeSensitivity.allowedValuesUpTo(org.ruoyi.enums.KnowledgeSensitivity.INTERNAL));
    }

    @Test
    void ownerAgentIdsAreDefensivelyCopied() throws Exception {
        QueryVectorBo bo = new QueryVectorBo();
        java.util.ArrayList<Long> mutable = new java.util.ArrayList<>(List.of(1L));
        bo.applyBackendAccessFilters(null, null, null, null, null, mutable);
        mutable.add(2L);
        assertEquals(List.of(1L), bo.getOwnerAgentIds(), "写入口须防御性拷贝，外部变更不得回渗");
        // 结构断言补充：字段保持私有（未因测试便利放开可见性）
        assertTrue(Modifier.isPrivate(QueryVectorBo.class.getDeclaredField("maxSensitivity").getModifiers()));
    }
}
