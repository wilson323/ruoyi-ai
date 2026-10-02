package org.ruoyi.ipd.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置默认 embedding 模型：Java 常量与安装种子 SQL 防漂移（hermetic，不连库）。
 */
@Tag("dev")
@DisplayName("内置默认 embedding 模型：常量与种子一致")
class BuiltinEmbeddingModelTest {

    private static final Path SEED = findGitRoot(Paths.get(System.getProperty("user.dir")))
        .resolve("docs/script/sql/update/2026-09-30-ipd-builtin-embedding-model-seed.sql");

    /** SELECT 行：id, 'vector', 'model', 'openai', 'describe', dim, 'show', 'api_host', api_key */
    private static final Pattern SEED_VALUES = Pattern.compile(
        "SELECT (\\d+), 'vector', '([^']+)', 'openai', '[^']*', (\\d+),\\s*'[YN]', '([^']+)', (NULL|'[^']*'),");

    @Test
    @DisplayName("base URL 拼 /embeddings 即用户原文端点；不含 /embeddings 尾缀（防双拼 404）")
    void baseUrlComposesUserEndpoint() {
        assertThat(BuiltinEmbeddingModel.BASE_URL + "/embeddings")
            .isEqualTo("http://127.0.0.1:11434/v1/embeddings");
        assertThat(BuiltinEmbeddingModel.BASE_URL).doesNotEndWith("/embeddings").doesNotEndWith("/");
        assertThat(BuiltinEmbeddingModel.API_KEY).isEmpty();
        assertThat(BuiltinEmbeddingModel.SOURCE).isEqualTo("builtin-default");
    }

    @Test
    @DisplayName("种子 SQL：模型名/维度/api_host 与常量一致，api_key=NULL，幂等且无 UPDATE/DELETE")
    void seedMatchesConstants() throws IOException {
        String sql = Files.readString(SEED, StandardCharsets.UTF_8);
        Matcher m = SEED_VALUES.matcher(sql);
        assertThat(m.find()).as("种子 SELECT 行格式").isTrue();
        assertThat(m.group(2)).isEqualTo(BuiltinEmbeddingModel.MODEL_NAME);
        assertThat(Integer.parseInt(m.group(3))).isEqualTo(BuiltinEmbeddingModel.DIMENSION);
        assertThat(m.group(4)).isEqualTo(BuiltinEmbeddingModel.BASE_URL);
        assertThat(m.group(5)).as("无鉴权：NULL，不写占位密钥").isEqualTo("NULL");

        String executable = sql.lines().filter(l -> !l.trim().startsWith("--"))
            .reduce("", (a, b) -> a + "\n" + b).toUpperCase();
        assertThat(executable).contains("WHERE NOT EXISTS");
        assertThat(executable).doesNotContain("UPDATE ").doesNotContain("DELETE ").doesNotContain("AI_MODEL_CONFIGS");
    }

    private static Path findGitRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return start.toAbsolutePath().normalize();
    }
}
