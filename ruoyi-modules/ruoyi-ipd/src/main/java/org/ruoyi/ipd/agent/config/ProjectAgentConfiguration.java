package org.ruoyi.ipd.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.kernel.AgentScopeProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentModelAssembler;
import org.ruoyi.ipd.agent.kernel.ProjectKnowledgeFragmentTextSearch;
import org.ruoyi.ipd.agent.kernel.ProjectKnowledgeRetriever;
import org.ruoyi.ipd.agent.kernel.ProjectKnowledgeVectorSearch;
import org.ruoyi.ipd.agent.service.AiFeedbackService;
import org.ruoyi.ipd.agent.service.DemandCatalogBinder;
import org.ruoyi.ipd.agent.service.DemandTriageRun;
import org.ruoyi.ipd.agent.service.ProjectAgentCapabilityService;
import org.ruoyi.ipd.agent.service.ProjectAgentRunExecutor;
import org.ruoyi.ipd.agent.service.ProjectAgentRunPlanner;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.AiFeedbackStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductLineNameMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.AiModelConfigService;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 项目智能体装配。
 *
 * <p>目录、服务与官方执行内核恒定装配。业务访问、审批与运行所有权由原权威检查，
 * 资源提供者是否就绪按实际接线和运行结果报告。
 */
@Configuration
public class ProjectAgentConfiguration {

    /**
     * 内置能力清单（缺失即启动失败，fail-fast）。
     *
     * @param mapper Jackson
     * @return 清单
     */
    @Bean
    public CapabilityManifest projectAgentCapabilityManifest(ObjectMapper mapper) {
        return CapabilityManifest.load(CapabilityManifest.DEFAULT_RESOURCE, mapper);
    }

    /** @return Skill 目录（ClasspathSkillRepository 读 ipd-skills） */
    @Bean
    public ProjectAgentSkillCatalog projectAgentSkillCatalog(CapabilityManifest manifest) {
        return new ProjectAgentSkillCatalog("ipd-skills", manifest);
    }

    /** @return 工具目录 */
    @Bean
    public ProjectAgentToolCatalog projectAgentToolCatalog(CapabilityManifest manifest) {
        return new ProjectAgentToolCatalog(manifest);
    }

    /** @return 模型目录（ai_model_configs 权威） */
    @Bean
    public ProjectAgentModelCatalog projectAgentModelCatalog(AiModelConfigMapper mapper,
                                                             AiModelConfigService modelConfigService) {
        return new ProjectAgentModelCatalog(mapper, modelConfigService);
    }

    /** @return 校验与冻结（含按 actionCode 查库绑定 Skill） */
    @Bean
    public ProjectAgentRunPlanner projectAgentRunPlanner(CapabilityManifest manifest,
                                                         ProjectAgentSkillCatalog skills,
                                                         ProjectAgentToolCatalog tools,
                                                         ProjectAgentModelCatalog models,
                                                         IpdActionSkillMapService skillMapService) {
        return new ProjectAgentRunPlanner(manifest, skills, tools, models, skillMapService);
    }

    @Bean
    public org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership projectAgentRunOwnership(org.redisson.api.RedissonClient client) {
        return new org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership(client);
    }

    // SDK store.close会shutdown注入的共享Redis客户端；生命周期仍由原RedissonBean管理。
    @Bean(value = "projectAgentStateStore", destroyMethod = "")
    public io.agentscope.core.state.AgentStateStore projectAgentStateStore(org.redisson.api.RedissonClient client) {
        return org.ruoyi.chat.kernel.AgentScopeRedisStateStores.create(client, "ipd:project-agent:state:");
    }

