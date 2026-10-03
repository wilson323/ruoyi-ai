package org.ruoyi.ipd.agent.servicebridge;
import java.nio.file.*;import java.util.*;import io.agentscope.harness.agent.artifact.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;import org.ruoyi.ipd.agent.domain.*;
public class GArtifactProbe {
 static ProjectAgentArtifactDeliveryTest fresh() throws Exception {var t=new ProjectAgentArtifactDeliveryTest();t.root=Files.createTempDirectory("g-artifact-case-");t.setup();return t;}
 public static void main(String[] args)throws Exception {
 var t=fresh(); if(!t.target.deliver(t.ctx,new ArtifactDeliveryRequest("lost.bin",new byte[]{0,2,(byte)255},"lost.bin",null,false)).successful())throw new AssertionError();
 Files.delete(t.root.resolve("101.bin"));Files.delete(t.root.resolve("101.receipt.json")); t.events.clear();
 t.target.requireDocumentContent(t.rows.get(101L));System.out.println("COUNTEREXAMPLE_ORIGIN_BYTES_SIDECAR_LOST_APPLY_ALLOWED=true");
 var u=fresh();u.target.deliver(u.ctx,new ArtifactDeliveryRequest("a.bin",new byte[]{1},"a.bin",null,false));Files.delete(u.root.resolve("101.bin"));Files.delete(u.root.resolve("101.receipt.json"));var ev=u.events.get(0);ev.setPayload(ev.getPayload().replace("\"versionId\":\"101\"","\"versionId\":\"102\""));u.target.requireDocumentContent(u.rows.get(101L));System.out.println("COUNTEREXAMPLE_ORIGIN_OTHER_VERSION_APPLY_ALLOWED=true");
 var v=fresh();for(int i=1;i<=450;i++){var e=new IpdAgentRunEvent();e.setRunId(901L);e.setTenantId(org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT);e.setSeq((long)i);e.setEventType("STEP");e.setPayload("{}");v.events.add(e);} when(v.runs.listEvents(any(),anyLong(),anyInt())).thenAnswer(a->v.events.stream().filter(e->e.getSeq()>(Long)a.getArgument(1)).limit((Integer)a.getArgument(2)).toList());if(!v.target.deliver(v.ctx,new ArtifactDeliveryRequest("p.md","pagination".getBytes(),"p.md",null,false)).successful())throw new AssertionError("pagination");System.out.println("PAGINATION_451_ORIGIN_PASS=true");
 }
}