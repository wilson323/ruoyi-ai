package org.ruoyi.ipd.agent.config;

import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentShutdownConfigurationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    @Test
    void defaultInfiniteWaitBecomesFiniteAndPolicyIsPreserved() {
        GracefulShutdownManager manager = mock(GracefulShutdownManager.class);
        when(manager.getConfig()).thenReturn(GracefulShutdownConfig.DEFAULT);
        ProjectAgentConfiguration.configureFiniteShutdown(manager);
        verify(manager).setConfig(new GracefulShutdownConfig(Duration.ofSeconds(15),
            GracefulShutdownConfig.DEFAULT.partialReasoningPolicy()));
    }

    @Test
    void existingFiniteConfigurationIsNotOverwritten() {
        GracefulShutdownManager manager = mock(GracefulShutdownManager.class);
        when(manager.getConfig()).thenReturn(new GracefulShutdownConfig(Duration.ofSeconds(3),
            GracefulShutdownConfig.DEFAULT.partialReasoningPolicy()));
        ProjectAgentConfiguration.configureFiniteShutdown(manager);
        verify(manager, never()).setConfig(any());
    }

    @Test
    void legacyDisabledPropertyStillAssemblesOfficialKernel() {
        GracefulShutdownManager manager = mock(GracefulShutdownManager.class);
        when(manager.getConfig()).thenReturn(GracefulShutdownConfig.DEFAULT);
        ProjectAgentConfiguration configuration = new ProjectAgentConfiguration() {
            @Override GracefulShutdownManager shutdownManager() { return manager; }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("ipd.project-agent.enabled", "false")));
            context.registerBean("projectAgentConfiguration", ProjectAgentConfiguration.class,
                () -> configuration);
            context.registerBean(org.ruoyi.ipd.service.AiDocEmbeddingService.class, () -> mock(org.ruoyi.ipd.service.AiDocEmbeddingService.class));
            context.registerBean(org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper.class, () -> mock(org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper.class));
            context.registerBean(org.ruoyi.service.knowledge.KnowledgeAccessGate.class, () -> mock(org.ruoyi.service.knowledge.KnowledgeAccessGate.class));
            context.registerBean(org.ruoyi.service.retrieval.KnowledgeRetrievalService.class, () -> mock(org.ruoyi.service.retrieval.KnowledgeRetrievalService.class));
            context.registerBean(org.ruoyi.ipd.service.ai.AiGateway.class, () -> mock(org.ruoyi.ipd.service.ai.AiGateway.class));
            context.registerBean(org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog.class,
                () -> mock(org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog.class));
            context.registerBean(org.ruoyi.ipd.mapper.IpdAgentMemoryMapper.class,
                () -> mock(org.ruoyi.ipd.mapper.IpdAgentMemoryMapper.class));
            context.registerBean(org.ruoyi.ipd.mapper.ProductLineNameMapper.class, () -> mock(org.ruoyi.ipd.mapper.ProductLineNameMapper.class));
            context.registerBean(org.ruoyi.ipd.service.IpdCopilotAccess.class, () -> mock(org.ruoyi.ipd.service.IpdCopilotAccess.class));
            context.registerBean(org.ruoyi.ipd.mapper.PersonMapper.class, () -> mock(org.ruoyi.ipd.mapper.PersonMapper.class));
            context.registerBean(org.ruoyi.ipd.agent.servicebridge.ProjectAgentProductionArtifacts.class,
                () -> new org.ruoyi.ipd.agent.servicebridge.ProjectAgentProductionArtifacts(
                    mock(org.ruoyi.ipd.agent.store.AgentRunStore.class),
                    mock(org.ruoyi.ipd.agent.store.ArtifactVersionStore.class),
                    context.getBean(org.ruoyi.ipd.mapper.PersonMapper.class),
                    context.getBean(org.ruoyi.ipd.service.IpdCopilotAccess.class),
                    mock(org.springframework.transaction.PlatformTransactionManager.class), workspace));
            context.registerBean(ProjectAgentOfficialCollaborationRedis.STORE_BEAN,
                io.agentscope.harness.agent.filesystem.remote.store.BaseStore.class,
                () -> mock(io.agentscope.harness.agent.filesystem.remote.store.BaseStore.class));
            context.registerBean("projectAgentStateStore", io.agentscope.core.state.AgentStateStore.class,
                () -> mock(io.agentscope.core.state.AgentStateStore.class));
            context.registerBean("agentScopeAuditHook", io.agentscope.core.hook.Hook.class,
                () -> mock(io.agentscope.core.hook.Hook.class));
            // 测试真实 Spring 内核装配，旧关闭属性不再删除官方能力。
            context.addBeanFactoryPostProcessor(factory -> {
                DefaultListableBeanFactory registry = (DefaultListableBeanFactory) factory;
                for (String name : registry.getBeanDefinitionNames()) {
                    var definition = registry.getBeanDefinition(name);
                    if ("projectAgentConfiguration".equals(definition.getFactoryBeanName())
                        && !"projectAgentKernel".equals(name)) {
                        registry.removeBeanDefinition(name);
                    }
                }
            });
            context.refresh();
            org.assertj.core.api.Assertions.assertThat(context.getBean("projectAgentKernel"))
                .isInstanceOf(org.ruoyi.ipd.agent.kernel.AgentScopeProjectAgentKernel.class);
            verify(manager).setConfig(new GracefulShutdownConfig(Duration.ofSeconds(15),
                GracefulShutdownConfig.DEFAULT.partialReasoningPolicy()));
        }
    }
}
