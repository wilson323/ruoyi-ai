package org.ruoyi.workflow.workflow.checkpoint;

import org.junit.jupiter.api.*;
import org.ruoyi.workflow.workflow.*;
import org.ruoyi.workflow.entity.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class WorkflowNativeDagContractTest {
    @BeforeAll static void cache() { TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),WorkflowCheckpoint.class); }
    private static List<WorkflowNode> nodes(String... names) {
        List<WorkflowNode> result=new ArrayList<>(); for(String name:names){WorkflowNode node=new WorkflowNode();node.setUuid(name);result.add(node);}return result;
    }
    private static WorkflowEdge edge(String a,String b,String handle) { WorkflowEdge edge=new WorkflowEdge();edge.setSourceNodeUuid(a);edge.setTargetNodeUuid(b);edge.setSourceHandle(handle);return edge; }
    private static WorkflowExecutionPlan plan(List<WorkflowNode> nodes,List<WorkflowEdge> edges,WorkflowNodeRunner runner) {
        return new WorkflowGraphBuilder(List.of(),nodes,edges,runner,null).build(nodes.get(0));
    }
    @Test void actualParallelDiamondJoinsAfterBothBranchesOnlyOnce() throws Exception {
        var definitions=nodes("A","B","C","D","E","F","G");
        var edges=List.of(edge("A","B",null),edge("A","C",null),edge("B","D",null),edge("C","D",null),edge("D","E",null),edge("D","F",null),edge("E","G",null),edge("F","G",null));
        CountDownLatch both=new CountDownLatch(2); Set<String> finished=ConcurrentHashMap.newKeySet(); List<String> seen=Collections.synchronizedList(new ArrayList<>());
        var graph=plan(definitions,edges,(node,state)-> {
            String name=node.getUuid(); seen.add(name);
            if(name.equals("B")||name.equals("C")) { both.countDown(); try {assertTrue(both.await(5,TimeUnit.SECONDS),"支路必须真实并行执行");} catch(InterruptedException e){throw new IllegalStateException(e);} }
            if(name.equals("D")) assertTrue(finished.containsAll(List.of("B","C")));
            if(name.equals("G")) assertTrue(finished.containsAll(List.of("E","F")));
            finished.add(name);return Map.of();
        });
        graph.execute(new JdbcCheckpointSaver(new FakeCheckpointDb().mapper()),"parallel",false,Map.of(),n->{});
        assertEquals(7,seen.size());assertEquals(7,new HashSet<>(seen).size());
    }
    @Test void conditionalJoinNeverWaitsForOrExecutesUnchosenBranch() throws Exception {
        List<String> seen=Collections.synchronizedList(new ArrayList<>());
        var graph=plan(nodes("A","D","X","C","B"),List.of(edge("A","B","selected"),edge("A","C","other"),edge("C","X",null),edge("X","D",null),edge("B","D",null)),(node,state)->{
            seen.add(node.getUuid());return node.getUuid().equals("A")?Map.of("next","B"):Map.of();
        });
        graph.execute(new JdbcCheckpointSaver(new FakeCheckpointDb().mapper()),"conditional",false,Map.of(),n->{});
        assertEquals(List.of("A","B","D"),seen);
    }
    @Test void completedSiblingIsNotRepeatedAfterFailedParallelBranch() throws Exception {
        FakeCheckpointDb db=new FakeCheckpointDb();Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();AtomicBoolean fail=new AtomicBoolean(true);
        var definitions=nodes("A","B","C","D");var edges=List.of(edge("A","B",null),edge("A","C",null),edge("B","D",null),edge("C","D",null));
        WorkflowNodeRunner runner=(node,state)->{String name=node.getUuid();calls.computeIfAbsent(name,n->new AtomicInteger()).incrementAndGet();if(name.equals("B")&&fail.getAndSet(false))throw new IllegalStateException("interruption");return Map.of();};
        assertThrows(Exception.class,()->plan(definitions,edges,runner).execute(new JdbcCheckpointSaver(db.mapper()),"resume-parallel",false,Map.of(),n->{}));
        assertFalse(calls.containsKey("D"));
        plan(definitions,edges,runner).execute(new JdbcCheckpointSaver(db.mapper()),"resume-parallel",true,Map.of(),n->{});
        assertEquals(1,calls.get("A").get());assertEquals(2,calls.get("B").get());assertEquals(1,calls.get("C").get());assertEquals(1,calls.get("D").get());
    }
    @Test void missingOrUnconfiguredRouteFailsBeforeAnyBranch() {
        for(var result:List.of(Map.<String,Object>of(),Map.<String,Object>of("next","unknown"))) {
            List<String> seen=new ArrayList<>();
            var graph=plan(nodes("A","B","C"),List.of(edge("A","B","b"),edge("A","C","c")),(node,state)->{seen.add(node.getUuid());return result;});
            assertThrows(Exception.class,()->graph.execute(new JdbcCheckpointSaver(new FakeCheckpointDb().mapper()),"bad-"+result.size(),false,Map.of(),n->{}));assertEquals(List.of("A"),seen);
        }
    }
    @Test void malformedDefinitionsRejectedBeforeNodeExecution() {
        WorkflowNodeRunner unused=(n,s)->{fail("不能执行无效图");return Map.of();};
        assertThrows(IllegalArgumentException.class,()->plan(nodes("A","B"),List.of(edge("A","B",null),edge("B","A",null)),unused));
        assertThrows(IllegalArgumentException.class,()->plan(nodes("A","B"),List.of(),unused));
        assertThrows(IllegalArgumentException.class,()->plan(nodes("A","B"),List.of(edge("A","B",null),edge("A","B",null)),unused));
        assertThrows(IllegalArgumentException.class,()->plan(nodes("A"),List.of(edge("A","missing",null)),unused));
    }
    @Test void graphDefinitionChangeRefusesResumeInsteadOfReplay() throws Exception {
        FakeCheckpointDb db=new FakeCheckpointDb();var definitions=nodes("A","B");var edges=List.of(edge("A","B",null));
        var graph=plan(definitions,edges,(node,state)->{if(node.getUuid().equals("B"))throw new IllegalStateException("pause");return Map.of();});
        assertThrows(Exception.class,()->graph.execute(new JdbcCheckpointSaver(db.mapper()),"changed",false,Map.of(),n->{}));
        definitions.get(1).setNodeConfig("changed");AtomicInteger calls=new AtomicInteger();
        assertThrows(Exception.class,()->plan(definitions,edges,(node,state)->{calls.incrementAndGet();return Map.of();}).execute(new JdbcCheckpointSaver(db.mapper()),"changed",true,Map.of(),n->{}));assertEquals(0,calls.get());
    }
    @Test void unknownSideEffectCannotBeAutomaticallyRepeated() {
        FakeCheckpointDb db=new FakeCheckpointDb();var definitions=nodes("A","B");definitions.get(1).setWorkflowComponentId(99L);
        WorkflowComponent component=new WorkflowComponent();component.setId(99L);component.setName("MailSend");
        AtomicInteger calls=new AtomicInteger(); WorkflowNodeRunner runner=(node,state)-> {if(node.getUuid().equals("B")){calls.incrementAndGet();throw new IllegalStateException("response lost after mail sent");}return Map.of();};
        var builder=new WorkflowGraphBuilder(List.of(component),definitions,List.of(edge("A","B",null)),runner,null);
        assertThrows(Exception.class,()->builder.build(definitions.get(0)).execute(new JdbcCheckpointSaver(db.mapper()),"effect",false,Map.of(),n->{}));
        assertThrows(Exception.class,()->builder.build(definitions.get(0)).execute(new JdbcCheckpointSaver(db.mapper()),"effect",true,Map.of(),n->{}));assertEquals(1,calls.get());
    }
}
