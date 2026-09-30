package org.ruoyi.ipd.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.service.ProjectAgentRunPlanner;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.AiModelConfigService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目智能体测试共用夹具。Skill 目录与清单读取真实 classpath 资源（JAR 同源），
 * 模型配置用 Mapper 替身（与 ai_model_configs 实体同形，非运行态证据）。
 */
public final class AgentTestFixtures {

    public static final Long PROJECT_ID = 20260929L;
    public static final Long OTHER_PROJECT_ID = 20260930L;
    public static final String TENANT = "tenant-a";
    public static final Long MODEL_ID = 7001L;
    public static final Long INACTIVE_MODEL_ID = 7002L;
    public static final IpdActor ACTOR = new IpdActor(11L, "市场PM", "MARKET_PM", 100L);
    public static final IpdActor OTHER_ACTOR = new IpdActor(12L, "研发PM", "RD_PM", 100L);

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTestFixtures() {
    }

    /** @return JAR 同源内置清单 */
    public static CapabilityManifest manifest() {
        return CapabilityManifest.load(CapabilityManifest.DEFAULT_RESOURCE, MAPPER);
    }

    /** @return 读真实 classpath 的 Skill 目录 */
    public static ProjectAgentSkillCatalog skillCatalog(CapabilityManifest manifest) {
        return new ProjectAgentSkillCatalog("ipd-skills", manifest);
    }

    /**
     * 模型目录：MODEL_ID 为生效配置，INACTIVE_MODEL_ID 为未启用配置。
     *
     * @return 模型目录
     */
    public static ProjectAgentModelCatalog modelCatalog() {
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        AiModelConfigService service = mock(AiModelConfigService.class);
        AiModelConfig active = model(MODEL_ID, true);
        AiModelConfig inactive = model(INACTIVE_MODEL_ID, false);
        when(mapper.selectById(MODEL_ID)).thenReturn(active);
        when(mapper.selectById(INACTIVE_MODEL_ID)).thenReturn(inactive);
        when(mapper.selectList(any())).thenReturn(List.of(active, inactive));
        when(service.decryptApiKey(any())).thenReturn("sk-test-only");
        return new ProjectAgentModelCatalog(mapper, service);
    }

    /** @return 真实目录组装的规划器 */
    public static ProjectAgentRunPlanner planner() {
        CapabilityManifest manifest = manifest();
        return new ProjectAgentRunPlanner(manifest, skillCatalog(manifest), new ProjectAgentToolCatalog(manifest),
            modelCatalog());
    }

    /**
     * 合法 C02 请求。
     *
     * @param key 幂等键
     * @param message 输入
     * @return 请求
     */
    public static AgentRunCreateReq c02(String key, String message) {
        return new AgentRunCreateReq("market-research", "v1", String.valueOf(MODEL_ID),
            List.of("competitor-analysis-ipd"), List.of("project_knowledge_search"), "C02", message, key);
    }

    private static AiModelConfig model(Long id, boolean active) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setProvider("MiniMax");
        config.setModelName("MiniMax-M3");
        config.setEndpointUrl("https://example.invalid/v1");
        config.setIsActive(active);
        return config;
    }
}
