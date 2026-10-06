package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单文件 HTML 页面渲染引擎（vendor 自 QingYunA/answer-me-with-html，MIT，ORIGIN.json 锁定）。
 *
 * <p>引擎 {@code ipd-am/am.mjs} 随 JAR 交付；加载时提取到工作区缓存并按 ORIGIN.json 的
 * SHA-256 校验，不一致 fail-closed（对齐 SKILL.md 原始字节锁定哲学）。每次渲染使用独立
 * 临时 AM_HOME（预写 {@code config.json}：{@code open=false}、{@code update_check=off}，
 * 禁止弹浏览器与联网版本检查），渲染后清理。
 *
 * <p>CLI 契约（f3082c9 实测）：成功 exit 0，首行 {@code ✓ <输出路径>}；失败 exit 1，
 * {@code ✗ L<行号> [组件] <原因>} 附正确示例，供模型按行号自修；STE 警告随成功输出，
 * 不阻断。node 缺失/超时/超限返回明确中文错误，不静默降级。
 */
public final class AnswerMeHtmlRenderer {

    /** 引擎 classpath 资源（与 ORIGIN.json 同目录）。 */
    public static final String ENGINE_RESOURCE = "ipd-am/am.mjs";
    public static final String ORIGIN_RESOURCE = "ipd-am/ORIGIN.json";

