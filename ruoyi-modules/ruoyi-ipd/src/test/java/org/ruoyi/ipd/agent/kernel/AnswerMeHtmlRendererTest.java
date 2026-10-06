package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渲染引擎服务：CLI 契约（命令行/环境变量/预写 config/草稿落盘）在临时目录清理前核验；
 * 成功/失败/超时/超限各路径明确报错不静默降级；vendor 引擎与 ORIGIN.json 锁定一致。
 */
@Tag("dev")
class AnswerMeHtmlRendererTest {

    @TempDir Path cache;

    private static final AnswerMeHtmlRenderer.Settings SETTINGS =
        new AnswerMeHtmlRenderer.Settings(true, "node", Duration.ofSeconds(30), 65_536, 2_000);

    /** 记录每次进程调用；render 时核验契约、落 page.html，行为可逐测试覆写。 */
    static final class FakeProcess implements AnswerMeHtmlRenderer.ProcessExecutor {
        final List<List<String>> commands = new CopyOnWriteArrayList<>();
        final List<String> contractFindings = new CopyOnWriteArrayList<>();
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger versionProbes = new AtomicInteger();
        volatile Path lastWorkingDirectory;
        int versionExit = 0;
        String versionStdout = "v22.22.3\n";
        int renderExit = 0;
        boolean timedOut = false;
        boolean writeOutput = true;
        int steWarningsInStdout = 0;
        String renderStderr = "";

        @Override public AnswerMeHtmlRenderer.ProcessOutput run(List<String> command,
                Map<String, String> environment, Path workingDirectory, Duration timeout) {
            commands.add(List.copyOf(command));
            if (command.contains("--version")) {
                versionProbes.incrementAndGet();
                return new AnswerMeHtmlRenderer.ProcessOutput(versionExit, versionStdout, "", false);
            }
            renders.incrementAndGet();
            lastWorkingDirectory = workingDirectory;
            observeContract(command, environment, workingDirectory);
            Path html = Path.of(command.get(command.indexOf("-o") + 1));
            if (writeOutput && renderExit == 0 && !timedOut) {
                try {
                    Files.writeString(html, "<html>页面</html>", StandardCharsets.UTF_8);
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
            }
            String stdout;
            if (timedOut) {
                stdout = "";
            } else if (renderExit != 0) {
                stdout = "✗ L8 [sequence] 语法错误\n  Correct example:\n    A -> B: 请求\n  Full syntax: am help sequence";
            } else {
                stdout = "✓ " + html
                    + "\n  sheet · blueprint · 1 panel · flow×1 callout×1"
                    + "\n  STE ✓ " + steWarningsInStdout
                    + (steWarningsInStdout == 1 ? " warning" : " warnings");
            }
            return new AnswerMeHtmlRenderer.ProcessOutput(timedOut ? -1 : renderExit, stdout, renderStderr, timedOut);
        }

        /** 临时目录仍存活时核验 CLI 契约：render/-o/--no-open、AM_HOME/AM_NO_OPEN、预写 config、草稿。 */
        private void observeContract(List<String> command, Map<String, String> environment, Path work) {
            if (!command.contains("render") || !command.contains("--no-open")
                || command.indexOf("-o") < 0
                || command.stream().noneMatch(arg -> arg.endsWith("draft.md"))) {
                contractFindings.add("命令行缺 render/draft.md/-o/--no-open：" + command);
            }
            Path home = Path.of(environment.getOrDefault("AM_HOME", "missing"));
            if (!"1".equals(environment.get("AM_NO_OPEN")) || !Files.isDirectory(home)) {
                contractFindings.add("AM_HOME/AM_NO_OPEN 环境变量不符：" + environment);
            }
            try {
                String config = Files.readString(home.resolve("config.json"), StandardCharsets.UTF_8);
                if (!config.contains("\"open\": false") || !config.contains("\"update_check\": false")) {
                    contractFindings.add("config.json 未预写关浏览器/关联网检查：" + config);
                }
                if (!Files.readString(work.resolve("draft.md"), StandardCharsets.UTF_8).contains("草稿正文")) {
                    contractFindings.add("draft.md 未按原文落盘");
                }
            } catch (IOException io) {
                contractFindings.add("契约核验读取失败：" + io.getMessage());
            }
        }
    }

    private static AnswerMeHtmlRenderer.Failure failure(Object result) {
        return (AnswerMeHtmlRenderer.Failure) result;
    }

