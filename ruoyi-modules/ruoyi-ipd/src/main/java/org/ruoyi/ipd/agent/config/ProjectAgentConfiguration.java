package org.ruoyi.ipd.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.kernel.AgentScopeProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentModelAssembler;
import org.ruoyi.ipd.agent.service.AiFeedbackService;
import org.ruoyi.ipd.agent.service.ProjectAgentCapabilityService;
import org.ruoyi.ipd.agent.service.ProjectAgentRunExecutor;
import org.ruoyi.ipd.agent.service.ProjectAgentRunPlanner;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.AiFeedbackStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.AiModelConfigService;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 项目智能体装配。
 *
 * <p>目录/服务/控制器 Bean 恒在（开关关闭时能力接口仍可如实回报“未启用”）；
 * 执行内核 {@link AgentScopeProjectAgentKernel} 仅在 {@code ipd.project-agent.enabled=true}
 * 时装配（matchIfMissing=false）。回滚点：开关置 false 即无内核、运行接口拒绝，不影响副驾链路。
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

    /**
     * 执行内核（仅开关开启时装配）。
     *
     * @param embeddingService 项目资料检索
     * @param workspaceRoot 工作区根
     * @param maxIters 最大迭代数
     * @return 内核
     */
    @Bean
    @ConditionalOnProperty(name = ProjectAgentConstants.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = false)
    public ProjectAgentKernel projectAgentKernel(
            AiDocEmbeddingService embeddingService,
            @Value("${ipd.project-agent.workspace-root:${java.io.tmpdir}/ipd-project-agent-workspace}") Path workspaceRoot,
            @Value("${ipd.project-agent.max-iters:8}") int maxIters) {
        return new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler(),
            embeddingService::retrieveContext, workspaceRoot, maxIters);
    }

    /** @return 执行器（内核缺席时为 null 注入，启动即收口 FAILED） */
    @Bean
    public ProjectAgentRunExecutor projectAgentRunExecutor(
            AgentRunStore store, ArtifactVersionStore artifactStore,
            ObjectProvider<ProjectAgentKernel> kernel, ObjectMapper mapper,
            AiModelUsageLedgerService usageLedger,
            @Value("${ipd.project-agent.max-concurrent-runs:4}") int maxConcurrentRuns) {
        ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(store, artifactStore,
            kernel.getIfAvailable(), mapper, Schedulers.boundedElastic(), System::currentTimeMillis,
            maxConcurrentRuns);
        executor.setUsageLedger(usageLedger);
        return executor;
    }

    /** @return 运行服务 */
    @Bean
    public ProjectAgentRunService projectAgentRunService(
            @Value("${" + ProjectAgentConstants.ENABLED_PROPERTY + ":false}") boolean enabled,
            IpdCopilotAccess access, ProjectAgentRunPlanner planner, AgentRunStore store,
            ArtifactVersionStore artifactStore, AiDocumentService documentService,
            ProjectMapper projectMapper, ProductMapper productMapper,
            ProjectAgentRunExecutor executor, ObjectMapper mapper,
            @Value("${ipd.project-agent.run-timeout-seconds:300}") long timeoutSeconds) {
        return new ProjectAgentRunService(enabled, access, planner, store, artifactStore, documentService,
            projectMapper, productMapper, executor, mapper, System::currentTimeMillis,
            Duration.ofSeconds(Math.max(30, timeoutSeconds)));
    }

    /** @return 能力目录服务 */
    @Bean
    public ProjectAgentCapabilityService projectAgentCapabilityService(
            @Value("${" + ProjectAgentConstants.ENABLED_PROPERTY + ":false}") boolean enabled,
            IpdCopilotAccess access, CapabilityManifest manifest, ProjectAgentSkillCatalog skills,
            ProjectAgentToolCatalog tools, ProjectAgentModelCatalog models) {
        return new ProjectAgentCapabilityService(enabled, access, manifest, skills, tools, models);
    }

    /** @return 反馈服务 */
    @Bean
    public AiFeedbackService aiFeedbackService(
            @Value("${" + ProjectAgentConstants.ENABLED_PROPERTY + ":false}") boolean enabled,
            IpdCopilotAccess access, AgentRunStore runStore, ArtifactVersionStore artifactStore,
            AiFeedbackStore feedbackStore) {
        return new AiFeedbackService(enabled, access, runStore, artifactStore, feedbackStore,
            System::currentTimeMillis);
    }
}
