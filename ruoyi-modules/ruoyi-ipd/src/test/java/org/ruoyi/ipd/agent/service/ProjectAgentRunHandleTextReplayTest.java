package org.ruoyi.ipd.agent.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real RunHandle with controlled event-store faults; not a database acceptance test. */
class ProjectAgentRunHandleTextReplayTest {
 private IpdAgentRunEvent event(long seq,String payload){return IpdAgentRunEvent.builder().seq(seq).eventType("TEXT_DELTA").payload(payload).build();}
 private AgentRunStore store(){var s=mock(AgentRunStore.class);when(s.findRun(9L)).thenReturn(Optional.of(IpdAgentRun.builder().id(9L).status("RUNNING").build()));when(s.transition(anyLong(),any(),any(),any(),any())).thenReturn(true);when(s.appendEvent(any())).thenReturn(true);return s;}
 private ProjectAgentRunHandle handle(AgentRunStore s){return new ProjectAgentRunHandle(IpdAgentRun.builder().id(9L).status("RUNNING").build(),s,new ObjectMapper(),()->1000L,()->{});}
 @Test void emptyReplacementClearsPriorText(){var s=store();when(s.listEvents(anyLong(),anyLong(),anyInt())).thenReturn(List.of(event(1,"{\"text\":\"old\"}"),event(2,"{\"text\":\"\",\"replace\":true}")));var h=handle(s);h.onText("tail");h.restoreFlushedText();assertEquals("tail",h.assistantText());}
 @Test void nonAdvancingFullPageIsRejectedBeforeDuplicateConsumption(){var s=store();var page=new ArrayList<IpdAgentRunEvent>();for(int i=1;i<=500;i++)page.add(event(i,"{\"text\":\"x\"}"));when(s.listEvents(anyLong(),anyLong(),anyInt())).thenReturn(page);var h=handle(s);h.onText("tail");assertThrows(IllegalStateException.class,h::restoreFlushedText);assertEquals("tail",h.assistantText());}
 @Test void unreadableHistoryDoesNotCommitSuccessAndCanRetryAfterReadRecovers(){var s=store();when(s.listEvents(anyLong(),anyLong(),anyInt())).thenThrow(new IllegalStateException("fixture read failure"));var h=handle(s);h.onText("tail");assertFalse(h.finish(AgentRunStatus.SUCCEEDED,null));assertFalse(h.isClosed());verify(s,never()).transition(anyLong(),any(),any(),any(),any());verify(s,never()).appendEvent(any());doReturn(List.of(event(1,"{\"text\":\"prior\"}"))).when(s).listEvents(anyLong(),anyLong(),anyInt());assertTrue(h.finish(AgentRunStatus.SUCCEEDED,null));assertEquals("priortail",h.assistantText());}
 @Test void malformedTextPayloadCannotDropASectionAndCommitSuccess(){var s=store();when(s.listEvents(anyLong(),anyLong(),anyInt())).thenReturn(List.of(event(1,"{bad")));var h=handle(s);h.onText("tail");assertFalse(h.finish(AgentRunStatus.SUCCEEDED,null));verify(s,never()).transition(anyLong(),any(),any(),any(),any());}
 @Test void unflushedTailAndReplacementRemainIdempotent(){var s=store();when(s.listEvents(anyLong(),anyLong(),anyInt())).thenReturn(List.of(event(1,"{\"text\":\"old\"}"),event(2,"{\"text\":\"new\",\"replace\":true}")));var h=handle(s);h.onText("tail");h.restoreFlushedText();h.restoreFlushedText();assertEquals("newtail",h.assistantText());}
 @Test void cancellationDoesNotRequireUnreadableHistory(){var s=store();when(s.listEvents(anyLong(),anyLong(),anyInt())).thenThrow(new IllegalStateException("fixture read failure"));var h=handle(s);assertTrue(h.finish(AgentRunStatus.CANCELLED,null));assertTrue(h.isClosed());verify(s,never()).listEvents(anyLong(),anyLong(),anyInt());}
}
