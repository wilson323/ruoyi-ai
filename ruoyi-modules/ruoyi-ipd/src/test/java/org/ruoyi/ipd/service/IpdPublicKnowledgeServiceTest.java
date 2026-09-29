package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@DisplayName("IPD 副驾公共知识：可信租户映射与有界检索")
class IpdPublicKnowledgeServiceTest {

    private final KnowledgeFragmentMapper mapper = mock(KnowledgeFragmentMapper.class);
    private final IpdPublicKnowledgeService service = new IpdPublicKnowledgeService(mapper);

    @Test
    void defaultTenantSearchesAllPublicLibrariesAndReturnsSourceRefs() {
        when(mapper.searchIpdPublic(eq(0L), eq(null), eq("知识怎么使用"), eq(4)))
            .thenReturn(List.of(fragment(7L, "可引用的公开资料"), fragment(7L, "第二片段"),
                fragment(8L, "另一公共库")));

        var context = service.retrieve("000000", null, "知识怎么使用");

        assertTrue(context.hasContent());
        assertTrue(context.block().contains("可引用的公开资料"));
        assertEquals(List.of("knowledge.public:7", "knowledge.public:8"), context.sourceRefs());
        verify(mapper).searchIpdPublic(0L, null, "知识怎么使用", 4);
    }

    @Test
    void selectedIdsOnlyNarrowScopeAndEmptySelectionSkipsQuery() {
        service.retrieve("000000", List.of("7", "7", "8"), "问题");
        verify(mapper).searchIpdPublic(0L, List.of(7L, 8L), "问题", 4);

        var empty = service.retrieve("000000", List.of(), "问题");
        assertFalse(empty.hasContent());
        verify(mapper, never()).searchIpdPublic(eq(0L), eq(List.of()), any(), anyInt());
    }

    @Test
    void unsupportedPersonTenantNeverQueriesKnowledgeTables() {
        assertFalse(service.retrieve("tenant-a", null, "问题").hasContent());
        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> service.retrieve("tenant-a", List.of("7"), "问题"));
        assertEquals(ApiV1ErrorCode.FORBIDDEN, error.getErrorCode());
        verify(mapper, never()).searchIpdPublic(any(), any(), any(), any());
    }

    @Test
    void invalidSelectedIdIsRejectedBeforeQuery() {
        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> service.retrieve("000000", List.of("-1"), "问题"));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(mapper, never()).searchIpdPublic(any(), any(), any(), any());
    }

    @Test
    void invalidDecimalFormsAndOverflowAreRejected() {
        for (String raw : List.of("0", "01", "7.0", "7e0", "+7", " 7", "9223372036854775808")) {
            IpdBusinessException error = assertThrows(IpdBusinessException.class,
                () -> service.retrieve("000000", List.of(raw), "问题"), raw);
            assertEquals(ApiV1ErrorCode.PARAM_INVALID, error.getErrorCode(), raw);
        }
        verify(mapper, never()).searchIpdPublic(any(), any(), any(), any());
    }

    @Test
    void jsonKnowledgeIdsMustBeStrings() throws JsonProcessingException {
        ObjectMapper json = new ObjectMapper();
        AiCopilotReq accepted = json.readValue("{\"message\":\"问题\",\"knowledgeIds\":[\"7\"]}",
            AiCopilotReq.class);
        assertEquals(List.of("7"), accepted.knowledgeIds());
        for (String value : List.of("7", "7.0", "7e0")) {
            assertThrows(JsonProcessingException.class,
                () -> json.readValue("{\"message\":\"问题\",\"knowledgeIds\":[" + value + "]}",
                    AiCopilotReq.class), value);
        }
    }

    @Test
    void mapperFailureDoesNotSupplyUnverifiedContent() {
        when(mapper.searchIpdPublic(eq(0L), any(), eq("问题"), eq(4)))
            .thenThrow(new IllegalStateException("db unavailable"));

        assertFalse(service.retrieve("000000", null, "问题").hasContent());
        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> service.retrieve("000000", List.of("7"), "问题"));
        assertEquals(ApiV1ErrorCode.INTERNAL_ERROR, error.getErrorCode());
    }

    private static KnowledgeFragmentVo fragment(Long kid, String content) {
        KnowledgeFragmentVo fragment = new KnowledgeFragmentVo();
        fragment.setKnowledgeId(kid);
        fragment.setContent(content);
        return fragment;
    }
}
