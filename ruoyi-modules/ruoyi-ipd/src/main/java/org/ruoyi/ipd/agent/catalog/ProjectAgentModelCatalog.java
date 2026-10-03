package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.AiModelConfigService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 项目智能体模型目录：以 IPD {@code ai_model_configs} 为唯一模型权威（C2），
 * 按 run 选定的 modelConfigId 取配置（区别于 {@code AiModelConfigKernelBridge#currentRequest()}
 * 只取全局生效行）。
 *
 * <p>可用判定：配置未删除 + {@code is_active=true} + provider/modelName 齐全。非生效配置
 * 列出但不可选（reason 明示），防止已停用配置被选用；选中配置不可用时拒绝创建运行，
 * 不静默换模型。映射字段与 {@code AiModelConfigKernelBridge.from} 相同
 * （provider→providerCode、endpointUrl→apiHost），该方法包级私有且原文件不在本切片写入面。
 * 凭据只在内存解密，{@link KernelModelRequest#toString()} 已脱敏。
 */
public class ProjectAgentModelCatalog {

    /** 模型可用性投影（id 为字符串，名称不含凭据）。 */
    public record ModelStatus(String id, String name, boolean available, String reason) { }

    private final AiModelConfigMapper mapper;
    private final AiModelConfigService modelConfigService;

    /**
     * @param mapper ai_model_configs Mapper（已豁免租户拦截）
     * @param modelConfigService 仅用于内存解密 api_key
     */
    public ProjectAgentModelCatalog(AiModelConfigMapper mapper, AiModelConfigService modelConfigService) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.modelConfigService = Objects.requireNonNull(modelConfigService, "modelConfigService");
    }

    /**
     * 全部模型配置的可用性（按更新时间倒序）。
     *
     * @return 模型状态列表
     */
    public List<ModelStatus> statuses() {
        List<AiModelConfig> configs = mapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
            .orderByDesc(AiModelConfig::getUpdateTime)
            .orderByDesc(AiModelConfig::getId));
        return configs == null ? List.of() : configs.stream().map(ProjectAgentModelCatalog::status).toList();
    }

    /**
     * 单个模型配置的可用性。
     *
     * @param modelConfigId 配置 ID
     * @return 状态（不存在返回不可用）
     */
    public ModelStatus status(Long modelConfigId) {
        AiModelConfig config = modelConfigId == null ? null : mapper.selectById(modelConfigId);
        if (config == null) {
            return new ModelStatus(modelConfigId == null ? null : String.valueOf(modelConfigId),
                null, false, "模型配置不存在");
        }
        return status(config);
    }

    /**
     * 读取指定配置上的 modelName。已停用的配置仍返回原名，不改选其他模型。
     *
     * @param modelConfigId 运行行上的配置 ID
     * @return 模型名；配置不存在或名为空时 null
     */
    public String modelNameOf(Long modelConfigId) {
        if (modelConfigId == null) {
            return null;
        }
        AiModelConfig config = mapper.selectById(modelConfigId);
        if (config == null || config.getModelName() == null || config.getModelName().isBlank()) {
            return null;
        }
        return config.getModelName().trim();
    }

    /**
     * 解析选定模型为内核装配请求（仅可用配置；凭据内存解密）。
     *
     * @param modelConfigId 配置 ID
     * @return 装配请求；不可用为空
     */
    public Optional<KernelModelRequest> resolve(Long modelConfigId) {
        AiModelConfig config = modelConfigId == null ? null : mapper.selectById(modelConfigId);
        if (config == null || unavailableReason(config) != null) {
            return Optional.empty();
        }
        return Optional.of(toRequest(config));
    }

    /**
     * 按目录约定解析主模型的官方回退配置（AgentScope 2.0.3 fallbackModel 扩展点取数）。
     *
     * <p>约定：同 provider 下 config_json 的 {@code fallbackFor} 等于主模型 modelName 的未删行
     * （{@code @TableLogic} 已滤软删）。回退行不参与主选型，{@code is_active} 保持 0
     * （is_active 语义是全局唯一生效主模型，见 {@code AiModelConfigService#enable} 互斥清位），
     * 本方法不按 is_active 判定。无约定行/字段不全 → {@link Optional#empty()}：
     * 回退缺席是配置态而非错误，装配侧 fail-open，不阻断主模型运行。
     *
     * @param primary 主模型装配请求
     * @return 回退装配请求；无约定回退时为空
     */
    public Optional<KernelModelRequest> resolveFallback(KernelModelRequest primary) {
        if (primary == null || isBlank(primary.providerCode()) || isBlank(primary.modelName())) {
            return Optional.empty();
        }
        List<AiModelConfig> configs = mapper.selectList(new LambdaQueryWrapper<AiModelConfig>()
            .eq(AiModelConfig::getProvider, primary.providerCode().trim())
            .orderByDesc(AiModelConfig::getUpdateTime)
            .orderByDesc(AiModelConfig::getId));
        if (configs == null) {
            return Optional.empty();
        }
        String primaryName = primary.modelName().trim();
        for (AiModelConfig config : configs) {
            if (config == null || isBlank(config.getModelName())
                || config.getModelName().trim().equals(primaryName)) {
                continue;
            }
            if (!primaryName.equals(fallbackOwner(config.getConfigJson()))) {
                continue;
            }
            if (isBlank(config.getProvider()) || isBlank(config.getModelName())
                || isBlank(config.getEndpointUrl())) {
                continue;
            }
            return Optional.of(toRequest(config));
        }
        return Optional.empty();
    }

    /** config_json.fallbackFor 取值；无键/无效 JSON 返回 null（回退约定缺席）。 */
    private static String fallbackOwner(String configJson) {
        try {
            JsonNode node = configJson == null || configJson.isBlank()
                ? null : new ObjectMapper().readTree(configJson);
            return node != null && node.hasNonNull("fallbackFor")
                ? node.get("fallbackFor").asText().trim() : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    /** AiModelConfig → 内核装配请求（resolve/resolveFallback 同一映射口径；凭据内存解密）。 */
    private KernelModelRequest toRequest(AiModelConfig config) {
        JsonNode parameters;
        try {
            parameters = config.getConfigJson() == null || config.getConfigJson().isBlank()
                ? new ObjectMapper().createObjectNode() : new ObjectMapper().readTree(config.getConfigJson());
        } catch (Exception ex) {
            // 与既有生成入口一致：无效 JSON 不注入任何参数。
            parameters = new ObjectMapper().createObjectNode();
        }
        if (parameters == null) {
            parameters = new ObjectMapper().createObjectNode();
        }
        Double temperature = parameters.hasNonNull("temperature")
            ? parameters.get("temperature").doubleValue() : null;
        Integer maxTokens = parameters.path("maxTokens").asInt(0) > 0
            ? parameters.path("maxTokens").asInt() : null;
        // 与生成入口 generateTimeoutMs 的 10–120 秒钳制一致；未配置不改变内核默认。
        Integer timeout = parameters.hasNonNull("generateTimeoutMs")
            ? parameters.path("generateTimeoutMs").asInt(0) : null;
        if (timeout != null) {
            timeout = timeout <= 0 ? 60_000 : Math.min(Math.max(timeout, 10_000), 120_000);
        }
        return new KernelModelRequest(config.getModelName(), config.getProvider(),
            modelConfigService.decryptApiKey(config), config.getEndpointUrl(), temperature, maxTokens, timeout);
    }

    private static ModelStatus status(AiModelConfig config) {
        String reason = unavailableReason(config);
        String name = orEmpty(config.getProvider()) + " / " + orEmpty(config.getModelName());
        return new ModelStatus(String.valueOf(config.getId()), name, reason == null, reason);
    }

    private static String unavailableReason(AiModelConfig config) {
        if (!Boolean.TRUE.equals(config.getIsActive())) {
            return "模型配置未启用";
        }
        if (isBlank(config.getProvider()) || isBlank(config.getModelName())) {
            return "模型配置不完整（缺 provider 或 modelName）";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
