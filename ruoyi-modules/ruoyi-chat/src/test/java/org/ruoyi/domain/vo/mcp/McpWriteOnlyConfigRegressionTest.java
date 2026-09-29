package org.ruoyi.domain.vo.mcp;

import cn.idev.excel.FastExcel;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.domain.bo.mcp.McpToolBo;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.service.mcp.impl.McpToolServiceImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * write-only 三层完备性回归门禁（Track E-Verify，主计划 §0.6）：
 * ① Jackson 序列化不含 configJson / authConfig（@JsonIgnore 层）；
 * ② Excel 导出不含两字段（@ExcelIgnoreUnannotated + 无 @ExcelProperty 层）；
 * ③ 编辑留空保留原值（applyWriteOnlyConfigPolicy）依赖①②成立——响应/导出都不回显，
 *    前端「空态卡 + 留空提交保留原值」（方案 a）才有意义。
 * 任何一层被后续改动破坏，本测试立即红。
 *
 * <p>命名备案：Track D 验证治理文档另见 {@code McpConfigWriteOnlyTest} 写法，
 * 以本类名 {@code McpWriteOnlyConfigRegressionTest} 为准（施工图 E-Verify 节）。
 */
@Tag("dev")
class McpWriteOnlyConfigRegressionTest {

    private static final String SECRET_MARKER = "SECRET-MARKER-8f3a1c";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpToolVo toolWithSecret() {
        McpToolVo vo = new McpToolVo();
        vo.setId(1L);
        vo.setName("probe-tool");
        vo.setDescription("probe");
        vo.setType("LOCAL");
        vo.setStatus("ENABLED");
        vo.setConfigJson("{\"command\":\"npx\",\"args\":[\"--token=" + SECRET_MARKER + "\"]}");
        vo.setCreateTime(new Date());
        vo.setUpdateTime(new Date());
        return vo;
    }

    private McpMarketVo marketWithSecret() {
        McpMarketVo vo = new McpMarketVo();
        vo.setId(1L);
        vo.setName("probe-market");
        vo.setUrl("https://example.invalid/mcp");
        vo.setAuthConfig("{\"apiKey\":\"" + SECRET_MARKER + "\"}");
        vo.setStatus("ENABLED");
        return vo;
    }

    @Test
    void jacksonSerializationOmitsWriteOnlyFields() throws Exception {
        String toolJson = objectMapper.writeValueAsString(toolWithSecret());
        assertThat(toolJson).doesNotContain("configJson").doesNotContain(SECRET_MARKER);

        String marketJson = objectMapper.writeValueAsString(marketWithSecret());
        assertThat(marketJson).doesNotContain("authConfig").doesNotContain(SECRET_MARKER);
    }

    @Test
    void excelExportOmitsWriteOnlyFields() throws Exception {
        // 导出构造与 McpToolController.export → ExcelUtil.exportExcel(list, "MCP工具",
        // McpToolVo.class, response) 同款头/行，单元格与列头双断言。
        assertWorkbookOmits(writeWorkbook("MCP工具", McpToolVo.class, toolWithSecret()),
            "configJson", "配置信息");
        // 导出契约同口径覆盖 McpMarketVo.authConfig（契约：两个 write-only 字段都不得出现在导出物）。
        assertWorkbookOmits(writeWorkbook("MCP市场", McpMarketVo.class, marketWithSecret()),
            "authConfig", "鉴权配置");
    }

    @Test
    void writeOnlyFieldsKeepAnnotationContract() throws Exception {
        assertThat(McpToolVo.class.getAnnotation(ExcelIgnoreUnannotated.class)).isNotNull();
        assertThat(McpMarketVo.class.getAnnotation(ExcelIgnoreUnannotated.class)).isNotNull();

        assertWriteOnlyField(McpToolVo.class, "configJson");
        assertWriteOnlyField(McpMarketVo.class, "authConfig");
    }

    /**
     * ④ 留空提交保留原值（方案 a 的服务端半区，主计划 U3 → D-G03 断言3）：
     * applyWriteOnlyConfigPolicy 在 configJson 空白（null/空串/纯空白）时必须把
     * update.configJson 置 null——MyBatis-Plus updateById 跳过 null 列 = 库内原值不被清，
     * 与前端「留空提交不带 configJson 键」成对构成红线；非空时原样覆写（替换配置生效）。
     * Track D §2.3 mutation 自证「留空分支改 setConfigJson("") → 断言必红」由本用例承接。
     * 注：该方法为包私有 static，本测试经反射调用（零生产代码改动）。
     */
    @Test
    void blankEditPreservesStoredConfig() throws Exception {
        Method policy = McpToolServiceImpl.class
            .getDeclaredMethod("applyWriteOnlyConfigPolicy", McpToolBo.class, McpTool.class);
        policy.setAccessible(true);

        for (String blank : new String[] {null, "", "   "}) {
            McpToolBo source = new McpToolBo();
            source.setConfigJson(blank);
            // 模拟 MapstructUtils.convert(bo) 已把用户提交值映射进 update
            McpTool update = new McpTool();
            update.setConfigJson("{\"command\":\"new-value\"}");
            policy.invoke(null, source, update);
            assertThat(update.getConfigJson())
                .as("留空提交（configJson=%s）必须置 null 以保留库内原值", blank)
                .isNull();
        }

        McpToolBo replace = new McpToolBo();
        replace.setConfigJson("{\"command\":\"npx\"}");
        McpTool update = new McpTool();
        update.setConfigJson(replace.getConfigJson());
        policy.invoke(null, replace, update);
        assertThat(update.getConfigJson())
            .as("非空提交（替换配置）必须原样覆写")
            .isEqualTo("{\"command\":\"npx\"}");
    }

    private List<Map<Integer, String>> writeWorkbook(String sheetName, Class<?> headType, Object row)
        throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcel.write(out)
            .head(headType)
            .sheet(sheetName)
            .doWrite(List.of(row));

        List<Map<Integer, String>> rows =
            FastExcel.read(new ByteArrayInputStream(out.toByteArray())).sheet().doReadSync();
        assertThat(rows).isNotEmpty();
        return rows;
    }

    private void assertWorkbookOmits(List<Map<Integer, String>> rows, String... forbidden) {
        StringBuilder headDump = new StringBuilder();
        for (Map<Integer, String> row : rows) {
            for (String cell : row.values()) {
                String text = cell == null ? "" : cell;
                headDump.append('|').append(text);
                assertThat(text)
                    .doesNotContain(SECRET_MARKER)
                    .doesNotContain(forbidden);
            }
        }
        // 列头面：导出表头不含 write-only 字段的展示名
        assertThat(headDump.toString()).doesNotContain(forbidden);
    }

    private void assertWriteOnlyField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertThat(field.getAnnotation(JsonIgnore.class))
            .as("%s.%s 必须保留 @JsonIgnore（响应不回显）", type.getSimpleName(), fieldName)
            .isNotNull();
        assertThat(field.getAnnotation(ExcelProperty.class))
            .as("%s.%s 不得挂 @ExcelProperty（导出不出现）", type.getSimpleName(), fieldName)
            .isNull();
    }
}
