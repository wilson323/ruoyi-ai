package org.ruoyi.workflow.workflow.checkpoint;

import org.junit.jupiter.api.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.workflow.workflow.*;
import org.ruoyi.workflow.workflow.data.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class LegacyWorkflowCheckpointCompatibilityTest {
    @BeforeAll static void cache() { TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkflowCheckpoint.class); }
    private String fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/workflow-checkpoint/legacy-1.8.20-synthetic.bin")) {
            assertNotNull(in);byte[] bytes=in.readAllBytes();
            assertEquals("065a5324b231f1c169887a5174c524afd4ac469b58a6b1eea02bf3ffbaf0ff95",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            return Base64.getEncoder().encodeToString(bytes);
        }
    }
    @Test void independentlyWrittenLegacyPayloadRetainsAllBusinessTypes() throws Exception {
        var checkpoint=JdbcCheckpointSaver.deserializeCheckpoint(fixture());
        assertEquals("synthetic-node-a",checkpoint.getNodeId());assertEquals("synthetic-node-b",checkpoint.getNextNodeId());
        Map<String,Object> state=checkpoint.getState();assertNull(state.get("nullable"));assertEquals("",state.get("empty"));assertEquals("测试".repeat(22000),state.get("longChinese"));
        Map<?,?> nested=assertInstanceOf(Map.class,state.get("nested"));assertEquals(Arrays.asList("中文",null,3,true),nested.get("list"));assertEquals(new LinkedHashSet<>(Arrays.asList("甲","乙",null)),nested.get("set"));
        List<?> outputs=assertInstanceOf(List.class,state.get("outputs"));assertEquals(5,outputs.size());
        assertInstanceOf(NodeIODataTextContent.class,((NodeIOData)outputs.get(0)).getContent());assertInstanceOf(NodeIODataNumberContent.class,((NodeIOData)outputs.get(1)).getContent());
        assertInstanceOf(NodeIODataBoolContent.class,((NodeIOData)outputs.get(2)).getContent());assertInstanceOf(NodeIODataFilesContent.class,((NodeIOData)outputs.get(3)).getContent());assertInstanceOf(NodeIODataOptionsContent.class,((NodeIOData)outputs.get(4)).getContent());
    }
    @Test void legacyLinearResumeStartsAtNextNodeAndKeepsOldInputs() throws Exception {
        FakeCheckpointDb db=new FakeCheckpointDb();WorkflowCheckpoint row=new WorkflowCheckpoint();row.setThreadId("legacy-linear");row.setCheckpointId("old");row.setNodeId("synthetic-node-a");row.setNextNodeId("synthetic-node-b");row.setStateJson(fixture());row.setIsDeleted(false);db.mapper().insert(row);
        List<WorkflowNode> definitions=new ArrayList<>();for(String name:List.of("synthetic-node-a","synthetic-node-b","C")){WorkflowNode node=new WorkflowNode();node.setUuid(name);definitions.add(node);}
        List<WorkflowEdge> edges=new ArrayList<>();for(String[] pair:List.of(new String[]{"synthetic-node-a","synthetic-node-b"},new String[]{"synthetic-node-b","C"})){WorkflowEdge edge=new WorkflowEdge();edge.setSourceNodeUuid(pair[0]);edge.setTargetNodeUuid(pair[1]);edges.add(edge);}
        List<String> seen=new ArrayList<>();var graph=new WorkflowGraphBuilder(List.of(),definitions,edges,(node,state)->{seen.add(node.getUuid());assertEquals("SYNTHETIC_LANGGRAPH4J_1_8_20",state.data().get("fixtureKind"));return Map.of();},null).build(definitions.get(0));
        graph.execute(new JdbcCheckpointSaver(db.mapper()),"legacy-linear",true,Map.of(),n->{});assertEquals(List.of("synthetic-node-b","C"),seen);
    }
    @Test void corruptPayloadCannotBeAcceptedAsEmptyCheckpoint() throws Exception {
        assertThrows(Exception.class,()->JdbcCheckpointSaver.deserializeCheckpoint("not-base64"));
        assertThrows(Exception.class,()->JdbcCheckpointSaver.deserializeCheckpoint(Base64.getEncoder().encodeToString(new byte[]{1,2,3})));
    }
}
