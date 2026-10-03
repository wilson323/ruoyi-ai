package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;import io.modelcontextprotocol.spec.McpSchema;import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class ProductLineMcpFullSchemaTest {
 static class Session implements ProductLineMcpQuery.LineSession {
  Map<String,Object> schema;int calls;int lists;Map<String,Object> arguments;
  Session(Map<String,Object> schema){this.schema=schema;}
  public List<McpSchema.Tool> listTools(){lists++;return List.of(McpSchema.Tool.builder().name("lookup").inputSchema(new McpSchema.JsonSchema("object",Map.of("query",Map.of("type","string")),List.of("query"),false,null,null)).build());}
  public Map<String,Object> rawInputSchema(String name){return schema;}
  public McpSchema.CallToolResult callTool(String name,Map<String,Object> args){calls++;arguments=args;return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("知识正文")),false);}
  public void close(){}
 }
 static Map<String,Object> base(){return Map.of("type","object","properties",Map.of("query",Map.of("type","string")),"required",List.of("query"),"additionalProperties",false);}
 @Test void topLevelOneOfMustNotBeLost(){var raw=new HashMap<>(base());raw.put("oneOf",List.of(Map.of("properties",Map.of("query",Map.of("const","approved"))),Map.of("properties",Map.of("query",Map.of("const","allowed")))));var s=new Session(raw);var r=new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"bad",s);assertEquals(0,s.calls);assertEquals("INVALID_ARGUMENTS",r.diagnostic().reasonCode());}
 @Test void allOfAndLocalRefValidateCompleteArguments(){var raw=new HashMap<>(base());raw.put("$defs",Map.of("q",Map.of("type","string","minLength",3)));raw.put("allOf",List.of(Map.of("properties",Map.of("query",Map.of("$ref","#/$defs/q")))));var s=new Session(raw);new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"x",s);assertEquals(0,s.calls);new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"okay",s);assertEquals(1,s.calls);assertEquals(Map.of("query","okay"),s.arguments);}
 @Test void externalReferenceIsRejectedBeforeCall(){var raw=new HashMap<>(base());raw.put("$ref","http://127.0.0.1:9/private");var s=new Session(raw);var r=new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"okay",s);assertEquals(0,s.calls);assertEquals("UNSUPPORTED_SCHEMA",r.diagnostic().reasonCode());}
 @Test void fullSchemaCannotBeMissingInProductionSession(){var s=new Session(null);var r=new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"okay",s);assertEquals(0,s.calls);assertEquals("UNSUPPORTED_SCHEMA",r.diagnostic().reasonCode());}
 @Test void schemaDigestPinsNewSessionAndRejectsDrift(){var s=new Session(base());var r=new ProductLineMcpQuery().invokeOutcome(ProductLineMcpCatalog.all().get(0),"okay","0".repeat(64),s);assertEquals(0,s.calls);assertEquals("SCHEMA_CHANGED",r.diagnostic().reasonCode());}
 @Test void digestIgnoresMapInsertionOrder(){var reversed=new LinkedHashMap<String,Object>();var keys=new ArrayList<>(base().keySet());Collections.reverse(keys);for(var k:keys)reversed.put(k,base().get(k));assertEquals(ProductLineMcpQuery.schemaDigest("lookup",base()),ProductLineMcpQuery.schemaDigest("lookup",reversed));}
 @Test void discoveryNeverInvokesKnowledgeTool() throws Exception {var s=new Session(base());var text=new ProductLineMcpQuery().discover(ProductLineMcpCatalog.all().get(0),s);assertEquals(0,s.calls);assertTrue(text.contains("schemaDigest"));assertTrue(text.contains("仅诊断"));}
 @Test void discoveryCanDescribeMultipleToolsWithoutCalling() throws Exception {var s=new Session(base()) {public List<McpSchema.Tool> listTools(){var first=super.listTools().get(0);return List.of(first,McpSchema.Tool.builder().name("second").inputSchema(first.inputSchema()).build());}};var text=new ProductLineMcpQuery().discover(ProductLineMcpCatalog.all().get(0),s);assertTrue(text.contains("second"));assertEquals(0,s.calls);}
 @Test void wrapperRejectsArbitraryProtocolSelectionAndArguments(){var s=new Session(base());var tool=ProductLineMcpTool.openWith(ProductLineMcpCatalog.all().get(0),org.mockito.Mockito.mock(ProjectAgentEventSink.class),s);for(var extra:List.of("toolName","arguments")){var result=tool.callAsync(io.agentscope.core.tool.ToolCallParam.builder().input(Map.of("query","okay",extra,"unexpected")).build()).block();assertNotNull(result);}assertEquals(0,s.calls);assertEquals(0,s.lists);}
}
