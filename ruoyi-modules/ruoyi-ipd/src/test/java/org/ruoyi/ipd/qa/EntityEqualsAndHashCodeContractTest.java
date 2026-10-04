package org.ruoyi.ipd.qa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约门禁：所有继承 {@code BaseEntity} / {@code TenantEntity} 的类，必须显式声明
 * {@code @EqualsAndHashCode(callSuper = true)}。
 *
 * <h2>为什么是「源码级扫描」而不是反射</h2>
 * <p>Lombok 的 {@code @EqualsAndHashCode} 与 {@code @Data} 都是
 * {@code RetentionPolicy.SOURCE}（经 javap 实测 lombok 1.18.40），编译后<b>不在 class
 * 文件里</b>。因此运行时反射<b>看不见</b>它们——若照抄 {@code Qa04EntityContractTest}
 * 的反射写法（它对 {@code @TableName} 有效，因为 MyBatis-Plus 注解是 RUNTIME），
 * 会得到一个<b>永远通过</b>的假门禁。故本测试只读源码文本。
 *
 * <h2>为什么要守这个约定</h2>
 * <p>Lombok 在 {@code @Data} 而无显式 {@code @EqualsAndHashCode} 时默认
 * {@code callSuper=false}，继承自父类的字段（如 createTime / createBy）会<b>静默</b>不参与
 * equals/hashCode。本仓曾有一批实体集体漏写此项，靠人工逐个排查才补齐；此前全仓
 * <b>没有任何门禁</b>守这个约定，新增实体漏写不会报错。
 *
 * <h2>范围塌缩自检</h2>
 * <p>本测试的第一条断言是「扫描确实扫到了东西」。若仓库根解析失败或源码目录不存在，
 * 它会以「扫到 0 个文件」失败，而不是静默通过——避免门禁退化成假绿。
 */
@Tag("dev")
class EntityEqualsAndHashCodeContractTest {

    /** 只匹配真实类声明，避免命中注释或字符串里的字样（注释已先被剥离，双保险）。 */
    private static final Pattern EXTENDS_ENTITY = Pattern.compile(
        "\\bclass\\s+\\w+[^{;]*\\bextends\\s+(?:[\\w.]+\\.)*(?:BaseEntity|TenantEntity)\\b");

    /** 允许 callSuper 与 true 之间有换行/空格；值不接受 false。 */
    private static final Pattern CALL_SUPER_TRUE = Pattern.compile(
        "@EqualsAndHashCode\\s*\\([^)]*callSuper\\s*=\\s*true", Pattern.DOTALL);

    private static final Pattern HAS_EHC = Pattern.compile("@EqualsAndHashCode\\b");

    /**
     * 扫描下限。当前真实规模约 2.3k 个主源码文件、150+ 个实体子类；
     * 取宽松下限只为捕捉「范围塌缩」，而非锁定具体数量。
     */
    private static final int MIN_SCANNED_FILES = 1000;
    private static final int MIN_ENTITY_CLASSES = 100;

    @Test
    void 所有实体子类必须显式声明_callSuper为true的_EqualsAndHashCode() throws IOException {
        Path repoRoot = resolveRepoRoot();

        List<Path> sources = collectMainSources(repoRoot);

        // 断言一：范围塌缩自检——扫不到东西就不许通过。
        assertThat(sources)
            .as("源码扫描范围塌缩：仓库根=%s，扫到 %d 个主源码文件（下限 %d）。"
                + "门禁在扫不到源码时必须报红，不能静默通过。",
                repoRoot, sources.size(), MIN_SCANNED_FILES)
            .hasSizeGreaterThan(MIN_SCANNED_FILES);

        List<String> violations = new ArrayList<>();
        int entityClasses = 0;

        for (Path file : sources) {
            String raw = Files.readString(file);
            String code = stripComments(raw);
            if (!EXTENDS_ENTITY.matcher(code).find()) {
                continue;
            }
            entityClasses++;
            if (!HAS_EHC.matcher(code).find()) {
                violations.add(rel(repoRoot, file) + " —— 缺 @EqualsAndHashCode（Lombok 默认 callSuper=false，父类字段不参与 equals）");
            } else if (!CALL_SUPER_TRUE.matcher(code).find()) {
                violations.add(rel(repoRoot, file) + " —— 有 @EqualsAndHashCode 但未写 callSuper = true");
            }
        }

        // 断言二：实体子类数量下限——同上，防范围塌缩。
        assertThat(entityClasses)
            .as("扫到的实体子类只有 %d 个（下限 %d），疑似类声明匹配失效，门禁不可信。",
                entityClasses, MIN_ENTITY_CLASSES)
            .isGreaterThan(MIN_ENTITY_CLASSES);

        // 断言三：真正的契约。
        assertThat(violations)
            .as("发现 %d 个实体未按契约声明 @EqualsAndHashCode(callSuper = true)：%n  %s",
                violations.size(), String.join(System.lineSeparator() + "  ", violations))
            .isEmpty();
    }

    /**
     * 从当前工作目录向上找仓库根：同时含 {@code ruoyi-modules} 与 {@code ruoyi-common} 的目录。
     * Surefire 的工作目录是模块 basedir，故需上溯。
     */
    private static Path resolveRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("ruoyi-modules"))
                && Files.isDirectory(dir.resolve("ruoyi-common"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "无法从 " + Paths.get("").toAbsolutePath() + " 上溯定位仓库根（需含 ruoyi-modules 与 ruoyi-common）");
    }

    /** 收集全部 {@code src/main/java} 下的 .java，排除 target 与历史归档目录。 */
    private static List<Path> collectMainSources(Path repoRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    String s = p.toString().replace('\\', '/');
                    return s.contains("/src/main/java/")
                        && !s.contains("/target/")
                        && !s.contains("/.codex/")
                        && !s.contains("/.harness/");
                })
                .toList();
        }
    }

    /**
     * 剥离行注释与块注释。方向是安全的：剥掉注释只会让注释里的字样不再命中
     * （正是我们要排除的），不会让真实类声明消失。
     */
    private static String stripComments(String src) {
        // 先块注释，再行注释；顺序不可颠倒，否则 /* 里的 // 会破坏块注释配对。
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)//.*$", " ");
    }

    private static String rel(Path repoRoot, Path file) {
        Path r = repoRoot.relativize(file);
        return r.toString().replace('\\', '/');
    }
}
