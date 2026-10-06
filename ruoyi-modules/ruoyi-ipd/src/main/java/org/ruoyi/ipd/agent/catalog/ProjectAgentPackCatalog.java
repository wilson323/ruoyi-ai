package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest.PackEntry;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped administrator pack overrides, read on each new catalog/planning request. */
public final class ProjectAgentPackCatalog {
    private final CapabilityManifest builtin;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProjectAgentPackCatalog(CapabilityManifest builtin, JdbcTemplate jdbc, ObjectMapper json) {
        this.builtin = builtin;
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PackEntry> packs(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("能力包租户必填");
        }
        var packs = new LinkedHashMap<String, PackEntry>();
        builtin.packs().forEach(p -> packs.put(key(p.code(), p.version()), p));
        var rows = jdbc.queryForList("SELECT id,code,version,name,description,stages,action_codes,status,del_flag "
            + "FROM ipd_capability_pack WHERE tenant_id=?", tenantId);
        for (var row : rows) {
            String code = required(row.get("code"), "能力包编码");
            String version = required(row.get("version"), "能力包版本");
            String key = key(code, version);
            if (!"ACTIVE".equals(row.get("status")) || !"0".equals(String.valueOf(row.get("del_flag")))) {
                packs.remove(key);
                continue;
            }
            List<String> stages = array(row.get("stages"));
            List<String> actions = array(row.get("action_codes"));
            for (String action : actions) {
                org.ruoyi.ipd.domain.ActionDef definition;
                try { definition = ActionCatalog.byCode(action); }
                catch (IllegalArgumentException e) { throw new IllegalStateException("能力包含未知或已退役动作：" + action, e); }
                if (!stages.contains(definition.stage())) {
                    throw new IllegalStateException("能力包动作与阶段不一致：" + code + "/" + action);
                }
            }
            var skills = new ArrayList<String>();
            var tools = new ArrayList<String>();
            var items = jdbc.queryForList("SELECT item_type,item_ref,item_version,sha256 FROM ipd_capability_pack_item "
                + "WHERE tenant_id=? AND pack_id=? AND del_flag='0' ORDER BY sort_order,id", tenantId, row.get("id"));
            for (var item : items) {
                String ref = required(item.get("item_ref"), "能力包条目引用");
                switch (required(item.get("item_type"), "能力包条目类型")) {
                    case "SKILL" -> {
                        var locked = builtin.skill(ref).orElseThrow(() ->
                            new IllegalStateException("能力包技能尚未发布：" + ref));
                        if (!locked.sha256().equals(item.get("sha256"))
                            || !locked.version().equals(item.get("item_version"))) {
                            throw new IllegalStateException("能力包技能版本或摘要不一致：" + ref);
                        }
                        skills.add(ref);
                    }
                    case "TOOL" -> {
                        if (builtin.tool(ref).isEmpty()) throw new IllegalStateException("能力包工具未登记：" + ref);
                        tools.add(ref);
                    }
                    default -> throw new IllegalStateException("能力包条目类型非法：" + item.get("item_type"));
                }
            }
            for (String action : actions) {
                // 2026-10-06 项目维度后：包目录一致性只校验全局默认绑定（project_id=0），
                // 保证目录装配不被项目级行干扰；项目级绑定在运行时由 planner 校验（resolve 项目级优先 + loadSkills ∈ 包技能）。
                var mappings = jdbc.queryForList("SELECT skill_names FROM ipd_action_skill_map WHERE tenant_id=? AND action_code=? AND project_id=0 AND del_flag='0'", tenantId, action);
                if (mappings.size() != 1 || mappings.get(0).get("skill_names") == null) {
                    throw new IllegalStateException("能力包动作未绑定执行技能：" + action);
                }
                List<String> bound = array(mappings.get(0).get("skill_names"));
                if (bound.isEmpty() || !skills.containsAll(bound)) {
                    throw new IllegalStateException("能力包缺少动作绑定的技能：" + action);
                }
            }
            packs.put(key, new PackEntry(code, version, required(row.get("name"), "能力包名称"),
                (String) row.get("description"), stages, actions, List.copyOf(skills), List.copyOf(tools)));
        }
        return List.copyOf(packs.values());
    }

    public Optional<PackEntry> pack(String tenantId, String code, String version) {
        return packs(tenantId).stream().filter(p -> code.equals(p.code()) && version.equals(p.version())).findFirst();
    }

    private List<String> array(Object value) {
        try {
            List<String> values = json.readValue(value.toString(), new TypeReference<List<String>>() { });
            if (values == null || values.stream().anyMatch(v -> v == null || v.isBlank())) {
                throw new IllegalArgumentException("能力包数组含空项");
            }
            return List.copyOf(values);
        } catch (Exception e) {
            throw new IllegalStateException("能力包配置数组非法", e);
        }
    }

    private static String required(Object value, String label) {
        if (value == null || value.toString().isBlank()) throw new IllegalStateException(label + "缺失");
        return value.toString();
    }

    private static String key(String code, String version) { return code + "@" + version; }
}