    /** 渲染配置（ipd.project-agent.html-render.*）。 */
    public record Settings(boolean enabled, String nodeBin, Duration timeout,
                           long maxOutputBytes, int draftMaxChars) {
        public Settings {
            Objects.requireNonNull(nodeBin, "nodeBin");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.toSeconds() < 10) throw new IllegalArgumentException("timeout 至少 10 秒");
            if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes 必须为正");
            if (draftMaxChars <= 0) throw new IllegalArgumentException("draftMaxChars 必须为正");
        }
    }

    /** 进程执行端口（生产 = ProcessBuilder；单测注入 fake，对齐 CommandProbe 模式）。 */
    @FunctionalInterface
    public interface ProcessExecutor {
        /**
         * @param command 命令行
         * @param environment 追加环境变量（继承当前进程环境）
         * @param workingDirectory 工作目录
         * @param timeout 超时（到点强杀）
         * @return 进程输出
         */
        ProcessOutput run(List<String> command, Map<String, String> environment,
                          Path workingDirectory, Duration timeout);
    }

    /** exitCode=-1 表示进程未能启动。 */
    public record ProcessOutput(int exitCode, String stdout, String stderr, boolean timedOut) { }

    public sealed interface RenderResult permits Success, Failure { }

    /** 渲染成功；summary 为 CLI 摘要行（模板/主题/面板/组件统计）。 */
    public record Success(byte[] html, String summary, int steWarnings) implements RenderResult { }

    /** 渲染失败；diagnostics 透传 CLI 原始诊断（行号/组件/正确示例），供模型一轮自修。 */
    public record Failure(String error, String diagnostics) implements RenderResult { }

    private static final Pattern ORIGIN_SHA = Pattern.compile(
        "\"am\\.mjs\"\\s*:\\s*\\{[^{}]*\"sha256\"\\s*:\\s*\"([a-fA-F0-9]{64})\"", Pattern.DOTALL);
    // 实测输出为「STE ✓ 0 warnings」（含勾号），✓/✗ 等非数字标记不参与匹配。
    private static final Pattern STE_WARNINGS = Pattern.compile("STE \\D*?(\\d+) warnings?");
    private static final Pattern NODE_VERSION = Pattern.compile("v(\\d+)");
    private static final long PROCESS_OUTPUT_CAP_BYTES = 65_536;

    private final Settings settings;
    private final ProcessExecutor executor;
    private final Path engineCacheRoot;
    private volatile Path engine;
    private volatile ProjectAgentNativeToolCatalog.Readiness readinessCache;
    private long readinessCheckedAt;

    /**
     * @param settings 渲染配置
     * @param executor 进程执行端口
     * @param engineCacheRoot 引擎提取缓存目录（如 workspaceRoot/.am-engine）
     */
    public AnswerMeHtmlRenderer(Settings settings, ProcessExecutor executor, Path engineCacheRoot) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.engineCacheRoot = Objects.requireNonNull(engineCacheRoot, "engineCacheRoot")
            .toAbsolutePath().normalize();
    }

    /** 工具目录可用性投影：未启用 / 引擎校验失败 / node 不可用或版本过低均明确不可用。 */
    public synchronized ProjectAgentNativeToolCatalog.Readiness readiness() {
        long now = System.nanoTime();
        if (readinessCache != null && now - readinessCheckedAt < TimeUnit.SECONDS.toNanos(10)) {
            return readinessCache;
        }
        ProjectAgentNativeToolCatalog.Readiness result = probeReadiness();
        readinessCache = result;
        readinessCheckedAt = now;
        return result;
    }

    private ProjectAgentNativeToolCatalog.Readiness probeReadiness() {
        if (!settings.enabled()) return unavailable("HTML 渲染未启用");
        try {
            engine();
        } catch (RuntimeException broken) {
            return unavailable("渲染引擎校验失败：" + broken.getMessage());
        }
        var output = executor.run(List.of(settings.nodeBin(), "--version"), Map.of(),
            engineCacheRoot, Duration.ofSeconds(8));
        if (output.timedOut() || output.exitCode() != 0) {
            return unavailable("Node 运行环境不可用（" + settings.nodeBin() + "）");
        }
        Matcher version = NODE_VERSION.matcher(output.stdout());
        if (!version.find() || Integer.parseInt(version.group(1)) < 20) {
            return unavailable("Node 版本须为 20 或以上");
        }
        return new ProjectAgentNativeToolCatalog.Readiness(true, null);
    }

    /**
     * 渲染一份扩展 Markdown 草稿为单文件 HTML。
     *
     * @param draft 草稿全文（UTF-8；错误行号与草稿行一一对应）
     * @return 成功含 HTML 字节与摘要；失败含可直接回报模型的诊断
     */
    public RenderResult render(String draft) {
        Objects.requireNonNull(draft, "draft");
        if (!settings.enabled()) return new Failure("HTML 渲染未启用", null);
        if (draft.isBlank()) return new Failure("draft 不能为空", null);
        if (draft.length() > settings.draftMaxChars()) {
            return new Failure("draft 超过上限 " + settings.draftMaxChars() + " 字符", null);
        }
        final Path resolved;
        try {
            resolved = engine();
        } catch (RuntimeException broken) {
            return new Failure("渲染引擎不可用：" + broken.getMessage(), null);
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("am-render-");
            Path home = Files.createDirectory(work.resolve("home"));
            Files.writeString(home.resolve("config.json"),
                "{\"open\": false, \"update_check\": false}", StandardCharsets.UTF_8);
            Path draftFile = work.resolve("draft.md");
            Files.writeString(draftFile, draft, StandardCharsets.UTF_8);
            Path html = work.resolve("page.html");
            var output = executor.run(
                List.of(settings.nodeBin(), resolved.toString(), "render",
                    draftFile.toString(), "-o", html.toString(), "--no-open"),
                Map.of("AM_HOME", home.toString(), "AM_NO_OPEN", "1"),
                work, settings.timeout());
            return interpret(output, html);
        } catch (IOException io) {
            return new Failure("渲染临时目录不可用：" + io.getMessage(), null);
        } finally {
            if (work != null) deleteRecursively(work);
        }
    }

    private RenderResult interpret(ProcessOutput output, Path html) {
        if (output.timedOut()) {
            return new Failure("渲染超时（" + settings.timeout().toSeconds() + " 秒）已终止，请精简草稿后重试",
                output.stdout());
        }
        if (output.exitCode() == -1) {
            return new Failure("渲染进程启动失败（Node 不可用？）：" + output.stderr(), null);
        }
        if (output.exitCode() != 0) {
            return new Failure("草稿未通过渲染校验，请按诊断修正后重试",
                diagnostics(output));
        }
        if (!Files.isRegularFile(html)) {
            return new Failure("渲染器未产出 HTML 文件", diagnostics(output));
        }
        try {
            long size = Files.size(html);
            if (size > settings.maxOutputBytes()) {
                return new Failure("HTML 输出超过上限 " + settings.maxOutputBytes() + " 字节", null);
            }
            byte[] bytes = Files.readAllBytes(html);
            return new Success(bytes, summaryOf(output), steWarningsOf(output));
        } catch (IOException io) {
            return new Failure("HTML 产物读取失败：" + io.getMessage(), null);
        }
    }

    /** 成功 stdout 第二行（模板/主题/面板统计），去掉缩进作为摘要。 */
    static String summaryOf(ProcessOutput output) {
        String[] lines = output.stdout().split("\n", 3);
        return lines.length >= 2 ? lines[1].trim() : "";
    }

    static int steWarningsOf(ProcessOutput output) {
        Matcher matcher = STE_WARNINGS.matcher(output.stdout());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** CLI 失败诊断 = stdout（✗ 行号/示例）+ stderr，原样透传给模型。 */
    static String diagnostics(ProcessOutput output) {
        StringBuilder text = new StringBuilder();
        if (!output.stdout().isBlank()) text.append(output.stdout().trim());
        if (!output.stderr().isBlank()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(output.stderr().trim());
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** 提取并校验引擎；缓存命中共用同一只读文件。任何不一致 fail-closed。 */
    private Path engine() {
        Path cached = engine;
        if (cached != null && Files.isRegularFile(cached)) return cached;
        synchronized (this) {
            cached = engine;
            if (cached != null && Files.isRegularFile(cached)) return cached;
            byte[] origin;
            byte[] binary;
            try (InputStream in = resource(ORIGIN_RESOURCE)) {
                origin = in.readAllBytes();
            } catch (IOException io) {
                throw new IllegalStateException("ORIGIN.json 不可读");
            }
            try (InputStream in = resource(ENGINE_RESOURCE)) {
                binary = in.readAllBytes();
            } catch (IOException io) {
                throw new IllegalStateException("渲染引擎 am.mjs 不可读");
            }
            String expected = expectedSha256(new String(origin, StandardCharsets.UTF_8));
            String actual = sha256(binary);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("am.mjs SHA-256 与 ORIGIN.json 不一致");
            }
            try {
                Files.createDirectories(engineCacheRoot);
                Path file = engineCacheRoot.resolve("am-" + actual.substring(0, 12) + ".mjs");
                Path staging = Files.createTempFile(engineCacheRoot, "am-stage-", ".mjs");
                Files.write(staging, binary);
                Files.move(staging, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                engine = file;
                return file;
            } catch (IOException io) {
                throw new IllegalStateException("渲染引擎提取失败：" + io.getMessage());
            }
        }
    }

    private static InputStream resource(String name) {
        InputStream in = AnswerMeHtmlRenderer.class.getClassLoader().getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("classpath 缺少 " + name);
        return in;
    }

    /** 从 ORIGIN.json 提取 am.mjs 的期望 SHA-256；结构不符即 fail-closed。 */
    static String expectedSha256(String originJson) {
        Matcher matcher = ORIGIN_SHA.matcher(originJson);
        if (!matcher.find()) throw new IllegalStateException("ORIGIN.json 缺少 am.mjs sha256");
        return matcher.group(1);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 生产进程执行端口：输出走临时文件避免管道死锁，超时强杀。 */
    public static ProcessOutput runProcess(List<String> command, Map<String, String> environment,
                                           Path workingDirectory, Duration timeout) {
        Process process = null;
        try {
            Path stdoutFile = Files.createTempFile(workingDirectory, "stdout-", ".log");
            Path stderrFile = Files.createTempFile(workingDirectory, "stderr-", ".log");
            var builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile());
            builder.environment().putAll(environment);
            process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessOutput(-1, "", "", true);
            }
            return new ProcessOutput(process.exitValue(),
                readCapped(stdoutFile), readCapped(stderrFile), false);
        } catch (IOException io) {
            return new ProcessOutput(-1, "", String.valueOf(io.getMessage()), false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new ProcessOutput(-1, "", "渲染进程被中断", false);
        }
    }

    private static String readCapped(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes((int) PROCESS_OUTPUT_CAP_BYTES), StandardCharsets.UTF_8);
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不阻断渲染结果返回。
                }
            });
        } catch (IOException ignored) {
            // 同上：清理是 best-effort。
        }
    }

    private static ProjectAgentNativeToolCatalog.Readiness unavailable(String reason) {
        return new ProjectAgentNativeToolCatalog.Readiness(false, reason);
    }
}
