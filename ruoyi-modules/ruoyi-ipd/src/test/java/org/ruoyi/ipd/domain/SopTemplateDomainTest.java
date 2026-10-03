package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3.3 SOP 模板/实例领域对象契约：状态与分类字面量对齐 DDL、软删除契约（G-02）、
 * 表名与主键策略、列名与映射，以及 contentLen 必须是非持久化列（{@code @TableField(exist=false)}）——
 * 若该注解被摘掉，MyBatis-Plus 会尝试写不存在的 {@code content_len} 列，插入直接报 SQL 错误。
 */
@Tag("dev")
class SopTemplateDomainTest {

    private TableInfo templateInfo;
    private TableInfo instanceInfo;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SopTemplate.class);
        TableInfoHelper.initTableInfo(assistant, SopTemplateInstance.class);
        templateInfo = TableInfoHelper.getTableInfo(SopTemplate.class);
        instanceInfo = TableInfoHelper.getTableInfo(SopTemplateInstance.class);
    }

    private static Map<String, String> columnsOf(TableInfo info) {
        return info.getFieldList().stream().collect(Collectors.toMap(
            TableFieldInfo::getProperty, TableFieldInfo::getColumn, (a, b) -> a));
    }

    private static Set<String> propertiesOf(TableInfo info) {
        return info.getFieldList().stream()
            .map(TableFieldInfo::getProperty)
            .collect(Collectors.toSet());
    }

    // ========== 字面量与 DDL 对齐 ==========

    @Test
    @DisplayName("SopTemplate 状态字面量：DRAFT / PUBLISHED / ARCHIVED")
    void templateStatusLiterals() {
        assertThat(SopTemplate.Status.DRAFT).isEqualTo("DRAFT");
        assertThat(SopTemplate.Status.PUBLISHED).isEqualTo("PUBLISHED");
        assertThat(SopTemplate.Status.ARCHIVED).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("SopTemplate 分类字面量：DEEP_MGMT / LIGHT_MGMT / MIXED")
    void templateCategoryLiterals() {
        assertThat(SopTemplate.Category.DEEP_MGMT).isEqualTo("DEEP_MGMT");
        assertThat(SopTemplate.Category.LIGHT_MGMT).isEqualTo("LIGHT_MGMT");
        assertThat(SopTemplate.Category.MIXED).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("SopTemplateInstance 状态字面量：ACTIVE / SUPERSEDED / ARCHIVED")
    void instanceStatusLiterals() {
        assertThat(SopTemplateInstance.Status.ACTIVE).isEqualTo("ACTIVE");
        assertThat(SopTemplateInstance.Status.SUPERSEDED).isEqualTo("SUPERSEDED");
        assertThat(SopTemplateInstance.Status.ARCHIVED).isEqualTo("ARCHIVED");
    }

    // ========== 软删除契约（G-02：禁物理 DELETE） ==========

    @Test
    @DisplayName("两实体均实现 SoftDeletable（软删除执行器按接口调用，缺实现即删除流程失效）")
    void bothEntitiesImplementSoftDeletable() {
        assertThat(new SopTemplate()).isInstanceOf(SoftDeletable.class);
        assertThat(new SopTemplateInstance()).isInstanceOf(SoftDeletable.class);
    }

    @Test
    @DisplayName("setDelFlag 契约签名是 void 且可读回（覆盖 Lombok 链式 setter，匹配接口）")
    void setDelFlagSignatureAndRoundTrip() throws Exception {
        Method m = SopTemplate.class.getMethod("setDelFlag", String.class);
        assertThat(m.getReturnType()).isEqualTo(void.class);

        SopTemplate t = SopTemplate.builder().id(7L).delFlag("0").build();
        m.invoke(t, "1");
        assertThat(t.getDelFlag()).isEqualTo("1");
        assertThat(t.getId()).isEqualTo(7L);

        Method mi = SopTemplateInstance.class.getMethod("setDelFlag", String.class);
        assertThat(mi.getReturnType()).isEqualTo(void.class);
        SopTemplateInstance inst = SopTemplateInstance.builder().id(8L).delFlag("0").build();
        mi.invoke(inst, "1");
        assertThat(inst.getDelFlag()).isEqualTo("1");
        assertThat(inst.getId()).isEqualTo(8L);
    }

    // ========== 表名 / 主键 / 列映射 ==========

    @Test
    @DisplayName("表名固定为 sop_templates / sop_template_instances")
    void tableNames() {
        assertThat(SopTemplate.class.getAnnotation(TableName.class).value()).isEqualTo("sop_templates");
        assertThat(SopTemplateInstance.class.getAnnotation(TableName.class).value())
            .isEqualTo("sop_template_instances");
        assertThat(templateInfo.getTableName()).isEqualTo("sop_templates");
        assertThat(instanceInfo.getTableName()).isEqualTo("sop_template_instances");
    }

    @Test
    @DisplayName("主键策略为 ASSIGN_ID（雪花），键属性 id")
    void idStrategy() {
        assertThat(templateInfo.getIdType()).isEqualTo(IdType.ASSIGN_ID);
        assertThat(templateInfo.getKeyProperty()).isEqualTo("id");
        assertThat(instanceInfo.getIdType()).isEqualTo(IdType.ASSIGN_ID);
        assertThat(instanceInfo.getKeyProperty()).isEqualTo("id");
    }

    @Test
    @DisplayName("del_flag 列名显式固定（软删除执行器按列名写 0/1）")
    void delFlagColumnIsExplicit() {
        assertThat(columnsOf(templateInfo)).containsEntry("delFlag", "del_flag");
        assertThat(columnsOf(instanceInfo)).containsEntry("delFlag", "del_flag");
    }

    @Test
    @DisplayName("业务列按驼峰转下划线落库（action_code / effective_from / effective_to）")
    void businessColumnsAreSnakeCase() {
        Map<String, String> cols = columnsOf(templateInfo);
        assertThat(cols).containsEntry("actionCode", "action_code");
        assertThat(cols).containsEntry("effectiveFrom", "effective_from");
        assertThat(cols).containsEntry("effectiveTo", "effective_to");
        assertThat(cols).containsEntry("templateCode", "template_code");
        assertThat(cols).containsEntry("createdBy", "created_by");
    }

    @Test
    @DisplayName("实例业务列按驼峰转下划线落库（instance_version / snapshot_json / instantiated_at）")
    void instanceColumnsAreSnakeCase() {
        Map<String, String> cols = columnsOf(instanceInfo);
        assertThat(cols).containsEntry("templateId", "template_id");
        assertThat(cols).containsEntry("projectId", "project_id");
        assertThat(cols).containsEntry("instanceVersion", "instance_version");
        assertThat(cols).containsEntry("snapshotJson", "snapshot_json");
        assertThat(cols).containsEntry("instantiatedAt", "instantiated_at");
        assertThat(cols).containsEntry("instantiatedBy", "instantiated_by");
    }

    @Test
    @DisplayName("contentLen 是非持久化列（@TableField(exist=false)），正文 content 才是落库列")
    void contentLenIsNotPersisted() {
        Set<String> props = propertiesOf(templateInfo);

        assertThat(props)
            .as("contentLen 是 CHAR_LENGTH 计算列，必须带 @TableField(exist=false)，"
                + "否则 MP 会尝试写入不存在的 content_len 列")
            .doesNotContain("contentLen");
        assertThat(props).contains("content", "title", "actionCode", "version",
            "status", "category", "effectiveFrom", "effectiveTo", "delFlag", "tenantId");
    }

    @Test
    @DisplayName("两实体均继承 BaseEntity，具备 createBy/updateBy/createTime/updateTime 审计列")
    void entitiesExtendBaseEntity() {
        assertThat(SopTemplate.class.getSuperclass().getName())
            .isEqualTo("org.ruoyi.common.mybatis.core.domain.BaseEntity");
        assertThat(SopTemplateInstance.class.getSuperclass().getName())
            .isEqualTo("org.ruoyi.common.mybatis.core.domain.BaseEntity");

        assertThat(propertiesOf(templateInfo))
            .contains("createBy", "createTime", "updateBy", "updateTime");
        assertThat(propertiesOf(instanceInfo))
            .contains("createBy", "createTime", "updateBy", "updateTime");
    }

    // ========== 构造器与等值 ==========

    @Test
    @DisplayName("builder 全字段往返")
    void builderRoundTripsFields() {
        SopTemplate t = SopTemplate.builder()
            .id(1L).templateCode("SOP-1").templateName("N").description("D")
            .actionCode("C01").title("标题").content("正文").version(2L)
            .status(SopTemplate.Status.DRAFT).category(SopTemplate.Category.DEEP_MGMT)
            .createdBy("1").delFlag("0").tenantId("T-1").build();

        assertThat(t.getId()).isEqualTo(1L);
        assertThat(t.getActionCode()).isEqualTo("C01");
        assertThat(t.getVersion()).isEqualTo(2L);
        assertThat(t.getStatus()).isEqualTo(SopTemplate.Status.DRAFT);
        assertThat(t.getCategory()).isEqualTo(SopTemplate.Category.DEEP_MGMT);
        assertThat(t.getTenantId()).isEqualTo("T-1");
    }

    @Test
    @DisplayName("等值比较覆盖自身业务字段：同值相等，title 不同则不等")
    void equalsCoversBusinessFields() {
        Function<String, SopTemplate> make = title -> SopTemplate.builder()
            .id(1L).actionCode("C01").title(title).version(1L)
            .status(SopTemplate.Status.DRAFT).delFlag("0").build();

        assertThat(make.apply("同标题")).isEqualTo(make.apply("同标题"));
        assertThat(make.apply("标题A")).isNotEqualTo(make.apply("标题B"));
    }
}
