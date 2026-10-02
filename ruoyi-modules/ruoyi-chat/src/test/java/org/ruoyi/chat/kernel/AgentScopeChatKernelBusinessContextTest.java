package org.ruoyi.chat.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class AgentScopeChatKernelBusinessContextTest {
    @TempDir Path workspace;

    @Test void explicitBusinessPromptAndHistorySurviveWithoutEngineeringFilesOrAtPathExpansion() throws Exception {
        Path employee = workspace.resolve("P").resolve("U").resolve("business");
        Files.createDirectories(employee);
        Files.writeString(employee.resolve("AGENTS.md"),"WORKSPACE_ENGINEERING_CANARY_DO_NOT_INJECT");
        Path attachment=employee.resolve("engineering-note.txt");
        Files.writeString(attachment,"AT_PATH_ENGINEERING_CANARY_DO_NOT_INJECT");
        List<List<Msg>> requests = new CopyOnWriteArrayList<>();
        Model model = new Model() {
            public String getModelName(){return "stub-business";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                requests.add(List.copyOf(messages));
                return Flux.just(new ChatResponse("stub-response-"+requests.size(),List.of(TextBlock.builder().text("EXPLICIT_HISTORY_REPLY").build()),null,Map.of(),"stop"));
            }
        };
        InMemoryAgentStateStore store=new InMemoryAgentStateStore();
        try(AgentScopeChatKernel kernel=new AgentScopeChatKernel(model,"stub:business",()->store,workspace)) {
            Sink first=new Sink();
            kernel.stream("P","U","business","S","EXPLICIT_USER_FACT @"+attachment,"EXPLICIT_BUSINESS_SYSTEM AUTHORIZED_KNOWLEDGE_FACT",first);
            assertTrue(first.done.await(10,TimeUnit.SECONDS));assertTrue(first.errors.isEmpty(),first.errors.toString());
            Sink second=new Sink();
            kernel.stream("P","U","business","S","SECOND_USER_FACT","EXPLICIT_BUSINESS_SYSTEM AUTHORIZED_KNOWLEDGE_FACT",second);
            assertTrue(second.done.await(10,TimeUnit.SECONDS));assertTrue(second.errors.isEmpty(),second.errors.toString());
        }
        assertEquals(2,requests.size());
        String input=requests.get(1).stream().map(Msg::getTextContent).reduce("",(a,b)->a+"\n"+b);
        assertTrue(input.contains("EXPLICIT_BUSINESS_SYSTEM"));assertTrue(input.contains("AUTHORIZED_KNOWLEDGE_FACT"));
        assertTrue(input.contains("EXPLICIT_USER_FACT"));assertTrue(input.contains("EXPLICIT_HISTORY_REPLY"));assertTrue(input.contains("SECOND_USER_FACT"));
        for(List<Msg> request:requests) {
            String text=request.stream().map(Msg::getTextContent).reduce("",(a,b)->a+"\n"+b);
            assertFalse(text.contains("WORKSPACE_ENGINEERING_CANARY_DO_NOT_INJECT"));
            assertFalse(text.contains("AT_PATH_ENGINEERING_CANARY_DO_NOT_INJECT"));
            assertFalse(text.contains("<attached_file"));
            assertFalse(text.contains("万傲瑞达 V6600、ZKTime、ZKAccess3.5"),"cwd repository engineering rule must not reach business model");
            assertFalse(text.contains("Ruflo 多智能体协同底座"),"cwd repository AGENTS must not reach business model");
        }
    }
    private static class Sink implements KernelChatSink {
        final CountDownLatch done=new CountDownLatch(1);
        final List<String> errors=new CopyOnWriteArrayList<>();
        public void onContent(String value){}
        public void onReasoning(String value){}
        public void onMcpTool(String tool,String status,String result){}
        public void onError(String code,String message){errors.add(code);done.countDown();}
        public void onComplete(){done.countDown();}
    }
}