    @Bean
    public org.ruoyi.ipd.agent.service.ProjectAgentRunRecovery projectAgentRunRecovery(AgentRunStore store,
            org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership ownership, ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Qualifier("projectAgentStateStore") io.agentscope.core.state.AgentStateStore stateStore) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var recovery = new org.ruoyi.ipd.agent.service.ProjectAgentRunRecovery(store, ownership, transaction, mapper);
        recovery.setStateStore(stateStore);
        return recovery;
    }

    /**
     * 官方执行内核（恒定装配）。
     *
     * @param embeddingService 已审核文档向量检索
     * @param fragmentMapper 产品知识库正文检索；向量库不可用时仍走这条
     * @param knowledgeAccessGate 知识库读面门禁，不在这里放宽
     * @param knowledgeRetrievalService 已有知识库向量检索，不新建客户端
     * @param workspaceRoot 工作区根
     * @param maxIters 最大迭代数
     * @return 内核
     */
    @Bean
    public ProjectAgentKernel projectAgentKernel(
            AiDocEmbeddingService embeddingService,
            ProjectKnowledgeFragmentMapper fragmentMapper,
            KnowledgeAccessGate knowledgeAccessGate,
            KnowledgeRetrievalService knowledgeRetrievalService,
            AiGateway aiGateway,
            @org.springframework.beans.factory.annotation.Qualifier("projectAgentStateStore") io.agentscope.core.state.AgentStateStore stateStore,
            @Value("${ipd.project-agent.workspace-root:${java.io.tmpdir}/ipd-project-agent-workspace}") Path workspaceRoot,
            @Value("${ipd.project-agent.max-iters:8}") int maxIters,
            ProductLineNameMapper lineNames,
            IpdCopilotAccess runtimeAccess, PersonMapper runtimePersons,
            @org.springframework.beans.factory.annotation.Qualifier("agentScopeAuditHook") io.agentscope.core.hook.Hook auditHook) {
        configureFiniteShutdown(shutdownManager());
        ProjectKnowledgeVectorSearch knowledgeSearch = new ProjectKnowledgeVectorSearch(
            knowledgeAccessGate, knowledgeRetrievalService,
            projectId -> fragmentMapper.listInScope(
                ProjectKnowledgeFragmentTextSearch.KNOWLEDGE_TENANT_ID, projectId),
            fragmentMapper::findLiveFragment);
        ProjectKnowledgeRetriever retriever = new ProjectKnowledgeRetriever(
            embeddingService::retrieveContextStrict,
            new ProjectKnowledgeFragmentTextSearch(fragmentMapper),
            knowledgeSearch::search);
        // 传具体检索器。方法引用 retriever::retrieve 只会绑到三参接口，
        // 内核认不出具体类，人员传不进去，向量门禁会按未登录拒绝。
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler(aiGateway),
            retriever, workspaceRoot, maxIters, lineNames, auditHook);
        kernel.setStateStore(stateStore);
        kernel.setRuntimeAccess(spec -> requireCurrentRuntimeAccess(runtimeAccess, runtimePersons, spec));
        return kernel;
    }

    /** 每次真实调用从服务器人员表恢复 Actor，再复用原项目权限权威。 */
    static void requireCurrentRuntimeAccess(IpdCopilotAccess access, PersonMapper persons,
            org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec spec) {
        org.ruoyi.ipd.domain.Person person = persons.selectById(spec.personId());
        if (person == null || !spec.personId().equals(person.getId())) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
        org.ruoyi.ipd.security.IpdActor actor = new org.ruoyi.ipd.security.IpdActor(
            person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
        String tenant = access.requireVisible(actor, spec.projectId());
        if (!java.util.Objects.equals(tenant, spec.tenantId())) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
    }

    /** 原运行服务构造后再装配暂停消费者，避免循环构造或第二运行轨。 */
    @Bean
    public org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService projectAgentAguiPauseResumeService(
            AgentRunStore store, ProjectAgentRunService runs, ObjectMapper mapper,
            ProjectAgentRunExecutor executor, ProjectAgentKernel kernel,
            org.ruoyi.ipd.agent.service.ProjectAgentRunRecovery recovery, org.ruoyi.ipd.mapper.PersonMapper persons,
            @org.springframework.beans.factory.annotation.Qualifier("projectAgentStateStore") io.agentscope.core.state.AgentStateStore stateStore) {
        var service = new org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService(store, runs, mapper);
        executor.setAguiPauseResume(service);
        recovery.setResumeRecovery(candidate-> {
            var person=persons.selectById(candidate.getPersonId());
            if(person==null) throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
            return runs.recoverAguiIntent(new org.ruoyi.ipd.security.IpdActor(person.getId(),person.getName(),
                person.getPersonType(),person.getGroupId()),candidate.getId());
        });
        runs.setAguiPauseResume(service, new org.ruoyi.ipd.agent.service.ProjectAgentAguiCheckpointGuard(stateStore,
            (actor, input) -> runs.resolveAguiResumeTools(actor, Long.valueOf(input.getRunId()), input),
            (actor,run,input,approval)-> {
                if (!(kernel instanceof AgentScopeProjectAgentKernel official))
                    throw new IllegalStateException("官方子恢复内核未装配");
                official.preflightChildResume(runs.prepareAguiPreflight(actor,run.getId(),input),approval);
            }));
        executor.setPausedCheckpointCleanup(run -> {
            var scope = org.ruoyi.chat.kernel.KernelScopeKey.of(String.valueOf(run.getProjectId()),
                String.valueOf(run.getPersonId()), ProjectAgentConstants.AGENT_ID, String.valueOf(run.getId()));
            for (String session : stateStore.listSessionIds(scope.userId())) {
                if (scope.sessionId().equals(session) || session.startsWith(scope.sessionId() + "/official/"))
                    stateStore.delete(scope.userId(), session);
            }
            stateStore.delete(scope.userId(), scope.sessionId());
        });
        return service;
    }

    /** SDK关闭管理器是全JVM单例；只在启用项目内核时补默认有限等待。 */
    GracefulShutdownManager shutdownManager() {
        return GracefulShutdownManager.getInstance();
    }

    static void configureFiniteShutdown(GracefulShutdownManager manager) {
        synchronized (manager) {
            GracefulShutdownConfig current = manager.getConfig();
            if (current.shutdownTimeout() == null) {
                // SDK钩子另加固定5秒interrupt grace；已有有限配置及推理策略原样保留。
                manager.setConfig(new GracefulShutdownConfig(Duration.ofSeconds(15),
                    current.partialReasoningPolicy()));
            }
        }
    }

    /** @return 执行器（运行失败按原终态合同收口） */
    @Bean
    public ProjectAgentRunExecutor projectAgentRunExecutor(
            AgentRunStore store, ArtifactVersionStore artifactStore,
            ObjectProvider<ProjectAgentKernel> kernel, ObjectMapper mapper,
            AiModelUsageLedgerService usageLedger,
            DemandCatalogBinder demandBinder, PlatformTransactionManager transactionManager,
            org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership ownership,
            @Value("${ipd.project-agent.max-concurrent-runs:4}") int maxConcurrentRuns) {
        ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(store, artifactStore,
            kernel.getIfAvailable(), mapper, Schedulers.boundedElastic(), System::currentTimeMillis,
            maxConcurrentRuns);
        executor.setUsageLedger(usageLedger);
        executor.setDemandBinder(demandBinder);
        TransactionTemplate finishTransaction = new TransactionTemplate(transactionManager);
        finishTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        executor.setFinishTransaction(finishTransaction);
        executor.setOwnership(ownership);
        return executor;
    }

    /** @return 分拣运行成功后的需求回写 */
    @Bean
    public DemandCatalogBinder demandCatalogBinder(
            RequirementMapper requirementMapper,
            ProductLineMapper lineMapper,
            ProductMapper productMapper) {
        return new DemandCatalogBinder(requirementMapper, lineMapper, productMapper);
    }

    /** @return 运行服务 */
    @Bean
    public ProjectAgentRunService projectAgentRunService(
            IpdCopilotAccess access, ProjectAgentRunPlanner planner, AgentRunStore store,
            ArtifactVersionStore artifactStore, AiDocumentService documentService,
            ProjectMapper projectMapper, ProductMapper productMapper,
            ProjectAgentRunExecutor executor, ObjectMapper mapper,
            @Value("${ipd.project-agent.run-timeout-seconds:300}") long timeoutSeconds,
            ProjectAgentModelCatalog modelCatalog,
            StageActionService stageActionService, ProductLineNameMapper lineNames) {
        ProjectAgentRunService service = new ProjectAgentRunService(true, access, planner, store,
            artifactStore, documentService, projectMapper, productMapper, executor, mapper,
            System::currentTimeMillis, Duration.ofSeconds(Math.max(30, timeoutSeconds)));
        service.setModelCatalog(modelCatalog);
        service.setStageActionService(stageActionService);
        service.setProductLineNames(lineNames);
        return service;
    }

    /** @return 未挂线游客需求接到分拣项目的现有创建运行 */
    @Bean
    public DemandTriageRun demandTriageRun(
            ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper, PersonMapper personMapper,
            ProjectAgentModelCatalog modelCatalog, CapabilityManifest manifest,
            ProjectAgentRunService projectAgentRunService) {
        return new DemandTriageRun(projectMapper, projectMemberMapper, personMapper, modelCatalog, manifest,
            projectAgentRunService);
    }

    /** @return 能力目录服务 */
    @Bean
    public ProjectAgentCapabilityService projectAgentCapabilityService(
            IpdCopilotAccess access, CapabilityManifest manifest, ProjectAgentSkillCatalog skills,
            ProjectAgentToolCatalog tools, ProjectAgentModelCatalog models) {
        return new ProjectAgentCapabilityService(true, access, manifest, skills, tools, models);
    }

    /** @return 反馈服务 */
    @Bean
    public AiFeedbackService aiFeedbackService(
            IpdCopilotAccess access, AgentRunStore runStore, ArtifactVersionStore artifactStore,
            AiFeedbackStore feedbackStore) {
        return new AiFeedbackService(true, access, runStore, artifactStore, feedbackStore,
            System::currentTimeMillis);
    }
}
