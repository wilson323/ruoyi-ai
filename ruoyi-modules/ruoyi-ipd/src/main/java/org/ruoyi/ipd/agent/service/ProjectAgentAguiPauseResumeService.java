package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.message.Msg;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput;
import org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry;
import org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.security.IpdActor;
import java.time.Clock;
import java.util.*;

/** 在原 run/epoch 事务中记录中断及一次性消费，不创建第二运行、不导入客户端状态。 */
public final class ProjectAgentAguiPauseResumeService {
    public static final String INTERNAL_CHILD_COMPLETION = "ipd.server.child.completion";
    public static final String INTERNAL_RESUME_INTENT = "ipd.server.agui.resume.intent";
    private final AgentRunStore store;
    private final ProjectAgentRunService runs;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProjectAgentAguiPauseResumeService(AgentRunStore store, ProjectAgentRunService runs, ObjectMapper mapper) {
        this(store,runs,mapper,Clock.systemUTC());
    }
    public ProjectAgentAguiPauseResumeService(AgentRunStore store, ProjectAgentRunService runs,
            ObjectMapper mapper, Clock clock) {
        this.store=Objects.requireNonNull(store); this.runs=Objects.requireNonNull(runs);
        this.mapper=Objects.requireNonNull(mapper); this.clock=Objects.requireNonNull(clock);
    }

    public record PauseCheckpoint(long pauseSeq, long pauseEpoch, long checkpointVersion,
            String threadId, String runId, String ownerPersonId, Map<String,AguiEvent.Interrupt> pending,
            Map<String,ChildApproval> childApprovals) {
        public PauseCheckpoint { pending=Map.copyOf(pending); var copied=new LinkedHashMap<String,ChildApproval>();
            childApprovals.forEach((id,approval)->copied.put(id,freezeApproval(approval))); childApprovals=Map.copyOf(copied); }
        public PauseCheckpoint(long seq,long epoch,long version,String thread,String run,String owner,
                Map<String,AguiEvent.Interrupt> pending) { this(seq,epoch,version,thread,run,owner,pending,Map.of()); }
    }
    public record ChildResume(ChildApproval approval,List<Msg> messages) {
        public ChildResume { approval=freezeApproval(approval); messages=List.copyOf(messages); }
    }
    public record ResumeResult(boolean consumed, PauseCheckpoint pause, RunAgentInput input, List<Msg> messages,
            List<ChildResume> childResumes) {
        public ResumeResult { messages=List.copyOf(messages); childResumes=List.copyOf(childResumes); }
        public ResumeResult(boolean consumed,PauseCheckpoint pause,RunAgentInput input,List<Msg> messages) {
            this(consumed,pause,input,messages,List.of());
        }
    }
    private static ChildApproval freezeApproval(ChildApproval approval) {
        Objects.requireNonNull(approval);
        return approval.withCalls(List.copyOf(approval.calls()));
    }
    /** 必须核真实 SDK checkpoint 与当前业务批准；只读验证，不得通过客户端 approved 代替。 */
    @FunctionalInterface public interface TrustedGuard {
        Map<String,AguiTool> validate(IpdActor actor, IpdAgentRun lockedRun, PauseCheckpoint pause, RunAgentInput input);
    }

    /** 返回之后由执行器标记 paused 再释放订阅；此处绝不 dispose 或清理 checkpoint。 */
    public PauseCheckpoint pause(ProjectAgentRunHandle handle, Long runId, long checkpointVersion,
            Map<String,AguiEvent.Interrupt> pending) {
        requireHandle(handle,runId);
        if (checkpointVersion<0 || pending==null || pending.isEmpty())
            throw new IllegalArgumentException("中断检查点不完整");
        Map<String,AguiEvent.Interrupt> frozen=Map.copyOf(pending);
        for (var e:frozen.entrySet()) if (!e.getKey().equals(e.getValue().id()))
            throw new IllegalArgumentException("中断编号不一致");
        return handle.withActiveOwnership(() -> {
            IpdAgentRun run=run(runId);
            long epoch=epoch(run);
            if (!store.transition(runId,Set.of(AgentRunStatus.RUNNING),AgentRunStatus.WAITING_APPROVAL,null,Date.from(clock.instant())))
                throw new IllegalStateException("运行当前不能暂停");
            emitInterruptOutcome(handle,runId,frozen);
            Map<String,Object> detail=new LinkedHashMap<>();
            detail.put("reason","AGUI_INTERRUPT"); detail.put("pauseEpoch",epoch);
            detail.put("checkpointVersion",checkpointVersion); detail.put("threadId",String.valueOf(runId));
            detail.put("runId",String.valueOf(runId)); detail.put("ownerPersonId",String.valueOf(run.getPersonId()));
            detail.put("interrupts",frozen);
            handle.onStep("AWAIT_USER",detail);
            return new PauseCheckpoint(store.maxSeq(runId),epoch,checkpointVersion,String.valueOf(runId),
                String.valueOf(runId),String.valueOf(run.getPersonId()),frozen);
        });
    }

