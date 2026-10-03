package org.ruoyi.ipd.config;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class TenantRetirementScanTest {
    @Test void actualRetirementWithoutCurrentConsumerRemovesOnlyThatTable() {
        assertThat(TenantExcludesConsistencyTest.requiredDdlTables(
            Set.of("t_workflow_checkpoint", "projects"),
            "DROP TABLE IF EXISTS `t_workflow_checkpoint`;", Set.of()))
            .containsExactly("projects");
    }

    @Test void currentEntityOrMapperKeepsRetiredTableInMissingTenantGate() {
        var candidates = Set.of("t_workflow_checkpoint");
        for (String source : new String[]{"@TableName(\"t_workflow_checkpoint\")",
                "<select>SELECT * FROM t_workflow_checkpoint WHERE id = #{id}</select>"}) {
            var consumers = TenantExcludesConsistencyTest.consumerTables(source, candidates);
            assertThat(TenantExcludesConsistencyTest.requiredDdlTables(candidates,
                "DROP TABLE t_workflow_checkpoint;", consumers)).containsExactly("t_workflow_checkpoint");
        }
    }

    @Test void rollbackCommentsAreNotExecutedRetirements() {
        assertThat(TenantExcludesConsistencyTest.retiredTables(
            "-- rollback: DROP TABLE projects;\n/* DROP TABLE ai_documents; */\n"
                + "# DROP TABLE requirements;\nDROP TABLE IF EXISTS `t_workflow_checkpoint`;\n"
                + "DROP TABLE IF EXISTS `t_workflow_node`;"))
            .containsExactlyInAnyOrder("t_workflow_checkpoint", "t_workflow_node");
    }

    @Test void unregisteredNewBusinessTableRemainsRedEvenWithRetiredWorkflowTable() {
        var required = TenantExcludesConsistencyTest.requiredDdlTables(
            Set.of("projects", "new_business_table", "t_workflow_checkpoint"),
            "DROP TABLE t_workflow_checkpoint;", Set.of());
        required.removeAll(Set.of("projects"));
        assertThat(required).containsExactly("new_business_table");
    }

    @Test void substringOrUnrelatedDropDoesNotNarrowCoverage() {
        assertThat(TenantExcludesConsistencyTest.consumerTables("t_workflow_checkpoint_backup",
            Set.of("t_workflow_checkpoint"))).isEmpty();
        assertThat(TenantExcludesConsistencyTest.requiredDdlTables(Set.of("projects"),
            "DROP TABLE historical_other_table;", Set.of())).containsExactly("projects");
    }
}
