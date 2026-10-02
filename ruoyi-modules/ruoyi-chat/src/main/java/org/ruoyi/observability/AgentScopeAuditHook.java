package org.ruoyi.observability;

import io.agentscope.core.hook.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.util.Set;

/** SDK Hook 只记录事件类别/时间/错误类型；不记录业务内容和密钥。 */
@Slf4j
public class AgentScopeAuditHook implements Hook {
    private final String category;
    private final Set<String> eventTypes;
    public AgentScopeAuditHook(String category, String... events) { this.category = category; this.eventTypes = Set.of(events); }
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (eventTypes.isEmpty() || eventTypes.contains(event.getClass().getSimpleName())) {
            if (event instanceof ErrorEvent error) {
                log.error("agentscope_event category={} event={} timestamp={} errorType={}", category,
                    event.getType(), event.getTimestamp(), error.getError() == null ? "UNKNOWN" : error.getError().getClass().getName());
            } else {
                log.info("agentscope_event category={} event={} timestamp={}", category, event.getType(), event.getTimestamp());
            }
        }
        return Mono.just(event);
    }
}