    /** 子调用身份与快照只接收原 SDK server hook 的 typed receipt。 */
    public PauseCheckpoint pauseChildren(ProjectAgentRunHandle handle,Long runId,long checkpointVersion,
            List<ChildApproval> approvals) {
        requireHandle(handle,runId);
        if (checkpointVersion<0 || approvals==null || approvals.isEmpty()) throw new IllegalArgumentException("子调用检查点不完整");
        return handle.withActiveOwnership(() -> {
            IpdAgentRun run=run(runId); long epoch=epoch(run);
            var rootScope=KernelScopeKey.of(String.valueOf(run.getProjectId()),String.valueOf(run.getPersonId()),
                ProjectAgentConstants.AGENT_ID,String.valueOf(runId));
            String user=rootScope.userId();
            Map<String,ChildApproval> receipts=new LinkedHashMap<>();
            Map<String,AguiEvent.Interrupt> pending=new LinkedHashMap<>();
            Set<String> calls=new HashSet<>();
            for (ChildApproval original:approvals) {
                if (original==null || !user.equals(original.userId()) || original.checkpointVersion()<0
                        || original.locator()==null || original.locator().isBlank() || original.sessionId()==null
                        || original.sessionId().isBlank() || rootScope.sessionId().equals(original.sessionId()) || original.replyId()==null || original.replyId().isBlank()
                        || original.calls()==null || original.calls().isEmpty()) throw new IllegalArgumentException("子调用身份或检查点无效");
                ChildApproval approval=freezeApproval(original);
                for (var call:approval.calls()) {
                    if (!calls.add(approval.sessionId()+"\0"+call.id())) throw new IllegalArgumentException("子调用重复");
                }
                for (var interrupt:childPending(approval).values()) {
                    String id="child-"+UUID.nameUUIDFromBytes((approval.locator()+"\0"+interrupt.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (pending.putIfAbsent(id,new AguiEvent.Interrupt(id,interrupt.reason(),interrupt.message(),
                            interrupt.toolCallId(),interrupt.responseSchema(),interrupt.expiresAt(),interrupt.metadata()))!=null)
                        throw new IllegalArgumentException("子中断重复");
                    receipts.put(id,approval);
                }
            }
            if (!store.transition(runId,Set.of(AgentRunStatus.RUNNING),AgentRunStatus.WAITING_APPROVAL,null,Date.from(clock.instant())))
                throw new IllegalStateException("运行当前不能暂停");
            emitInterruptOutcome(handle,runId,pending);
            Map<String,Object> detail=new LinkedHashMap<>();
            detail.put("reason","AGUI_INTERRUPT"); detail.put("pauseEpoch",epoch); detail.put("checkpointVersion",checkpointVersion);
            detail.put("threadId",String.valueOf(runId)); detail.put("runId",String.valueOf(runId));
            detail.put("ownerPersonId",String.valueOf(run.getPersonId())); detail.put("interrupts",pending);
            detail.put(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN,receipts);
            handle.onStep("AWAIT_USER",detail);
            return new PauseCheckpoint(store.maxSeq(runId),epoch,checkpointVersion,String.valueOf(runId),String.valueOf(runId),
                String.valueOf(run.getPersonId()),pending,receipts);
        });
    }

    private void emitInterruptOutcome(ProjectAgentRunHandle handle,Long runId,Map<String,AguiEvent.Interrupt> pending) {
        var terminal=new AguiEvent.RunFinished(String.valueOf(runId),String.valueOf(runId),null,
            new AguiEvent.RunFinishedInterruptOutcome(List.copyOf(pending.values())),clock.millis(),null);
        handle.onStep("AGUI",Map.of("events",List.of(new io.agentscope.core.agui.encoder.AguiEventEncoder().encodeToJson(terminal))));
    }

    private Map<String,AguiEvent.Interrupt> childPending(ChildApproval approval) {
        List<io.agentscope.core.message.ToolUseBlock> calls=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for (var call:approval.calls()) {
            if (call==null || call.id()==null || call.id().isBlank() || call.name()==null || call.name().isBlank()
                    || !ids.add(call.id())) throw new IllegalArgumentException("子调用快照无效");
            try {
                Map<String,Object> input=mapper.readValue(call.canonicalInput(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
                calls.add(io.agentscope.core.message.ToolUseBlock.builder().id(call.id()).name(call.name()).input(input)
                    .content(call.rawContent()).state(io.agentscope.core.message.ToolCallState.ASKING).build());
            } catch (java.io.IOException invalid) { throw new IllegalArgumentException("子调用参数快照无效",invalid); }
        }
        var context=new io.agentscope.core.agui.adapter.strategy.AguiStreamContext("child","child",
            io.agentscope.core.agui.adapter.AguiAdapterConfig.builder().build());
        new io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry().convert(
            new io.agentscope.core.event.RequireUserConfirmEvent(approval.replyId(),calls),context);
        Map<String,AguiEvent.Interrupt> result=new LinkedHashMap<>();
        for (var interrupt:context.getPendingInterrupts()) if (result.putIfAbsent(interrupt.id(),interrupt)!=null)
            throw new IllegalArgumentException("子中断重复");
        if (result.size()!=calls.size()) throw new IllegalArgumentException("子调用缺少官方中断");
        return Map.copyOf(result);
    }

    private RunAgentInput onlyResumes(RunAgentInput input,List<io.agentscope.core.agui.model.AguiResume> resume,boolean keepMessages) {
        return RunAgentInput.builder().threadId(input.getThreadId()).runId(input.getRunId()).tools(input.getTools())
            .messages(keepMessages?input.getMessages():List.of()).context(input.getContext()).state(input.getState())
            .forwardedProps(input.getForwardedProps()).resume(resume).build();
    }

    public ResumeResult consume(IpdActor actor, ProjectAgentRunHandle handle, Long runId,
            long expectedPauseSeq, RunAgentInput input, TrustedGuard guard) {
        requireHandle(handle,runId); Objects.requireNonNull(actor); Objects.requireNonNull(input);
        Objects.requireNonNull(guard,"trusted checkpoint and approval guard required");
        RunAgentInput frozen=ProjectAgentAguiInput.freeze(input);
        String inputDigest=ProjectAgentAguiInput.digest(frozen);
        // 原 service 会重新读取 Person、项目可见性及 run owner，不用客户端身份或角色快照。
        runs.get(actor,runId);
        return handle.withActiveOwnership(() -> {
            runs.get(actor,runId);
            IpdAgentRun run=run(runId);
            if (!Objects.equals(run.getPersonId(),actor.id())) throw new IllegalArgumentException("只能恢复本人运行");
            History history=history(runId);
            PauseCheckpoint pause=history.latest();
            if (pause==null || pause.pauseSeq()!=expectedPauseSeq)
                throw new IllegalArgumentException("恢复请求已过期或中断不存在");
            if (history.consumed().containsKey(expectedPauseSeq)) {
                requireSameResponse(history.consumed().get(expectedPauseSeq),inputDigest);
                return new ResumeResult(false,pause,frozen,List.of());
            }
            ProjectAgentAguiResumeValidation.validate(frozen,
                new ProjectAgentAguiResumeValidation.Binding(pause.threadId(),pause.runId(),pause.ownerPersonId(),
                    pause.pauseEpoch(),pause.checkpointVersion(),pause.pending()),String.valueOf(actor.id()),
                pause.pauseEpoch(),pause.checkpointVersion(),clock.instant());
            if (!AgentRunStatus.WAITING_APPROVAL.name().equals(run.getStatus()))
                throw new IllegalStateException("运行当前没有等待恢复的中断");
            Map<String,AguiTool> authorizedTools=Objects.requireNonNull(guard.validate(actor,run,pause,
                ProjectAgentAguiInput.freeze(frozen)),
                "trusted frontend tool catalog required");
            if (!frozen.getTools().isEmpty()) {
                Set<String> requested=new HashSet<>();
                for (AguiTool tool:frozen.getTools()) {
                    if (tool==null || !requested.add(tool.getName()))
                        throw new IllegalArgumentException("恢复工具重复或无效");
                }
                if (!requested.equals(authorizedTools.keySet()))
                    throw new IllegalArgumentException("恢复请求不得变更原前端工具集合");
            }
            List<AguiTool> originalTools=authorizedTools.values().stream()
                .sorted(Comparator.comparing(AguiTool::getName)).toList();
            // bind 前已经校验原 client IDs；本次目录仍由服务端重新授权，不允许静默忽略 tools。
            RunAgentInput bound=ProjectAgentAguiInput.freeze(
                ProjectAgentAguiInput.bind(ProjectAgentAguiInput.withFrontendTools(frozen,originalTools),
                    pause.threadId(),pause.runId(),authorizedTools));
            ResumeMessages prepared=prepareMessages(bound,pause);
            RunAgentInput recoveryInput=RunAgentInput.builder().threadId(bound.getThreadId()).runId(bound.getRunId())
                .messages(bound.getMessages()).tools(bound.getTools()).resume(bound.getResume()).build();
            String normalized=ProjectAgentAguiInput.encodeRecoveryInput(recoveryInput);
            ResumeIntent intent=new ResumeIntent(1,pause.pauseSeq(),pause.pauseEpoch(),pause.checkpointVersion(),
                pause.threadId(),pause.runId(),pause.ownerPersonId(),epoch(run),inputDigest,
                ProjectAgentAguiInput.digest(recoveryInput),normalized,
                prepared.children().stream().map(ChildResume::approval).toList());
            if (!store.transition(runId,Set.of(AgentRunStatus.WAITING_APPROVAL),AgentRunStatus.RUNNING,null,Date.from(clock.instant())))
                throw new IllegalStateException("中断已被其他操作消费");
            handle.onStep("AGUI_RESUMED",Map.of("pauseSeq",expectedPauseSeq,"pauseEpoch",pause.pauseEpoch(),
                "checkpointVersion",pause.checkpointVersion(),"executionEpoch",epoch(run),"inputDigest",inputDigest,
                INTERNAL_RESUME_INTENT,intent));
            return new ResumeResult(true,pause,bound,prepared.root(),prepared.children());
        });
    }

    /** 同原事件事务持久化的规范化恢复意图；不含客户端 state、context、forwardedProps。 */
    public record ResumeIntent(int formatVersion,long pauseSeq,long pauseEpoch,long checkpointVersion,
            String threadId,String runId,String ownerPersonId,long executionEpoch,String inputDigest,
            String normalizedDigest,String normalizedInputJson,List<ChildApproval> childGroups) {
        public ResumeIntent { childGroups=childGroups.stream().map(ProjectAgentAguiPauseResumeService::freezeApproval).toList(); }
    }
    public record RecoveredResume(ResumeIntent intent,PauseCheckpoint pause,List<Msg> messages,List<ChildResume> childResumes) {
        public RecoveredResume { messages=List.copyOf(messages);childResumes=List.copyOf(childResumes); }
    }
    private record ResumeMessages(List<Msg> root,List<ChildResume> children) { }
    private ResumeMessages prepareMessages(RunAgentInput bound,PauseCheckpoint pause) {
        Map<String,AguiEvent.Interrupt> rootPending=new LinkedHashMap<>(pause.pending());
        pause.childApprovals().keySet().forEach(rootPending::remove);
        List<Msg> messages=ProjectAgentAguiInput.messages(onlyResumes(bound,bound.getResume().stream()
            .filter(r->!pause.childApprovals().containsKey(r.getInterruptId())).toList(),true),rootPending);
        Map<String,List<io.agentscope.core.agui.model.AguiResume>> responses=new LinkedHashMap<>();
        Map<String,ChildApproval> groups=new LinkedHashMap<>();
        for (var response:bound.getResume()) {
            ChildApproval approval=pause.childApprovals().get(response.getInterruptId());
            if (approval==null) continue;
            var official=childPending(approval);
            String callId=pause.pending().get(response.getInterruptId()).toolCallId();
            String nativeId=official.values().stream().filter(i->Objects.equals(callId,i.toolCallId()))
                .map(AguiEvent.Interrupt::id).findFirst().orElseThrow(()->new IllegalArgumentException("子中断映射缺失"));
            String group=approval.sessionId();
            ChildApproval previous=groups.get(group);
            if (previous!=null && !previous.withCalls(approval.calls()).equals(approval))
                throw new IllegalArgumentException("子检查点或恢复身份冲突");
            if (previous==null) groups.put(group,approval);
            else {
                Map<String,ProjectAgentChildLineageRegistry.CallSnapshot> merged=new LinkedHashMap<>();
                previous.calls().forEach(c->merged.put(c.id(),c)); approval.calls().forEach(c->merged.put(c.id(),c));
                groups.put(group,previous.withCalls(List.copyOf(merged.values())));
            }
            responses.computeIfAbsent(group,k->new ArrayList<>()).add(new io.agentscope.core.agui.model.AguiResume(
                nativeId,response.getStatus(),response.getPayload()));
        }
        List<ChildResume> childResumes=new ArrayList<>();
        for (var group:groups.entrySet()) childResumes.add(new ChildResume(group.getValue(),
            ProjectAgentAguiInput.messages(onlyResumes(bound,responses.get(group.getKey()),false),childPending(group.getValue()))));
        return new ResumeMessages(messages,List.copyOf(childResumes));
    }

    /** 仅原恢复执行器在持有当前租约后回读；回读不消费、不派发，也不授予跨 epoch 重执行权。 */
    public RecoveredResume loadConsumedIntent(ProjectAgentRunHandle handle,Long runId,long pauseSeq) {
        requireHandle(handle,runId);
        return handle.withActiveOwnership(() -> {
            IpdAgentRun run=run(runId); History history=history(runId);PauseCheckpoint pause=history.latest();
            ResumeIntent intent=history.intents().get(pauseSeq);
            if (pause==null || pause.pauseSeq()!=pauseSeq || intent==null || intent.formatVersion()!=1
                    || !AgentRunStatus.RUNNING.name().equals(run.getStatus())
                    || !String.valueOf(runId).equals(intent.runId()) || !String.valueOf(runId).equals(intent.threadId())
                    || !String.valueOf(run.getPersonId()).equals(intent.ownerPersonId())
                    || !intent.ownerPersonId().equals(pause.ownerPersonId()) || intent.pauseSeq()!=pause.pauseSeq()
                    || intent.pauseEpoch()!=pause.pauseEpoch() || intent.checkpointVersion()!=pause.checkpointVersion()
                    || intent.executionEpoch()<1 || intent.executionEpoch()>epoch(run)
                    || !Objects.equals(history.consumedEpochs().get(pauseSeq),intent.executionEpoch()))
                throw new IllegalStateException("当前运行没有完整绑定的持久恢复意图");
            requireSameResponse(history.consumed().get(pauseSeq),intent.inputDigest());
            RunAgentInput normalized=ProjectAgentAguiInput.decodeRecoveryInput(intent.normalizedInputJson());
            if (!intent.threadId().equals(normalized.getThreadId()) || !intent.runId().equals(normalized.getRunId())
                    || !Objects.equals(intent.normalizedDigest(),ProjectAgentAguiInput.digest(normalized)))
                throw new IllegalStateException("持久恢复意图内容或摘要不一致");
            Set<String> replies=new HashSet<>();
            for(var response:normalized.getResume())
                if(response==null || !replies.add(response.getInterruptId()) || (!response.isResolved() && !response.isCancelled())
                        || (response.isCancelled() && response.getPayload()!=null))
                    throw new IllegalStateException("持久恢复响应无效");
            if(!replies.equals(pause.pending().keySet())) throw new IllegalStateException("持久恢复响应未完整覆盖原中断");
            ResumeMessages messages=prepareMessages(normalized,pause);
            if(!messages.children().stream().map(ChildResume::approval).toList().equals(intent.childGroups()))
                throw new IllegalStateException("持久子恢复分组与原中断不一致");
            return new RecoveredResume(intent,pause,messages.root(),messages.children());
        });
    }

    public RecoveredResume loadLatestConsumedIntent(ProjectAgentRunHandle handle,Long runId) {
        requireHandle(handle,runId);
        return handle.withActiveOwnership(()-> {
            var latest=history(runId).latest();
            if(latest==null) throw new IllegalStateException("运行没有持久中断");
            return loadConsumedIntent(handle,runId,latest.pauseSeq());
        });
    }

    /** 新租约重接前只接受没有开始工具效果且检查点已由 trusted guard 验证的原意图。 */
    public void authorizeRecoveredIntent(ProjectAgentRunHandle handle,Long runId,RecoveredResume recovered) {
        requireHandle(handle,runId);Objects.requireNonNull(recovered);
        handle.withActiveOwnership(()-> {
            var current=loadConsumedIntent(handle,runId,recovered.intent().pauseSeq());
            if(!current.intent().equals(recovered.intent())) throw new IllegalStateException("持久恢复意图已变更");
            long after=current.intent().pauseSeq();int attempts=0;
            while(true) {
                var page=store.listEvents(runId,after,ProjectAgentConstants.EVENTS_PAGE_LIMIT);
                for(var event:page) {
                    after=event.getSeq();
                    if(!"STEP".equals(event.getEventType())) continue;
                    try {
                        var data=mapper.readTree(event.getPayload());
                        if("TOOL_EXECUTION".equals(data.path("kind").asText()) && "STARTED".equals(data.path("state").asText()))
                            throw new IllegalStateException("原恢复工具效果需先对账，不能自动重放");
                        if("AGUI_RESUME_RECOVERED".equals(data.path("kind").asText())
                                && data.path("pauseSeq").asLong()==current.intent().pauseSeq()) attempts++;
                    } catch(java.io.IOException invalid) { throw new IllegalStateException("恢复对账事件无法读取",invalid); }
                }
                if(page.isEmpty()) break;
            }
            if(attempts>=2) throw new IllegalStateException("原恢复意图已达到有限恢复次数");
            handle.onStep("AGUI_RESUME_RECOVERED",Map.of("pauseSeq",current.intent().pauseSeq(),
                "originalExecutionEpoch",current.intent().executionEpoch(),"executionEpoch",epoch(run(runId)),
                "inputDigest",current.intent().inputDigest(),"normalizedDigest",current.intent().normalizedDigest()));
            return null;
        });
    }

    public void recordChildCompletion(ProjectAgentRunHandle handle,Long runId,
            ProjectAgentChildLineageRegistry.ChildCompletion completion) {
        requireHandle(handle,runId);Objects.requireNonNull(completion);
        handle.withActiveOwnership(()-> {
            requireConsumedChild(handle,runId,completion.approval());
            if(completion.completedCheckpointVersion()<=completion.approval().checkpointVersion()
                || !Set.of("MODEL_STOP","STRUCTURED_OUTPUT","ALL_TOOLS_DENIED").contains(completion.generateReason())
                || completion.finalText()==null) throw new IllegalArgumentException("原子调用没有完成证据");
            var existing=history(runId).completions().get(completionKey(completion));
            if(existing!=null) {
                if(!existing.equals(completion)) throw new IllegalStateException("原子完成证据冲突");
                return null;
            }
            handle.onStep("CHILD_RESUME_COMPLETED",Map.of("executionEpoch",epoch(run(runId)),
                INTERNAL_CHILD_COMPLETION,completion));
            return null;
        });
    }
    /** 不授予再次执行，只回读原持久结果，派发器还须核当前 factory 与 SDK 完成检查点。 */
    public List<ProjectAgentChildLineageRegistry.ChildCompletion> loadChildCompletions(ProjectAgentRunHandle handle,Long runId) {
        requireHandle(handle,runId);
        return handle.withActiveOwnership(()-> {
            var run=run(runId);
            var root=KernelScopeKey.of(String.valueOf(run.getProjectId()),String.valueOf(run.getPersonId()),
                ProjectAgentConstants.AGENT_ID,String.valueOf(runId));
            var results=history(runId).completions().values().stream().toList();
            for(var completion:results) if(!root.userId().equals(completion.approval().userId()))
                throw new IllegalStateException("子完成证据归属错误");
            return results;
        });
    }
    private static String completionKey(ProjectAgentChildLineageRegistry.ChildCompletion completion) {
        var approval=completion.approval();return approval.locator()+"\0"+approval.checkpointVersion()+"\0"+approval.replyId();
    }

    /** SDK 子恢复执行前重核已消费的私有回执；定位字符串本身不产生授权。 */
    public void requireConsumedChild(ProjectAgentRunHandle handle,Long runId,ChildApproval approval) {
        requireHandle(handle,runId); Objects.requireNonNull(approval);
        handle.withActiveOwnership(() -> {
            IpdAgentRun run=run(runId); History history=history(runId); PauseCheckpoint pause=history.latest();
            if (pause==null || !AgentRunStatus.RUNNING.name().equals(run.getStatus())
                    || !String.valueOf(run.getPersonId()).equals(pause.ownerPersonId())
                    || !String.valueOf(runId).equals(pause.runId())
                    || !String.valueOf(runId).equals(pause.threadId())
                    || !history.consumed().containsKey(pause.pauseSeq())
                    || history.consumed().get(pause.pauseSeq()).isBlank()
                    || !Objects.equals(history.effectiveEpochs().get(pause.pauseSeq()),epoch(run)))
                throw new IllegalStateException("子恢复没有当前执行 epoch 的持久消费凭据");
            var scope=KernelScopeKey.of(String.valueOf(run.getProjectId()),String.valueOf(run.getPersonId()),
                ProjectAgentConstants.AGENT_ID,String.valueOf(runId));
            if (!scope.userId().equals(approval.userId()) || scope.sessionId().equals(approval.sessionId())
                    || approval.calls()==null || approval.calls().isEmpty())
                throw new IllegalArgumentException("子恢复身份与原运行不匹配");
            Map<String,ProjectAgentChildLineageRegistry.CallSnapshot> expected=new LinkedHashMap<>();
            for (ChildApproval receipt:pause.childApprovals().values()) {
                if (!Objects.equals(receipt.sessionId(),approval.sessionId())) continue;
                if (!sameApprovalIdentity(receipt,approval))
                    throw new IllegalStateException("子恢复的 factory 或父调用绑定与原回执不匹配");
                for (var call:receipt.calls()) {
                    var previous=expected.putIfAbsent(call.id(),call);
                    if (previous!=null && !previous.equals(call)) throw new IllegalStateException("子调用持久回执冲突");
                }
            }
            Map<String,ProjectAgentChildLineageRegistry.CallSnapshot> actual=new LinkedHashMap<>();
            for (var call:approval.calls()) if (call==null || actual.putIfAbsent(call.id(),call)!=null)
                throw new IllegalArgumentException("子恢复调用重复或为空");
            if (expected.isEmpty() || !expected.equals(actual))
                throw new IllegalStateException("子恢复调用没有完整匹配原持久回执");
            return null;
        });
    }

    /** 比较 record 的全部非调用字段，新增 server authority 字段也不能被忽略。 */
    private boolean sameApprovalIdentity(ChildApproval first,ChildApproval second) {
        com.fasterxml.jackson.databind.node.ObjectNode a=mapper.valueToTree(first);
        com.fasterxml.jackson.databind.node.ObjectNode b=mapper.valueToTree(second);
        a.remove("calls"); b.remove("calls"); return a.equals(b);
    }

    /** 原 API 重试只回读原消费证据；不申请执行租约、不写入、不调用审批回调。 */
    public boolean replayConsumed(IpdActor actor,Long runId,long pauseSeq,RunAgentInput input) {
        Objects.requireNonNull(actor); Objects.requireNonNull(input);
        RunAgentInput frozen=ProjectAgentAguiInput.freeze(input);
        runs.get(actor,runId);
        History history=history(runId);
        if (history.latest()==null || history.latest().pauseSeq()!=pauseSeq)
            throw new IllegalArgumentException("恢复请求已过期或中断不存在");
        if (!history.consumed().containsKey(pauseSeq)) return false;
        requireSameResponse(history.consumed().get(pauseSeq),ProjectAgentAguiInput.digest(frozen));
        return true;
    }
    private static void requireSameResponse(String recorded,String requested) {
        if (recorded==null || recorded.isBlank() || !recorded.equals(requested))
            throw new IllegalArgumentException("当前中断已消费，恢复响应与原请求不一致");
    }
    private record History(PauseCheckpoint latest, Map<Long,String> consumed,Map<Long,Long> consumedEpochs,Map<Long,ResumeIntent> intents,Map<Long,Long> effectiveEpochs,Map<String,ProjectAgentChildLineageRegistry.ChildCompletion> completions) { }
    private History history(Long runId) {
        PauseCheckpoint latest=null; Map<Long,String> consumed=new HashMap<>();
        Map<Long,Long> consumedEpochs=new HashMap<>(); Map<Long,ResumeIntent> intents=new HashMap<>(); Map<Long,Long> effectiveEpochs=new HashMap<>(); Map<String,ProjectAgentChildLineageRegistry.ChildCompletion> completions=new LinkedHashMap<>(); long after=0;
        while (true) {
            var page=store.listEvents(runId,after,ProjectAgentConstants.EVENTS_PAGE_LIMIT);
            for (var event:page) {
                if (event.getSeq()<=after) throw new IllegalStateException("事件游标未前进");
                after=event.getSeq();
                if (!"STEP".equals(event.getEventType())) continue;
                try {
                    var data=mapper.readTree(event.getPayload());
                    String kind=data.path("kind").asText();
                    if ("AGUI_RESUMED".equals(kind)) {
                        // pauseSeq/executionEpoch 历史版本同样落成字符串，longValue 恒 0 会让两次消费
                        // 折到同一 key，误报「持久恢复意图重复」；asLong 兼容 STRING/INTEGER 两态。
                        long seq=data.path("pauseSeq").asLong();
                        consumed.put(seq,data.path("inputDigest").asText(""));
                        if (data.hasNonNull("executionEpoch")) {
                            consumedEpochs.put(seq,data.path("executionEpoch").asLong());
                            effectiveEpochs.put(seq,data.path("executionEpoch").asLong());
                        }
                        if(data.hasNonNull(INTERNAL_RESUME_INTENT)) {
                            ResumeIntent intent=mapper.treeToValue(data.get(INTERNAL_RESUME_INTENT),ResumeIntent.class);
                            if(intents.putIfAbsent(seq,intent)!=null) throw new IllegalStateException("持久恢复意图重复");
                        }
                    }
                    if("CHILD_RESUME_COMPLETED".equals(kind)) {
                        if(!data.hasNonNull(INTERNAL_CHILD_COMPLETION) || data.path("executionEpoch").asLong()<1)
                            throw new IllegalStateException("子完成事件缺少私有证据");
                        var completion=mapper.treeToValue(data.get(INTERNAL_CHILD_COMPLETION),ProjectAgentChildLineageRegistry.ChildCompletion.class);
                        var previous=completions.putIfAbsent(completionKey(completion),completion);
                        if(previous!=null && !previous.equals(completion)) throw new IllegalStateException("持久子完成证据冲突");
                    }
                    if("AGUI_RESUME_RECOVERED".equals(kind)) {
                        long seq=data.path("pauseSeq").asLong();var intent=intents.get(seq);
                        if(intent==null || intent.executionEpoch()!=data.path("originalExecutionEpoch").asLong()
                            || !intent.inputDigest().equals(data.path("inputDigest").asText())
                            || !intent.normalizedDigest().equals(data.path("normalizedDigest").asText())
                            || data.path("executionEpoch").asLong()<intent.executionEpoch())
                            throw new IllegalStateException("恢复接管凭据与原意图不一致");
                        effectiveEpochs.put(seq,data.path("executionEpoch").asLong());
                    }
                    if (!"AWAIT_USER".equals(kind) || !"AGUI_INTERRUPT".equals(data.path("reason").asText())) continue;
                    if (!data.hasNonNull("pauseEpoch") || !data.hasNonNull("checkpointVersion") || !data.path("interrupts").isObject())
                        throw new IllegalStateException("持久中断检查点不完整");
                    Map<String,AguiEvent.Interrupt> pending=mapper.convertValue(data.path("interrupts"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String,AguiEvent.Interrupt>>() {});
                    // AWAIT_USER 载荷的历史版本把这两个字段落成字符串；asLong 兼容 STRING/INTEGER 两态，
                    // longValue 对 TextNode 恒 0，会把 checkpointVersion 读成 0 导致 resume 恒被 guard 拒绝。
                    latest=new PauseCheckpoint(event.getSeq(),data.path("pauseEpoch").asLong(),data.path("checkpointVersion").asLong(),
                        data.path("threadId").asText(),data.path("runId").asText(),data.path("ownerPersonId").asText(),pending,
                        data.has(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN)?mapper.convertValue(data.get(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String,ChildApproval>>() {}):Map.of());
                } catch (java.io.IOException invalid) { throw new IllegalStateException("中断事件无法读取",invalid); }
            }
            if (page.isEmpty()) return new History(latest,Map.copyOf(consumed),Map.copyOf(consumedEpochs),Map.copyOf(intents),Map.copyOf(effectiveEpochs),Collections.unmodifiableMap(completions));
        }
    }
    private IpdAgentRun run(Long runId) { return store.findRun(runId).orElseThrow(() -> new IllegalArgumentException("运行不存在")); }
    private static long epoch(IpdAgentRun run) {
        if (run.getVersion()==null || run.getVersion()<1) throw new IllegalStateException("运行没有执行 epoch");
        return run.getVersion();
    }
    private static void requireHandle(ProjectAgentRunHandle handle,Long runId) {
        if (handle==null || runId==null || !runId.equals(handle.runId())) throw new IllegalArgumentException("运行 handle 不匹配");
    }
}
