package org.ruoyi.service.vector.impl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class MilvusPayloadSensitivityTest {
    @Test void patchPreservesFullRowAndOwnershipWithExactVector() {
        var metadata = new com.google.gson.JsonObject();
        metadata.addProperty("ownerPersonId", "9007199254740993");
        metadata.addProperty("scopeType", "PERSON");
        metadata.addProperty("sensitivity", "PUBLIC");
        metadata.addProperty("fid", "fragment");
        var fields = Map.<String, Object>of("id", "original-id", "text", "body", "vector", List.of(0.125f, -0.75f),
            "metadata", metadata, "extraField", "preserved");
        var row = MilvusVectorStoreStrategy.patchSensitivity(fields, "SECRET");
        assertEquals("original-id", row.get("id").getAsString());
        assertEquals("body", row.get("text").getAsString());
        assertEquals("preserved", row.get("extraField").getAsString());
        assertEquals(new com.google.gson.Gson().toJsonTree(fields.get("vector")), row.get("vector"));
        assertEquals("9007199254740993", row.getAsJsonObject("metadata").get("ownerPersonId").getAsString());
        assertEquals("fragment", row.getAsJsonObject("metadata").get("fid").getAsString());
        assertEquals("SECRET", row.getAsJsonObject("metadata").get("sensitivity").getAsString());
        assertEquals("PUBLIC", metadata.get("sensitivity").getAsString());
        var permissions = new HashMap<String, String>();
        row.getAsJsonObject("metadata").entrySet().forEach(e -> permissions.put(e.getKey(), e.getValue().getAsString()));
        var bo = new QueryVectorBo();
        bo.applyBackendAccessFilters("PUBLIC", 9007199254740993L, null, null, null, null);
        assertFalse(VectorAccessMetadata.permits(permissions, bo));
    }
    @Test void legacyMetadataCanReceiveSensitivityWithoutInventingAnOwner() {
        var row = MilvusVectorStoreStrategy.patchSensitivity(Map.of("id", "id", "text", "body", "vector", List.of(1f),
            "metadata", "{\"fid\":\"fragment\"}"), "INTERNAL");
        assertFalse(row.getAsJsonObject("metadata").has("ownerPersonId"));
        assertFalse(VectorAccessMetadata.permits(Map.of("sensitivity", "INTERNAL"), new QueryVectorBo()));
        assertThrows(org.ruoyi.common.core.exception.ServiceException.class,
            () -> MilvusVectorStoreStrategy.patchSensitivity(Map.of("id", "id"), "PUBLIC"));
    }
}