    @Test
    @DisplayName("渲染成功：契约完整，返回 HTML 字节、摘要与 STE 计数，临时目录清理")
    void renderSuccessReturnsHtmlSummaryAndSteCount() {
        FakeProcess process = new FakeProcess();
        var renderer = new AnswerMeHtmlRenderer(SETTINGS, process, cache);

        var result = renderer.render("# 标题\n\n## 面板\n\n草稿正文");

        assertThat(result).isInstanceOf(AnswerMeHtmlRenderer.Success.class);
        var success = (AnswerMeHtmlRenderer.Success) result;
        assertThat(new String(success.html(), StandardCharsets.UTF_8)).contains("<html>");
        assertThat(success.summary()).isEqualTo("sheet · blueprint · 1 panel · flow×1 callout×1");
        assertThat(success.steWarnings()).isZero();
        assertThat(process.contractFindings).isEmpty();
        // 引擎经 ORIGIN.json 校验后提取到缓存目录
        assertThat(process.commands.get(0).get(1)).startsWith(cache.toString());
        assertThat(process.commands.get(0).get(1)).contains(".mjs");
        // 渲染后临时工作目录已清理
        assertThat(process.lastWorkingDirectory).isNotNull();
        assertThat(Files.notExists(process.lastWorkingDirectory)).isTrue();
    }

