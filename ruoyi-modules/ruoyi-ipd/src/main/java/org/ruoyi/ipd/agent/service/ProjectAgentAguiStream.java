package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** SSE 连接仅观察原持久事件。断开、超时、发送失败只释放观察者，绝不取消后台运行。 */
@Service
public final class ProjectAgentAguiStream {
    private final ProjectAgentRunService runs;
    private final ProjectAgentAguiProjection projection;
    private final long connectionTimeoutMs;

    public ProjectAgentAguiStream(ProjectAgentRunService runs, ObjectMapper mapper) {
        this(runs, mapper, 300L);
    }

    @Autowired
    public ProjectAgentAguiStream(ProjectAgentRunService runs, ObjectMapper mapper,
            @Value("${ipd.project-agent.run-timeout-seconds:300}") long timeoutSeconds) {
        this.runs = runs;
        this.projection = new ProjectAgentAguiProjection(mapper);
        // 与执行器同一配置，正常长运行不能因固定60秒连接耗尽客户端重连次数。
        this.connectionTimeoutMs = Math.multiplyExact(Math.addExact(Math.max(30, timeoutSeconds), 30), 1000);
    }

    public SseEmitter open(IpdActor actor, Long runId, Long afterSeq, String lastEventId) {
        long cursor = cursor(afterSeq, lastEventId);
        // 身份/归属拒绝在 SSE 响应提交之前发生；后续每页重新核项目权限。
        ProjectAgentViews.Events first = runs.events(actor, runId, cursor);
        SseEmitter emitter = new SseEmitter(connectionTimeoutMs);
        AtomicLong delivered = new AtomicLong(cursor);
        AtomicBoolean disconnected = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable stop = () -> {
            disconnected.set(true);
            Disposable reading = subscription.get();
            if (reading != null) reading.dispose();
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(() -> { stop.run(); emitter.complete(); });
        emitter.onError(ignored -> stop.run());
        Flux<ProjectAgentViews.Events> pages = Mono.just(first).concatWith(
            Flux.interval(Duration.ofMillis(400))
                .onBackpressureDrop()
                .concatMap(tick -> Mono.fromCallable(() -> runs.events(actor, runId, delivered.get()))
                    .subscribeOn(Schedulers.boundedElastic()), 1));
        Disposable reading = pages.takeUntil(ProjectAgentViews.Events::terminal)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(page -> {
                if (disconnected.get()) return;
                try {
                    if (page.events().isEmpty()) emitter.send(SseEmitter.event().comment("keep-alive"));
                    for (ProjectAgentViews.Event row : page.events()) {
                        var frames = projection.encode(String.valueOf(runId), row);
                        for (int index = 0; index < frames.size(); index++) {
                            // ID 只放在一行的最后一个 ipd_event 帧上。
                            var frame = SseEmitter.event().data(frames.get(index));
                            if (index == frames.size() - 1) frame.id(String.valueOf(row.seq()));
                            emitter.send(frame);
                        }
                        delivered.set(row.seq());
                    }
                    if (page.terminal()) emitter.complete();
                    else if (page.events().size() < org.ruoyi.ipd.agent.ProjectAgentConstants.EVENTS_PAGE_LIMIT) {
                        var detail = runs.get(actor, runId);
                        if ("WAITING_APPROVAL".equals(detail.status()) && detail.pauseSeq() != null
                                && delivered.get() >= detail.pauseSeq()) {
                            stop.run();
                            emitter.complete();
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    stop.run();
                    emitter.completeWithError(new IllegalStateException("项目运行事件读取失败"));
                }
            }, failure -> emitter.completeWithError(new IllegalStateException("项目运行事件读取失败")), emitter::complete);
        subscription.set(reading);
        if (disconnected.get()) reading.dispose();
        return emitter;
    }

    public static long cursor(Long afterSeq, String lastEventId) {
        long value = afterSeq == null ? 0 : afterSeq;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                long last = Long.parseLong(lastEventId);
                if (last < 0) throw new NumberFormatException("negative cursor");
                value = Math.max(value, last);
            }
            catch (NumberFormatException invalid) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "事件游标格式错误");
            }
        }
        if (value < 0 || (afterSeq != null && afterSeq < 0))
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "事件游标不能为负数");
        return value;
    }
}
