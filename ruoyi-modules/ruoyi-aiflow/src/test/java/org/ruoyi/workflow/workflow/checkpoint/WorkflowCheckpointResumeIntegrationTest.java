package org.ruoyi.workflow.workflow.checkpoint;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.ruoyi.workflow.workflow.*;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.entity.WorkflowEdge;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 生产确定性调度与生产检查点仓储；mock mapper 仅替代 DB IO，非模拟 resume。 */
@Tag("dev")
class WorkflowCheckpointResumeIntegrationTest {
    @BeforeAll static void initMpLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), org.ruoyi.workflow.entity.WorkflowCheckpoint.class);
    }
    private WorkflowExecutionPlan graph(List<String> seen, AtomicBoolean fail, Map<String,Object> observed) {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (String id : List.of("nodeA", "nodeB", "nodeC")) { WorkflowNode node = new WorkflowNode(); node.setUuid(id); nodes.add(node); }
        List<WorkflowEdge> edges = new ArrayList<>();
        for (String[] pair : List.of(new String[]{"nodeA","nodeB"}, new String[]{"nodeB","nodeC"})) {
            WorkflowEdge edge = new WorkflowEdge(); edge.setSourceNodeUuid(pair[0]); edge.setTargetNodeUuid(pair[1]); edges.add(edge);
        }
        return new WorkflowGraphBuilder(List.of(), nodes, edges, (node, state) -> {
            String id = node.getUuid(); seen.add(id);
            if (id.equals("nodeB") && fail.getAndSet(false)) throw new IllegalStateException("simulated interruption");
            if (id.equals("nodeC")) observed.put("in",state.data().get("in"));
            return Map.of();
        }, null).build(nodes.get(0));
    }
    @Test void restartContinuesFromPersistedFrontierWithoutRerun() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb(); List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean fail = new AtomicBoolean(true); Map<String,Object> observed = new HashMap<>();
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(db.mapper());
        assertThrows(Exception.class, () -> graph(seen,fail,observed).execute(saver,"runtime",false,Map.of("in","v"),n -> {}));
        assertEquals(List.of("nodeA","nodeB"),seen);
        var latest=saver.get(WorkflowCheckpointConfig.builder().threadId("runtime").build()).orElseThrow();
        assertEquals("nodeA",latest.getNodeId()); assertEquals("nodeB",latest.getNextNodeId());
        graph(seen,fail,observed).execute(new JdbcCheckpointSaver(db.mapper()),"runtime",true,Map.of(),n -> {});
        assertEquals(List.of("nodeA","nodeB","nodeB","nodeC"),seen); assertEquals("v",observed.get("in"));
    }
    @Test void absentCheckpointNeverRestartsFromBeginning() {
        List<String> seen = new ArrayList<>();
        assertThrows(Exception.class, () -> graph(seen,new AtomicBoolean(false),new HashMap<>()).execute(new JdbcCheckpointSaver(new FakeCheckpointDb().mapper()),"runtime",true,Map.of(),n -> {}));
        assertTrue(seen.isEmpty());
    }
    @Test void outputWriteThenCheckpointFailureRetriesReadOnlyNodeFromCommittedFrontier() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb(); List<String> seen = Collections.synchronizedList(new ArrayList<>());
        List<String> outputWrites = new ArrayList<>();
        JdbcCheckpointSaver failing = new JdbcCheckpointSaver(db.mapper()) {
            @Override public synchronized void put(WorkflowCheckpointConfig config, WorkflowCheckpointState checkpoint) throws Exception {
                if (checkpoint.getNodeId().equals("nodeB") && ((Collection<?>)checkpoint.getState().get("completed")).contains("nodeB"))
                    throw new IllegalStateException("checkpoint IO failed after output committed");
                super.put(config, checkpoint);
            }
        };
        assertThrows(Exception.class, () -> graph(seen,new AtomicBoolean(false),new HashMap<>()).execute(failing,"output-gap",false,Map.of(),outputWrites::add));
        assertEquals(List.of("nodeA","nodeB"),outputWrites);
        var checkpoint = new JdbcCheckpointSaver(db.mapper()).get(WorkflowCheckpointConfig.builder().threadId("output-gap").build()).orElseThrow();
        assertEquals(List.of("nodeA"),checkpoint.getState().get("completed"));
        assertEquals(List.of("nodeB"),checkpoint.getState().get("inFlight"));
        graph(seen,new AtomicBoolean(false),new HashMap<>()).execute(new JdbcCheckpointSaver(db.mapper()),"output-gap",true,Map.of(),outputWrites::add);
        assertEquals(List.of("nodeA","nodeB","nodeB","nodeC"),seen);
    }

    @Test void parallelCheckpointFailureCannotBeCommittedByAnotherWorker() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb();
        var failed = new java.util.concurrent.CountDownLatch(1);
        List<WorkflowNode> definitions = new ArrayList<>();
        for (String id : List.of("A","B","C")) { var node = new WorkflowNode(); node.setUuid(id); definitions.add(node); }
        List<WorkflowEdge> edges = new ArrayList<>();
        for (String target : List.of("B","C")) { var edge = new WorkflowEdge();edge.setSourceNodeUuid("A");edge.setTargetNodeUuid(target);edges.add(edge); }
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(db.mapper()) {
            @Override public synchronized void put(WorkflowCheckpointConfig config, WorkflowCheckpointState checkpoint) throws Exception {
                if (((Collection<?>)checkpoint.getState().get("completed")).contains("B")) {
                    failed.countDown(); throw new IllegalStateException("B output committed, checkpoint unavailable");
                }
                super.put(config, checkpoint);
            }
        };
        var graph = new WorkflowGraphBuilder(List.of(),definitions,edges,(node,state) -> {
            if (node.getUuid().equals("C")) {
                try { if (!failed.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("B did not fail"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            return Map.of();
        },null).build(definitions.get(0));
        List<String> outputWrites = new ArrayList<>();
        assertThrows(Exception.class,()->graph.execute(saver,"parallel-output-gap",false,Map.of(),outputWrites::add));
        var checkpoint = saver.get(WorkflowCheckpointConfig.builder().threadId("parallel-output-gap").build()).orElseThrow();
        assertEquals(List.of("A"),checkpoint.getState().get("completed"));
        assertEquals(List.of("B","C"),checkpoint.getState().get("inFlight"));
        assertEquals(List.of("A","B"),outputWrites);
    }

    @Test void checkpointFailureFencesQueuedWorkersBeyondPoolSize() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb();
        var failed = new java.util.concurrent.CountDownLatch(1);
        List<WorkflowNode> definitions = new ArrayList<>();
        List<WorkflowEdge> edges = new ArrayList<>();
        for (int i = -1; i < 10; i++) {
            var node = new WorkflowNode();node.setUuid(i < 0 ? "A" : "B"+i);definitions.add(node);
            if (i >= 0) {var edge = new WorkflowEdge();edge.setSourceNodeUuid("A");edge.setTargetNodeUuid(node.getUuid());edges.add(edge);}
        }
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(db.mapper()) {
            @Override public synchronized void put(WorkflowCheckpointConfig config, WorkflowCheckpointState checkpoint) throws Exception {
                if (((Collection<?>)checkpoint.getState().get("completed")).contains("B0")) {
                    failed.countDown(); throw new IllegalStateException("checkpoint unavailable");
                }
                super.put(config, checkpoint);
            }
        };
        Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var graph = new WorkflowGraphBuilder(List.of(),definitions,edges,(node,state)-> {
            started.add(node.getUuid());
            if (node.getUuid().startsWith("B") && !node.getUuid().equals("B0")) {
                try { if (!failed.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("failure missing"); }
                catch (InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            }
            return Map.of();
        },null).build(definitions.get(0));
        assertThrows(Exception.class,()->graph.execute(saver,"queued-gap",false,Map.of(),node->{}));
        assertFalse(started.contains("B8"));assertFalse(started.contains("B9"));
    }

}
