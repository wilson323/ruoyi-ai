package org.ruoyi.ipd.agent.model;

import org.ruoyi.chat.kernel.KernelModelRequest;
import java.util.Objects;

/** Server-selected identity; only the exact in-memory instance is accepted for accounting. */
public record ProjectAgentModelIdentity(Long modelConfigId, String providerCode, String modelName) {
    public ProjectAgentModelIdentity {
        Objects.requireNonNull(modelConfigId, "model config id");
        if (modelConfigId <= 0 || providerCode == null || providerCode.isBlank()
            || modelName == null || modelName.isBlank()) throw new IllegalArgumentException("Model identity is incomplete");
    }
    public static ProjectAgentModelIdentity of(Long id, KernelModelRequest request) {
        return new ProjectAgentModelIdentity(id, request.providerCode(), request.modelName());
    }
}
