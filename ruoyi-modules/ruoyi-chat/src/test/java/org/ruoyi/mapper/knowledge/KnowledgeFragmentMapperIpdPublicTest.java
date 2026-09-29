package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无数据库时验证动态 SQL 的授权谓词和空选库语义；真实 MySQL 命中仍需单独验收。 */
@Tag("dev")
@DisplayName("IPD 公共知识片段 SQL：租户/公开性/附件状态同查")
class KnowledgeFragmentMapperIpdPublicTest {

    @Test
    void selectedIdsKeepAllAuthorizationPredicates() throws Exception {
        String sql = boundSql(List.of(7L, 8L));

        assertTrue(sql.contains("JOIN knowledge_info ki ON ki.id = kf.knowledge_id"));
        assertTrue(sql.contains("JOIN knowledge_attach ka ON ka.knowledge_id = ki.id"));
        assertTrue(sql.contains("ki.tenant_id = ?"));
        assertTrue(sql.contains("kf.tenant_id = ?"));
        assertTrue(sql.contains("ka.tenant_id = ?"));
        assertTrue(sql.contains("ka.status = 2"));
        assertTrue(sql.contains("ki.scope_type = 'GLOBAL'"));
        assertTrue(sql.contains("ki.sensitivity = 'PUBLIC'"));
        assertTrue(sql.contains("ki.share = 1"));
        assertTrue(sql.contains("MATCH (kf.content) AGAINST"));
        assertTrue(sql.contains("ki.id IN"));
        assertTrue(sql.contains("LIMIT ?"));
    }

    @Test
    void nullIdsMeanAllAuthorizedLibrariesButEmptyIdsReturnNoRows() throws Exception {
        String allPublic = boundSql(null);
        assertFalse(allPublic.contains("ki.id IN"));
        assertFalse(allPublic.contains("AND 1 = 0"));
        assertTrue(allPublic.contains("ki.scope_type = 'GLOBAL'"));

        String none = boundSql(List.of());
        assertTrue(none.contains("AND 1 = 0"));
    }

    private static String boundSql(List<Long> ids) throws Exception {
        Method method = KnowledgeFragmentMapper.class.getMethod("searchIpdPublic",
            Long.class, List.class, String.class, Integer.class);
        String script = String.join("", method.getAnnotation(Select.class).value());
        SqlSource source = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", 0L);
        parameters.put("knowledgeIds", ids);
        parameters.put("query", "问题");
        parameters.put("limit", 4);
        BoundSql bound = source.getBoundSql(parameters);
        return bound.getSql();
    }
}
