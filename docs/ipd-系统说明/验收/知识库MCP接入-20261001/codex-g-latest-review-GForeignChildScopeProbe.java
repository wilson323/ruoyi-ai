package org.ruoyi.ipd.agent.kernel;
import io.agentscope.core.agent.*;import io.agentscope.core.state.*;import io.agentscope.core.middleware.*;import io.agentscope.core.message.*;import io.agentscope.core.tool.*;import org.ruoyi.chat.kernel.KernelScopeKey;import java.util.*;import reactor.core.publisher.*;import static org.mockito.Mockito.*;
public class GForeignChildScopeProbe { public static void main(String[]args){
 var sink=mock(ProjectAgentEventSink.class);var guard=new ProjectAgentOfficialToolGovernance(sink,new KernelScopeKey.Scope("person","owning-run"));var effect=new GHitlProbe.Effect();var toolkit=new Toolkit();toolkit.registerAgentTool(effect);var agent=mock(Agent.class);when(agent.getToolkit()).thenReturn(toolkit);
 for(String session:List.of("foreign-run","sub-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")){
 var use=ToolUseBlock.builder().id("call-"+session).name("g_sideeffect").input(Map.of()).content("{}").state(ToolCallState.ALLOWED).build();var state=AgentState.builder().userId("person").sessionId(session).context(List.of(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(use)).build())).build();var runtime=RuntimeContext.builder().userId("person").sessionId(session).agentState(state).build();
 boolean allowed=false;try {guard.onActing(agent,runtime,new ActingInput(List.of(use)),ignored->Flux.empty()).blockLast();toolkit.getTool("g_sideeffect").callAsync(ToolCallParam.builder().agent(agent).runtimeContext(runtime).toolUseBlock(use).input(Map.of()).build()).block();allowed=true;}catch(IllegalStateException expected){}
 System.out.println(session.startsWith("sub-")?"UNREGISTERED_CHILD_SESSION_ALLOWED="+allowed:"UNRELATED_ROOT_SESSION_ALLOWED="+allowed);
 }
 System.out.println("SYNTHETIC_EFFECTS="+effect.executions.get());
}}
