package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Tag("dev")
class AiDocumentGeneratedRevisionTest {
    private final AiDocumentMapper mapper = mock(AiDocumentMapper.class);
    private final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    private final AiDocumentService service = new AiDocumentService(mapper);
    private final IpdActor actor = new IpdActor(9L, "alice", "MARKET_PM", 1L);
    private AiDocument arrange() {
        service.setProjectAccess(access);
        when(access.requireVisible(actor, 100L)).thenReturn("000000");
        AiDocument head = AiDocument.builder().id(10L).projectId(100L).docType("PRD")
            .versionNo(1).status("REJECTED").content("original").build();
        when(mapper.selectChain(10L)).thenReturn(List.of(head));
        when(mapper.lockVersion(10L)).thenReturn(head);
        return head;
    }
    @Test void rejectedAiReworkAppendsOriginalChainAndKeepsActualUsage() {
        AiDocument previous = arrange();
        AiDocument next = service.reviseGeneratedAuthorized(actor, 100L, "PRD", 10L, 10L,
            "new", "reworked", "actual-model", 12, 34);
        assertThat(next.getParentVersionId()).isEqualTo(10L);
        assertThat(next.getVersionNo()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo("GENERATED");
        assertThat(next.getModel()).isEqualTo("actual-model");
        assertThat(next.getTokenPrompt()).isEqualTo(12);
        assertThat(next.getTokenCompletion()).isEqualTo(34);
        assertThat(previous.getStatus()).isEqualTo("REJECTED");
        assertThat(previous.getContent()).isEqualTo("original");
        verify(mapper).insert(next);
    }
    @Test void staleBaseCannotCreateSecondChild() {
        arrange();
        AiDocument child = AiDocument.builder().id(11L).projectId(100L).docType("PRD")
            .parentVersionId(10L).versionNo(2).status("GENERATED").build();
        when(mapper.lockChild(10L)).thenReturn(child);
        assertThatThrownBy(() -> service.reviseGeneratedAuthorized(actor,100L,"PRD",10L,10L,
            "new","body","model",null,null)).isInstanceOf(IpdBusinessException.class);
        verify(mapper,never()).insert(any(AiDocument.class));
    }
    @Test void wrongProjectOrDocumentTypeCannotAttachToChain() {
        arrange();
        assertThatThrownBy(() -> service.reviseGeneratedAuthorized(actor,100L,"MRD",10L,10L,
            "new","body","model",null,null)).isInstanceOf(IpdBusinessException.class);
        verify(mapper,never()).insert(any(AiDocument.class));
    }
}
