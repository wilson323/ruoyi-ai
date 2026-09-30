package org.ruoyi.ipd.agent.catalog;

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
        return Optional.of(new KernelModelRequest(config.getModelName(), config.getProvider(),
            modelConfigService.decryptApiKey(config), config.getEndpointUrl()));
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
