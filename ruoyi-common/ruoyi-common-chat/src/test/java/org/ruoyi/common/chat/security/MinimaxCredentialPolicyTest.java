package org.ruoyi.common.chat.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class MinimaxCredentialPolicyTest {
    @Test
    void supportedEndpointsResolveOnlyTheDedicatedReference() {
        for (String host : List.of("https://api.minimax.io/v1", "https://api.minimax.io/anthropic",
            "https://api.minimax.cn/v1", "https://api.minimax.cn/anthropic",
            "https://api.minimaxi.com/v1", "https://api.minimaxi.com/anthropic")) {
            for (String model : List.of("MiniMax-M3", "MiniMax-M2.7")) {
                ChatModelCredentialPolicy.requirePersistableConfiguration("minimax", model, host,
                    "env:MINIMAX_API_KEY");
                assertEquals("fixture-key", ChatModelCredentialPolicy.resolveApiKeyForUse(
                    "minimax", "minimax", model, host + "/", "env:MINIMAX_API_KEY", variable -> {
                        assertEquals("MINIMAX_API_KEY", variable);
                        return "fixture-key";
                    }));
            }
        }
    }

    @Test
    void invalidBindingsFailBeforeEnvironmentAccess() {
        for (String[] row : List.of(
            new String[]{"minimax", "minimax", "MiniMax-M3", "https://attacker.example/v1", "env:MINIMAX_API_KEY"},
            new String[]{"minimax", "minimax", "MiniMax-M3", "https://api.minimax.cn/v1", "raw-fixture-key"},
            new String[]{"minimax", "minimax", "MiniMax-M3", "https://api.minimax.cn/v1", "env:DEEPSEEK_API_KEY"},
            new String[]{"deepseek", "minimax", "MiniMax-M3", "https://api.minimax.cn/v1", "env:MINIMAX_API_KEY"},
            new String[]{"minimax", "minimax", "", "https://api.minimax.cn/v1", "env:MINIMAX_API_KEY"},
            new String[]{"minimax", "minimax", "MiniMax-M3", "https://api.minimax.cn/v1?redirect=evil", "env:MINIMAX_API_KEY"})) {
            AtomicBoolean environmentRead = new AtomicBoolean();
            assertThrows(IllegalArgumentException.class, () -> ChatModelCredentialPolicy.resolveApiKeyForUse(
                row[0], row[1], row[2], row[3], row[4], variable -> {
                    environmentRead.set(true);
                    return "fixture-key";
                }));
            assertFalse(environmentRead.get());
        }
    }

    @Test
    void persistenceAndMissingCredentialFailClosed() {
        for (String reference : List.of("raw-fixture-key", "env:ATLAS_API_KEY")) {
            assertThrows(IllegalArgumentException.class, () -> ChatModelCredentialPolicy.requirePersistableConfiguration(
                "minimax", "MiniMax-M3", "https://api.minimax.cn/v1", reference));
        }
        assertThrows(IllegalStateException.class, () -> ChatModelCredentialPolicy.resolveApiKeyForUse(
            "minimax", "minimax", "MiniMax-M3", "https://api.minimax.cn/v1", "env:MINIMAX_API_KEY", variable -> null));
    }
}
