package org.ruoyi.chat.poc.kernel;

import org.ruoyi.chat.kernel.KernelScopeKey;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [PoC G4] 真实流式关:G3 的 HarnessAgent + 真模型(MiniMax OpenAI 兼容端点)发真实流式请求,
 * 逐 chunk 打印前 5 事件 + 完整拼接文本长度;运行态复核 okhttp 5.3.2 收敛后无类冲突。
 *
 * <p>缺 MINIMAX_API_KEY 时本关 BLOCKED_ENVIRONMENT(测试跳过并留名),不得伪造流式输出。
 */
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".+")
class AgentScopeStreamingPocIT {

    private static final String RUN = "R" + Long.toString(System.nanoTime(), 36);

    @Test
    void realStreamingOverOkhttp5() throws Exception {
        HarnessAgent agent = PocKernelSupport.buildAgent("emp-a1");
        try {
            KernelScopeKey.Scope scope = KernelScopeKey.of("P1", "U1", "emp-a1", "G4S-" + RUN);
            Msg msg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent("请用一句中文介绍你自己,并务必包含数字 42。")
                    .build();

            List<AgentEvent> events = new ArrayList<>();
            Map<Integer, String> firstChunks = new ConcurrentHashMap<>();
            StringBuilder text = new StringBuilder();

            agent.streamEvents(msg, scope.toRuntimeContext())
                    .doOnNext(
                            ev -> {
                                int idx = events.size();
                                events.add(ev);
                                if (idx < 5) {
                                    String brief =
                                            ev instanceof TextBlockDeltaEvent d ? d.getDelta() : "";
                                    firstChunks.put(idx, ev.getType() + " :: " + brief);
                                    System.out.println("[G4 chunk#" + idx + "] " + ev.getType()
                                            + " :: " + brief);
                                }
                                if (ev instanceof TextBlockDeltaEvent d) {
                                    text.append(d.getDelta());
                                }
                            })
                    .blockLast(Duration.ofSeconds(180));

            System.out.println("[G4] total events = " + events.size());
            System.out.println("[G4] concatenated text length = " + text.length());
            System.out.println("[G4] text head = "
                    + text.substring(0, Math.min(120, text.length())));
            System.out.println("[G4] okhttp5 runtime check: stream completed without"
                    + " NoSuchMethodError / NoClassDefFoundError / 类冲突");

            assertTrue(events.size() >= 5, "事件流应至少 5 个事件,实际 " + events.size());
            assertFalse(text.isEmpty(), "拼接文本不应为空(真实模型应有输出)");
        } finally {
            agent.close();
        }
    }
}