    @Test
    @DisplayName("STE 警告单复数都能解析为计数")
    void steWarningCountParsesSingularAndPlural() {
        FakeProcess plural = new FakeProcess();
        plural.steWarningsInStdout = 5;
        var result = (AnswerMeHtmlRenderer.Success) new AnswerMeHtmlRenderer(SETTINGS, plural, cache)
            .render("草稿正文");
        assertThat(result.steWarnings()).isEqualTo(5);

        FakeProcess singular = new FakeProcess();
        singular.steWarningsInStdout = 1;
        var single = (AnswerMeHtmlRenderer.Success) new AnswerMeHtmlRenderer(SETTINGS, singular, cache)
            .render("草稿正文");
        assertThat(single.steWarnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLI 失败：✗ 行号诊断原样透传，供模型按行号自修")
    void renderFailurePassesThroughDiagnostics() {
        FakeProcess process = new FakeProcess();
        process.renderExit = 1;
        process.renderStderr = "node:extra";
        var result = new AnswerMeHtmlRenderer(SETTINGS, process, cache)
            .render("## 面板\n\n草稿正文");

        assertThat(result).isInstanceOf(AnswerMeHtmlRenderer.Failure.class);
        var failure = failure(result);
        assertThat(failure.error()).contains("未通过渲染校验");
        assertThat(failure.diagnostics()).contains("✗ L8", "Correct example");
        assertThat(AnswerMeHtmlRenderer.diagnostics(new AnswerMeHtmlRenderer.ProcessOutput(1,
            "✗ L8 [sequence] 语法错误\n  Correct example:\n    A -> B: 请求\n  Full syntax: am help sequence",
            "node:extra", false)))
            .contains("L8 [sequence]", "Correct example", "Full syntax", "node:extra");
        assertThat(AnswerMeHtmlRenderer.diagnostics(
            new AnswerMeHtmlRenderer.ProcessOutput(1, "", "", false))).isNull();
    }

    @Test
    @DisplayName("超时与进程启动失败都明确报错，不静默降级")
    void timeoutAndStartupFailureFailLoud() {
        FakeProcess timedOut = new FakeProcess();
        timedOut.timedOut = true;
        var timeoutResult = new AnswerMeHtmlRenderer(SETTINGS, timedOut, cache).render("草稿正文");
        assertThat(failure(timeoutResult).error()).contains("超时").contains("30");

        FakeProcess dead = new FakeProcess();
        dead.versionExit = -1;
        dead.renderExit = -1;
        dead.writeOutput = false;
        var deadResult = new AnswerMeHtmlRenderer(SETTINGS, dead, cache).render("草稿正文");
        assertThat(failure(deadResult).error()).contains("启动失败");
    }

    @Test
    @DisplayName("退出 0 但无产物文件：明确报未产出，不返回空 HTML")
    void missingOutputFileFailsExplicitly() {
        FakeProcess process = new FakeProcess();
        process.writeOutput = false;
        var result = new AnswerMeHtmlRenderer(SETTINGS, process, cache).render("草稿正文");
        assertThat(failure(result).error()).contains("未产出 HTML 文件");
    }

    @Test
    @DisplayName("产物超限被拒绝：超过 maxOutputBytes 不读回不返回")
    void oversizedOutputIsRejected() {
        FakeProcess process = new FakeProcess();
        var limited = new AnswerMeHtmlRenderer(
            new AnswerMeHtmlRenderer.Settings(true, "node", Duration.ofSeconds(30), 4, 2_000), process, cache);
        var result = limited.render("草稿正文");
        assertThat(failure(result).error()).contains("超过上限");
    }

    @Test
    @DisplayName("草稿为空或超长在进程序前被拒绝，零进程调用")
    void blankAndOversizedDraftsAreRejectedBeforeProcess() {
        FakeProcess process = new FakeProcess();
        var renderer = new AnswerMeHtmlRenderer(SETTINGS, process, cache);
        assertThat(failure(renderer.render("   ")).error()).contains("不能为空");
        var capped = new AnswerMeHtmlRenderer(
            new AnswerMeHtmlRenderer.Settings(true, "node", Duration.ofSeconds(30), 65_536, 5), process, cache);
        assertThat(failure(capped.render("草稿正文超长")).error()).contains("超过上限");
        assertThat(process.commands).isEmpty();
    }

    @Test
    @DisplayName("未启用的渲染器：readiness 与 render 都明确不可用且零进程调用")
    void disabledRendererFailsClosed() {
        FakeProcess process = new FakeProcess();
        var disabled = new AnswerMeHtmlRenderer(
            new AnswerMeHtmlRenderer.Settings(false, "node", Duration.ofSeconds(30), 65_536, 2_000), process, cache);
        assertThat(disabled.readiness().available()).isFalse();
        assertThat(disabled.readiness().reason()).isEqualTo("HTML 渲染未启用");
        assertThat(failure(disabled.render("草稿正文")).error()).isEqualTo("HTML 渲染未启用");
        assertThat(process.commands).isEmpty();
    }

    @Test
    @DisplayName("readiness 探测 node 版本且 10 秒内缓存复用；版本过低明确拒绝")
    void readinessCachesNodeProbeAndRejectsOldNode() {
        FakeProcess process = new FakeProcess();
        var renderer = new AnswerMeHtmlRenderer(SETTINGS, process, cache);
        assertThat(renderer.readiness().available()).isTrue();
        assertThat(renderer.readiness().available()).isTrue();
        assertThat(process.versionProbes).hasValue(1);

        FakeProcess old = new FakeProcess();
        old.versionStdout = "v18.20.4\n";
        var legacy = new AnswerMeHtmlRenderer(SETTINGS, old, cache);
        assertThat(legacy.readiness().available()).isFalse();
        assertThat(legacy.readiness().reason()).contains("20");

        FakeProcess broken = new FakeProcess();
        broken.versionExit = 127;
        var missing = new AnswerMeHtmlRenderer(SETTINGS, broken, cache);
        assertThat(missing.readiness().reason()).contains("Node 运行环境不可用");
    }

    @Test
    @DisplayName("vendor 引擎与 ORIGIN.json 逐字节锁一致；期望摘要提取 fail-closed")
    void vendoredEngineMatchesOriginLock() throws Exception {
        byte[] engine;
        String origin;
        try (var in = AnswerMeHtmlRenderer.class.getClassLoader()
            .getResourceAsStream(AnswerMeHtmlRenderer.ENGINE_RESOURCE)) {
            assertThat(in).as("classpath 应含 vendored am.mjs").isNotNull();
            engine = in.readAllBytes();
        }
        try (var in = AnswerMeHtmlRenderer.class.getClassLoader()
            .getResourceAsStream(AnswerMeHtmlRenderer.ORIGIN_RESOURCE)) {
            assertThat(in).as("classpath 应含 ORIGIN.json").isNotNull();
            origin = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(AnswerMeHtmlRenderer.sha256(engine))
            .isEqualTo(AnswerMeHtmlRenderer.expectedSha256(origin));
        assertThatThrownBy(() -> AnswerMeHtmlRenderer.expectedSha256("{\"files\":{}}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("am.mjs sha256");
    }

    @Test
    @DisplayName("配置下限校验：超时至少 10 秒，输出与草稿上限为正")
    void settingsEnforceMinimums() {
        assertThatThrownBy(() -> new AnswerMeHtmlRenderer.Settings(true, "node",
            Duration.ofSeconds(5), 65_536, 2_000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("至少 10 秒");
        assertThatThrownBy(() -> new AnswerMeHtmlRenderer.Settings(true, "node",
            Duration.ofSeconds(30), 0, 2_000))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
