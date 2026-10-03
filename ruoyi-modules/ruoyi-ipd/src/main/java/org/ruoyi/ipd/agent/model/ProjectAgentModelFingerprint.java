package org.ruoyi.ipd.agent.model;

import org.ruoyi.chat.kernel.KernelModelRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable model comparison evidence; no credential or endpoint plaintext is persisted. */
public final class ProjectAgentModelFingerprint {
    private static final String FORMAT = "ipd-model-sha256-v1";
    private ProjectAgentModelFingerprint() { }

    public record Snapshot(String format, String salt, String digest) { }

    public static Snapshot capture(KernelModelRequest primary, KernelModelRequest fallback) {
        Objects.requireNonNull(primary, "primary model is required");
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        String encoded = HexFormat.of().formatHex(salt);
        return new Snapshot(FORMAT, encoded, digest(encoded, primary, fallback));
    }

    public static Snapshot capture(Long primaryId, KernelModelRequest primary, Long fallbackId, KernelModelRequest fallback) {
        Objects.requireNonNull(primaryId, "primary config id");
        if ((fallbackId == null) != (fallback == null)) throw new IllegalArgumentException("Fallback identity is incomplete");
        Snapshot parameters = capture(primary, fallback);
        return new Snapshot("ipd-model-sha256-v2", parameters.salt(), identityDigest(parameters.salt(), primaryId, primary, fallbackId, fallback));
    }

    public static void requireUnchanged(Snapshot frozen, Long primaryId, KernelModelRequest primary,
                                        Long fallbackId, KernelModelRequest fallback) {
        if (frozen == null || !"ipd-model-sha256-v2".equals(frozen.format())) {
            throw new IllegalStateException("原运行未保存完整模型身份指纹，无法确认原配置；请创建关联的新尝试");
        }
        if (primaryId == null || primary == null || (fallbackId == null) != (fallback == null)
            || frozen.salt() == null || !frozen.salt().matches("[0-9a-f]{64}")
            || frozen.digest() == null || !frozen.digest().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("原运行模型身份指纹无法核验");
        }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(frozen.digest()),
            HexFormat.of().parseHex(identityDigest(frozen.salt(), primaryId, primary, fallbackId, fallback)))) {
            throw new IllegalStateException("原运行的模型身份或配置已改变；请恢复原配置或创建关联的新尝试");
        }
    }

    private static String identityDigest(String salt, Long primaryId, KernelModelRequest primary,
                                          Long fallbackId, KernelModelRequest fallback) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, "ipd-model-sha256-v2"); add(hash, salt);
            add(hash, primaryId.toString()); model(hash, primary);
            add(hash, fallbackId == null ? null : fallbackId.toString()); model(hash, fallback);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Missing historical evidence must not be reconstructed from today's configuration. */
    public static void requireUnchanged(Snapshot frozen, KernelModelRequest primary,
                                        KernelModelRequest fallback) {
        if (frozen == null) {
            throw new IllegalStateException("原运行未保存模型配置指纹，无法确认原配置；请创建关联的新尝试");
        }
        if (!FORMAT.equals(frozen.format()) || frozen.salt() == null
            || !frozen.salt().matches("[0-9a-f]{64}") || frozen.digest() == null
            || !frozen.digest().matches("[0-9a-f]{64}") || primary == null) {
            throw new IllegalStateException("原运行模型配置指纹无法核验");
        }
        byte[] expected = HexFormat.of().parseHex(frozen.digest());
        byte[] actual = HexFormat.of().parseHex(digest(frozen.salt(), primary, fallback));
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalStateException("原运行的模型配置已改变；请恢复原配置或创建关联的新尝试");
        }
    }

    private static String digest(String salt, KernelModelRequest primary, KernelModelRequest fallback) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, FORMAT);
            add(hash, salt);
            model(hash, primary);
            model(hash, fallback);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void model(MessageDigest hash, KernelModelRequest model) {
        hash.update((byte) (model == null ? 0 : 1));
        if (model == null) return;
        add(hash, model.modelName());
        add(hash, model.providerCode());
        add(hash, model.apiHost());
        add(hash, model.apiKey());
        add(hash, model.temperature() == null ? null : model.temperature().toString());
        add(hash, model.maxTokens() == null ? null : model.maxTokens().toString());
        add(hash, model.timeoutMs() == null ? null : model.timeoutMs().toString());
    }

    private static void add(MessageDigest hash, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        hash.update((byte) (value == null ? 0 : 1));
        hash.update((byte) (bytes.length >>> 24));
        hash.update((byte) (bytes.length >>> 16));
        hash.update((byte) (bytes.length >>> 8));
        hash.update((byte) bytes.length);
        hash.update(bytes);
    }
}
