package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.enums.KnowledgeSensitivity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 四刀之一（关键词通道契约）：searchByKeyword 的 MySQL FULLTEXT 粗召回
 * 必须与向量通道 payload filter 上下一致（实施方案 §3.1——否则混合检索 RRF
 * 的关键词路成为敏感级过滤旁路）。纯注解契约断言，不触库。
 * <p>
 * 契约点（Validator P0 修复后）：
 * 1) JOIN knowledge_info（sensitivity 为库级列，片段表无此列必须 JOIN）；
 * 2) sensitivityAllowed 非 null 时追加 ki.sensitivity IN (foreach 集合)——
 *    与 Weaviate 侧 ContainsAny 同「允许值集合」语义（集合由
 *    KnowledgeSensitivity#allowedValuesUpTo 单源展开，禁 &lt;= 字符串序比较：
 *    字典序与敏感级升序相反，曾致越权放大/误杀）；
 * 3) null 时不加谓词（空态：现网 knowledge_fragment 0 行，关键词路零行为变化）；
 * 4) abstract 主方法第 4 参 @Param("sensitivityAllowed") List&lt;String&gt;；
 * 5) default 兼容重载保留 String maxSensitivity 老签名（调用方零改动，
 *    展开单源委托，MyBatis 对 default 方法不注册 MappedStatement）。
 */
@Tag("dev")
class KnowledgeFragmentMapperContractTest {

    private static Method searchByKeywordSetForm() throws Exception {
        return KnowledgeFragmentMapper.class
            .getDeclaredMethod("searchByKeyword", Long.class, String.class, Integer.class, List.class);
    }

    private static String selectSql() throws Exception {
        Select select = searchByKeywordSetForm().getAnnotation(Select.class);
        assertNotNull(select, "searchByKeyword 须保留 @Select 注解契约");
        return String.join("", select.value());
    }

    @Test
    void keywordChannelJoinsKnowledgeInfo() throws Exception {
        String sql = selectSql();
        assertTrue(sql.contains("JOIN knowledge_info ki ON ki.id = kf.knowledge_id"),
            "敏感级为库级列，关键词路必须 JOIN knowledge_info，实际=" + sql);
    }

    @Test
    void sensitivityPredicateIsSetSemanticsInClause() throws Exception {
        String sql = selectSql();
        assertTrue(sql.contains("<if test="), "须有动态谓词守卫，实际=" + sql);
        assertTrue(sql.contains("sensitivityAllowed != null"), "null 时不得追加谓词（空态语义），实际=" + sql);
        assertTrue(sql.contains("ki.sensitivity IN"), "谓词须为 IN 集合语义（禁序比较），实际=" + sql);
        assertTrue(sql.contains("<foreach collection='sensitivityAllowed'"),
            "集合参数须经 foreach 参数化绑定（防注入），实际=" + sql);
        assertFalse(sql.contains("&lt;= #{maxSensitivity}"),
            "禁回退到 <= 字符串序比较（字典序 INTERNAL<PUBLIC<SECRET 与敏感级升序相反，Validator P0），实际=" + sql);
    }

    @Test
    void fourthParamIsAnnotatedSensitivityAllowedList() throws Exception {
        Parameter[] params = searchByKeywordSetForm().getParameters();
        assertEquals(4, params.length, "签名须为 4 参（原 3 参 + sensitivityAllowed），实际=" + params.length);
        Param annotation = params[3].getAnnotation(Param.class);
        assertNotNull(annotation, "第 4 参须带 @Param");
        assertEquals("sensitivityAllowed", annotation.value());
        assertEquals(List.class, params[3].getType(), "第 4 参须为 List<String> 允许值集合形态");
    }

    @Test
    void stringCapOverloadDelegatesWithSingleSourceExpansion() throws Exception {
        // 老签名兼容重载必须存在且为 default（MyBatis 不为 default 方法注册语句，
        // 调用方 KnowledgeRetrievalServiceImpl 的 String 透传零改动）；
        // 展开单源：集合值必须与 KnowledgeSensitivity#allowedNamesUpTo 完全一致
        Method overload = KnowledgeFragmentMapper.class
            .getDeclaredMethod("searchByKeyword", Long.class, String.class, Integer.class, String.class);
        assertTrue(overload.isDefault(), "String 上限形态须为 default 兼容重载");
        assertEquals(List.of("PUBLIC"), KnowledgeSensitivity.allowedNamesUpTo("PUBLIC"));
        assertEquals(List.of("PUBLIC", "INTERNAL"), KnowledgeSensitivity.allowedNamesUpTo("INTERNAL"));
        assertEquals(List.of("PUBLIC", "INTERNAL", "SECRET"), KnowledgeSensitivity.allowedNamesUpTo("SECRET"));
    }
}
