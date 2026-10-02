package org.ruoyi.workflow.workflow;

import org.junit.jupiter.api.*;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class WfStateParallelInputTest {
    private AbstractWfNode completed(String id,String text) {
        AbstractWfNode node=mock(AbstractWfNode.class);WorkflowNode definition=new WorkflowNode();definition.setUuid(id);
        WfNodeState state=new WfNodeState();state.setOutputs(new ArrayList<>(List.of(NodeIOData.createByText("output","",text))));
        when(node.getNode()).thenReturn(definition);when(node.getState()).thenReturn(state);return node;
    }
    @Test void parallelBranchesReceiveIndependentCopiesOfActualPredecessorOutput() throws Exception {
        WfState state=new WfState(null,List.of(),"run",1L,null,null,null);
        var parent=completed("A","parent");state.getCompletedNodes().add(parent);
        state.addEdge("A","B");state.addEdge("A","C");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<NodeIOData>> results=new ArrayList<>();
            for(String name:List.of("B","C"))results.add(pool.submit(()-> {
                state.beginNode(name);try {var input=state.getLatestOutputs().get(0);input.setName(name);return input;}finally{state.endNode();}
            }));
            assertEquals("B",results.get(0).get().getName());assertEquals("C",results.get(1).get().getName());
            assertNotSame(results.get(0).get(),results.get(1).get());assertEquals("output",parent.getState().getOutputs().get(0).getName());
        } finally {pool.shutdownNow();}
    }
    @Test void joinUsesBothPredecessorsRatherThanUnrelatedLastCompletedNode() {
        WfState state=new WfState(null,List.of(),"run",1L,null,null,null);
        state.getCompletedNodes().add(completed("B","left"));state.getCompletedNodes().add(completed("C","right"));state.getCompletedNodes().add(completed("unrelated","wrong"));
        state.addEdge("B","D");state.addEdge("C","D");state.beginNode("D");
        try {assertEquals(List.of("left","right"),state.getLatestOutputs().stream().map(NodeIOData::valueToString).toList());}
        finally {state.endNode();}
    }
    @Test void referenceInputsCannotMutatePersistedUpstreamData() {
        WfState state=new WfState(null,List.of(),"run",1L,null,null,null);var parent=completed("A","data");state.getCompletedNodes().add(parent);
        state.getIOByNodeUuid("A").get(0).setName("changed");
        assertEquals("output",parent.getState().getOutputs().get(0).getName());
    }
    @Test void nodeMetadataNestedCollectionsAreIndependent() {
        Map<String,Object> nested=new LinkedHashMap<>();nested.put("v","original");Map<String,Object> source=new LinkedHashMap<>();source.put("nested",nested);
        WfNodeState left=new WfNodeState(source),right=new WfNodeState(source);
        @SuppressWarnings("unchecked") Map<String,Object> leftNested=(Map<String,Object>)left.data().get("nested");leftNested.put("v","changed");
        assertEquals("original",((Map<?,?>)right.data().get("nested")).get("v"));assertEquals("original",nested.get("v"));
    }

    @Test void restoreDropsUncommittedAttemptsAndOutputPersistenceMatchesAttemptUuid() {
        WfState state = new WfState(null,List.of(),"run",1L,null,null,null);
        var original = completed("A","old"); original.getState().setUuid("old-A");
        var committed = completed("A","committed"); committed.getState().setUuid("new-A");
        var uncommitted = completed("B","not-checkpointed"); uncommitted.getState().setUuid("old-B");
        state.getCompletedNodes().addAll(List.of(original, committed, uncommitted));
        for (String id : List.of("old-A","new-A","old-B")) {
            var row = new org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto(); row.setUuid(id); row.setNodeId(1L);
            state.getRuntimeNodes().add(row);
        }
        state.retainCheckpointCompleted(Set.of("A"));
        assertEquals(List.of(committed),state.getCompletedNodes());
        assertEquals("new-A",state.getRuntimeNodeByNodeUuid("A").getUuid());
        assertTrue(state.getNodeStateByNodeUuid("B").isEmpty());
        assertEquals(List.of("committed"),state.getOutput().stream().map(NodeIOData::valueToString).toList());
        var retry = completed("B","retry"); retry.getState().setUuid("new-B"); state.getCompletedNodes().add(retry);
        var retryRow = new org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto();retryRow.setUuid("new-B");retryRow.setNodeId(1L);
        state.getRuntimeNodes().add(retryRow);
        assertSame(retryRow,state.getRuntimeNodeByNodeUuid("B"));
        assertEquals("retry",state.getIOByNodeUuid("B").get(0).valueToString());
    }

}
