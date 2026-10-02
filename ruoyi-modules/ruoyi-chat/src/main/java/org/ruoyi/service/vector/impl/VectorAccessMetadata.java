package org.ruoyi.service.vector.impl;

import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.enums.KnowledgeSensitivity;
import java.util.*;

/** 同一权限谓词供官方向量客户端消费；缺失存量归属不得视作公开。 */
final class VectorAccessMetadata {
    static final List<String> SCOPES = List.of("GLOBAL", "GROUP", "PROJECT", "PERSON", "AGENT");
    static Map<String, List<String>> visibility(QueryVectorBo bo) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (bo.getScopeTypes() != null && !bo.getScopeTypes().isEmpty()) result.put("scopeType", bo.getScopeTypes());
        if (bo.getGroupId() != null) result.put("groupId", List.of(bo.getGroupId().toString()));
        if (bo.getProjectId() != null) result.put("projectId", List.of(bo.getProjectId().toString()));
        if (bo.getPersonId() != null) result.put("ownerPersonId", List.of(bo.getPersonId().toString()));
        if (bo.getOwnerAgentIds() != null && !bo.getOwnerAgentIds().isEmpty())
            result.put("ownerAgentId", bo.getOwnerAgentIds().stream().map(Object::toString).toList());
        return result;
    }
    static List<String> sensitivity(QueryVectorBo bo) {
        return bo.getMaxSensitivity() == null ? KnowledgeSensitivity.NAMES : KnowledgeSensitivity.allowedNamesUpTo(bo.getMaxSensitivity());
    }
    static boolean permits(Map<String, String> metadata, QueryVectorBo bo) {
        String scope = metadata.get("scopeType");
        if (scope == null || metadata.get("sensitivity") == null || !SCOPES.contains(scope) || !sensitivity(bo).contains(metadata.get("sensitivity"))) return false;
        String required = switch (scope) {
            case "GROUP" -> "groupId"; case "PROJECT" -> "projectId";
            case "PERSON" -> "ownerPersonId"; case "AGENT" -> "ownerAgentId"; default -> null;
        };
        if (required != null && (metadata.get(required) == null || metadata.get(required).isBlank())) return false;
        var visibility = visibility(bo);
        return visibility.isEmpty() || visibility.entrySet().stream().anyMatch(e -> e.getValue().contains(metadata.get(e.getKey())));
    }
    static String milvusFilter(QueryVectorBo bo) {
        List<String> must = new ArrayList<>();
        must.add(in("scopeType", SCOPES));
        must.add(in("sensitivity", sensitivity(bo)));
        var visibility = visibility(bo);
        if (!visibility.isEmpty()) must.add("(" + String.join(" or ", visibility.entrySet().stream().map(e -> in(e.getKey(), e.getValue())).toList()) + ")");
        return String.join(" and ", must);
    }
    private static String in(String key, List<String> values) {
        return "metadata[\"" + key + "\"] in " + new com.google.gson.Gson().toJson(values);
    }
}
