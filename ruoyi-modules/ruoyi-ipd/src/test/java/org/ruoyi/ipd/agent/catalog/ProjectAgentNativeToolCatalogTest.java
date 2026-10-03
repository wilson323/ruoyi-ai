package org.ruoyi.ipd.agent.catalog;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
class ProjectAgentNativeToolCatalogTest {
    @Test void exactOfficialFullProfileAndExecutionUnionPreserveAllNames() {
        assertThat(ProjectAgentNativeToolCatalog.IDS).hasSize(31).doesNotHaveDuplicates();
        assertThat(ProjectAgentNativeToolCatalog.IDS).containsExactly("read_file", "write_file", "edit_file",
            "grep_files", "glob_files", "list_files", "execute", "web_fetch", "web_search", "memory_get",
            "memory_search", "memory_save", "session_search", "session_list", "session_history", "wait_async_results",
            "agent_list", "agent_send", "agent_spawn", "task_cancel", "task_list", "task_output", "load_skill_through_path",
            "deliver_artifact", "plan_enter", "plan_exit", "plan_write", "propose_skill", "reset_equipped_tools", "skill_manage", "todo_write");
        assertThat(ProjectAgentToolCatalog.executionToolIds(List.of("project_knowledge_search", "read_file")))
            .hasSize(32).containsAll(ProjectAgentNativeToolCatalog.IDS).contains("project_knowledge_search");
        assertThat(ProjectAgentNativeToolCatalog.descriptor("wait_all")).isNull();
    }
    @Test void nativeEffectsAreNotReadOnlyAndProviderFailureNeverDisablesBusinessKnowledge() {
        var manifest=new CapabilityManifest(1,List.of(),List.of(new CapabilityManifest.ToolEntry("project_knowledge_search","Knowledge",true)),List.of());
        var checks=new HashMap<String,ProjectAgentNativeToolCatalog.Readiness>();
        ProjectAgentNativeToolCatalog.IDS.forEach(id->checks.put(id,new ProjectAgentNativeToolCatalog.Readiness(true,null)));
        checks.put("web_search",new ProjectAgentNativeToolCatalog.Readiness(false,"查询服务尚未配置"));
        var catalog=new ProjectAgentToolCatalog(manifest,ProjectAgentNativeToolCatalog.providers(Set.copyOf(ProjectAgentNativeToolCatalog.IDS),checks));
        assertThat(catalog.statuses()).hasSize(32);assertThat(catalog.status("web_search").available()).isFalse();
        assertThat(catalog.status("web_search").reason()).isEqualTo("查询服务尚未配置");
        assertThat(catalog.status("project_knowledge_search").available()).isTrue();
        for(String id:List.of("write_file","execute","web_fetch","memory_save","agent_spawn","plan_enter")) {
            assertThat(catalog.status(id).available()).as(id).isTrue();assertThat(catalog.status(id).readOnly()).as(id).isFalse();
        }
        assertThat(ProjectAgentToolCatalog.descriptor("write_file").hasCapability(ToolCapability.WRITE)).isTrue();
        assertThat(ProjectAgentToolCatalog.descriptor("execute").hasCapability(ToolCapability.EXECUTE)).isTrue();
        assertThat(ProjectAgentToolCatalog.descriptor("web_search").hasCapability(ToolCapability.NETWORK)).isTrue();
    }
    @Test void unregisteredUnverifiedUnknownAndNullReadinessStayExplicit() {
        var provider=ProjectAgentNativeToolCatalog.providers(Set.of("read_file"),Map.of("write_file",new ProjectAgentNativeToolCatalog.Readiness(true,null)));
        assertThat(provider.status("write_file").available()).isFalse();assertThat(provider.status("read_file").available()).isFalse();
        assertThat(provider.status("read_file").reason()).isNotBlank();
        var catalog=new ProjectAgentToolCatalog(new CapabilityManifest(1,List.of(),List.of(),List.of()),id->null);
        assertThat(catalog.status("read_file").available()).isFalse();assertThat(catalog.status("unknown").available()).isFalse();
        assertThat(catalog.statuses()).hasSize(31);
    }
}
