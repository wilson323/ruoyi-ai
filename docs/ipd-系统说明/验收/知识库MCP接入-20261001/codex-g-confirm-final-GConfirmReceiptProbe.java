package org.ruoyi.ipd.agent.kernel;
import io.agentscope.core.agent.*;
import io.agentscope.core.state.*;
import io.agentscope.core.message.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import java.util.*;
import static org.mockito.Mockito.*;
public class GConfirmReceiptProbe {
 static void check(String label, String session, boolean receipt, boolean consumed, boolean tamper, boolean deny, boolean expect) {
  var effect=new GHitlProbe.Effect(); var sink=mock(ProjectAgentEventSink.class);
  var owned=new ProjectAgentOfficialToolGovernance.OwnedTool(effect,sink);
  var approved=ToolUseBlock.builder().id("exact-call").name("g_sideeffect").input(Map.of()).content("{}").state(ToolCallState.ALLOWED).build();
  var call=tamper?ToolUseBlock.builder().id("exact-call").name("g_sideeffect").input(Map.of("changed",true)).content("{\"changed\":true}").state(ToolCallState.ALLOWED).build():approved;
  var messages=new ArrayList<Msg>();
  if(receipt)messages.add(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(approved)).build());
  if(consumed)messages.add(Msg.builder().role(MsgRole.TOOL).content(List.of(ToolResultBlock.builder().id("exact-call").name("g_sideeffect").output(TextBlock.builder().text("prior result").build()).build())).build());
  var perms=PermissionContextState.builder();
  if(deny)perms.addDenyRule("g_sideeffect",new PermissionRule("g_sideeffect",null,PermissionBehavior.DENY,"synthetic deny"));
  else perms.addAskRule("g_sideeffect",new PermissionRule("g_sideeffect",null,PermissionBehavior.ASK,"synthetic ask"));
  var state=AgentState.builder().userId("person").sessionId("owned-session").context(messages).permissionContext(perms.build()).build();
  var rc=RuntimeContext.builder().userId("person").sessionId(session).agentState(state).build();
  boolean succeeded=false;try {owned.callAsync(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(rc).build()).block();succeeded=true;}catch(IllegalStateException expected){}
  if(succeeded!=expect||effect.executions.get()!=(expect?1:0))throw new AssertionError(label);
  System.out.println(label+".PASS effects="+effect.executions.get());
 }
 public static void main(String[]args) {
  check("exactReceipt", "owned-session",true,false,false,false,true);
  check("wrongSession", "different-session",true,false,false,false,false);
  check("forgedAllowedNoReceipt", "owned-session",false,false,false,false,false);
  check("consumedReceipt", "owned-session",true,true,false,false,false);
  check("tamperedInput", "owned-session",true,false,true,false,false);
  check("nativeDenyWithoutReceipt", "owned-session",false,false,false,true,false);
 }
}
