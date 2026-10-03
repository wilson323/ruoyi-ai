package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SopTemplateInstanceMapper;
import org.ruoyi.ipd.mapper.SopTemplateMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-3.3 / BR-IPD-SOP-03 实例快照 JSON 结构：meta + actionList + responsibilityMatrix + phaseDeadlineMap，
 * 按模板 category 过滤动作深度（DEEP_MGMT→仅 DEEP / LIGHT_MGMT→仅 LIGHT / MIXED→全量），
 * 责任矩阵键集合必须与 actionList 的 code 集合逐字一致。
 *
 * <p>快照是「在研项目与模板迭代解耦」的唯一载体——结构漂移（缺键 / 键集合错位 / 深度过滤失效）
 * 会让在研项目读到错误的动作清单，因此本类逐键钉住结构。
 */
@Tag("dev")
class SopTemplateSnapshotJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SopTemplateMapper templateMapper;
    private SopTemplateInstanceMapper instanceMapper;
    private SopTemplateService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 100L);
    private static final long TEMPLATE_ID = 10L;
    private static final long PROJECT_ID = 200L;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SopTemplate.class);
        TableInfoHelper.initTableInfo(assistant, SopTemplateInstance.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);

        templateMapper = mock(SopTemplateMapper.class);
        instanceMapper = mock(SopTemplateInstanceMapper.class);
        IAuditLogService auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(instanceMapper.insert(any(SopTemplateInstance.class))).thenAnswer(inv -> {
            SopTemplateInstance i = inv.getArgument(0);
            if (i.getId() == null) i.setId(System.nanoTime());
            return 1;
        });
        service = new SopTemplateService(templateMapper, instanceMapper, auditLogService,
            mock(ProjectMemberMapper.class), mock(ProjectMapper.class));
    }

    /** 以给定分类实例化一次模板，返回解析后的快照 JSON。 */
    private JsonNode snapshotFor(String category) throws Exception {
        SopTemplate t = SopTemplate.builder()
            .id(TEMPLATE_ID).actionCode("C01").title("t").content("c")
            .templateCode("SOP-CONCEPT-DEEP").templateName("深管概念")
            .version(3L).status(SopTemplate.Status.PUBLISHED)
            .category(category).delFlag("0").build();
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(t);
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());

        SopTemplateInstance fresh = service.instantiate(TEMPLATE_ID, PROJECT_ID, ADMIN);
        return JSON.readTree(fresh.getSnapshotJson());
    }

    private static long catalogCountByDepth(String depth) {
        return ActionCatalog.ALL.stream().filter(d -> depth.equals(d.depth())).count();
    }

    @Test
    @DisplayName("快照 meta 携带模板身份与版本（在研项目据此追溯模板来源）")
    void snapshot_metaCarriesTemplateIdentity() throws Exception {
        JsonNode meta = snapshotFor(SopTemplate.Category.MIXED).get("meta");

        assertThat(meta.get("templateCode").asText()).isEqualTo("SOP-CONCEPT-DEEP");
        assertThat(meta.get("templateName").asText()).isEqualTo("深管概念");
        assertThat(meta.get("version").asLong()).isEqualTo(3L);
        assertThat(meta.get("category").asText()).isEqualTo(SopTemplate.Category.MIXED);
        assertThat(meta.get("phaseDeadlineMap").isObject()).isTrue();
    }

    @Test
    @DisplayName("快照四个顶层键齐备，且 actionList 为数组")
    void snapshot_topLevelShape() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.MIXED);

        assertThat(root.has("meta")).isTrue();
        assertThat(root.has("actionList")).isTrue();
        assertThat(root.has("responsibilityMatrix")).isTrue();
        assertThat(root.has("phaseDeadlineMap")).isTrue();
        assertThat(root.get("actionList").isArray()).isTrue();
        assertThat(root.get("responsibilityMatrix").isObject()).isTrue();
    }

    @Test
    @DisplayName("MIXED 分类不过滤：actionList 覆盖动作目录全量")
    void snapshot_mixedKeepsAllActions() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.MIXED);

        assertThat(root.get("actionList")).hasSize(ActionCatalog.ALL.size());
        assertThat(root.get("meta").get("actionCount").asInt())
            .isEqualTo(ActionCatalog.ALL.size());
    }

    @Test
    @DisplayName("DEEP_MGMT 只保留 DEEP 深度动作（轻管动作不得混入）")
    void snapshot_deepMgmtFiltersToDeepOnly() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.DEEP_MGMT);

        assertThat(root.get("actionList")).hasSize((int) catalogCountByDepth("DEEP"));
        for (JsonNode row : root.get("actionList")) {
            assertThat(row.get("depth").asText()).isEqualTo("DEEP");
        }
    }

    @Test
    @DisplayName("LIGHT_MGMT 只保留 LIGHT 深度动作（深管动作不得混入）")
    void snapshot_lightMgmtFiltersToLightOnly() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.LIGHT_MGMT);

        assertThat(root.get("actionList")).hasSize((int) catalogCountByDepth("LIGHT"));
        for (JsonNode row : root.get("actionList")) {
            assertThat(row.get("depth").asText()).isEqualTo("LIGHT");
        }
    }

    @Test
    @DisplayName("动作目录只含 DEEP/LIGHT 两档，且两档之和等于全量（深度过滤不会漏动作）")
    void catalog_depthPartitionIsTotal() {
        long deep = catalogCountByDepth("DEEP");
        long light = catalogCountByDepth("LIGHT");

        assertThat(deep + light).isEqualTo(ActionCatalog.ALL.size());
        assertThat(deep).isPositive();
        assertThat(light).isPositive();
    }

    @Test
    @DisplayName("未知分类（null）等同于不过滤，保留全量动作")
    void snapshot_nullCategoryKeepsAllActions() throws Exception {
        JsonNode root = snapshotFor(null);

        assertThat(root.get("actionList")).hasSize(ActionCatalog.ALL.size());
        assertThat(root.get("meta").get("category").isNull()).isTrue();
    }

    @Test
    @DisplayName("actionList 每行含 code/name/stage/ownerRole/depth 五字段且非空")
    void snapshot_actionRowsHaveRequiredFields() throws Exception {
        JsonNode rows = snapshotFor(SopTemplate.Category.MIXED).get("actionList");

        for (JsonNode row : rows) {
            assertThat(row.get("code").asText()).isNotBlank();
            assertThat(row.get("name").asText()).isNotBlank();
            assertThat(row.get("stage").asText()).isNotBlank();
            assertThat(row.get("ownerRole").asText()).isNotBlank();
            assertThat(row.get("depth").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("责任矩阵键集合与 actionList 的 code 集合逐字一致（不多不少）")
    void snapshot_responsibilityMatrixKeysMatchActionList() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.MIXED);

        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode row : root.get("actionList")) {
            codes.add(row.get("code").asText());
        }
        Set<String> matrixKeys = new LinkedHashSet<>();
        for (Iterator<String> it = root.get("responsibilityMatrix").fieldNames(); it.hasNext(); ) {
            matrixKeys.add(it.next());
        }

        assertThat(matrixKeys).isEqualTo(codes);
    }

    @Test
    @DisplayName("责任矩阵取值等于该动作 ownerRole（矩阵与动作行不得错位）")
    void snapshot_responsibilityMatrixValuesMatchOwnerRole() throws Exception {
        JsonNode root = snapshotFor(SopTemplate.Category.DEEP_MGMT);
        Map<String, String> expected = new LinkedHashMap<>();
        for (ActionDef def : ActionCatalog.ALL) {
            if ("DEEP".equals(def.depth())) {
                expected.put(def.code(), def.ownerRole());
            }
        }

        JsonNode matrix = root.get("responsibilityMatrix");
        assertThat(matrix.size()).isEqualTo(expected.size());
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(matrix.get(e.getKey()).asText())
                .as("动作 %s 的责任角色", e.getKey())
                .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("phaseDeadlineMap 六个阶段齐备且天数与规范一致")
    void snapshot_phaseDeadlineMapHasSixPhases() throws Exception {
        JsonNode map = snapshotFor(SopTemplate.Category.MIXED).get("phaseDeadlineMap");

        assertThat(map.size()).isEqualTo(6);
        assertThat(map.get("CONCEPT").asInt()).isEqualTo(30);
        assertThat(map.get("PLAN").asInt()).isEqualTo(60);
        assertThat(map.get("DEV").asInt()).isEqualTo(120);
        assertThat(map.get("VALID").asInt()).isEqualTo(90);
        assertThat(map.get("LAUNCH").asInt()).isEqualTo(30);
        assertThat(map.get("LIFECYCLE").asInt()).isEqualTo(180);
    }

    @Test
    @DisplayName("meta.actionCount 与 actionList 实际长度一致（过滤后计数不得失同步）")
    void snapshot_metaActionCountMatchesList() throws Exception {
        for (String category : new String[]{SopTemplate.Category.DEEP_MGMT,
            SopTemplate.Category.LIGHT_MGMT, SopTemplate.Category.MIXED}) {
            JsonNode root = snapshotFor(category);
            assertThat(root.get("meta").get("actionCount").asInt())
                .as("分类 %s 的 meta.actionCount", category)
                .isEqualTo(root.get("actionList").size());
        }
    }
}
