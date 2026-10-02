package org.ruoyi.service.vector.impl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class VectorAccessMetadataTest {
    @Test void legacyAndIncompleteOwnershipAreDenied() {
        var bo = new QueryVectorBo();
        assertFalse(VectorAccessMetadata.permits(Map.of(), bo));
        assertFalse(VectorAccessMetadata.permits(Map.of("scopeType", "PERSON", "sensitivity", "PUBLIC"), bo));
        assertTrue(VectorAccessMetadata.permits(Map.of("scopeType", "GLOBAL", "sensitivity", "PUBLIC"), bo));
    }
    @Test void sensitivityAndVisibilityMustBothMatchWithoutNumericIdLoss() {
        var bo = new QueryVectorBo();
        bo.applyBackendAccessFilters("PUBLIC", 9007199254740993L, List.of("GLOBAL"), null, null, List.of());
        assertTrue(VectorAccessMetadata.permits(Map.of("scopeType", "PERSON", "sensitivity", "PUBLIC", "ownerPersonId", "9007199254740993"), bo));
        assertFalse(VectorAccessMetadata.permits(Map.of("scopeType", "PERSON", "sensitivity", "INTERNAL", "ownerPersonId", "9007199254740993"), bo));
        assertFalse(VectorAccessMetadata.permits(Map.of("scopeType", "PERSON", "sensitivity", "PUBLIC", "ownerPersonId", "8"), bo));
        assertTrue(VectorAccessMetadata.milvusFilter(bo).contains("9007199254740993"));
        assertTrue(VectorAccessMetadata.milvusFilter(bo).contains(" or "));
    }
}
