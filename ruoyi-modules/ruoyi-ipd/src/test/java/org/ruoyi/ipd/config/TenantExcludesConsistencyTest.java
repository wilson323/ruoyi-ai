package org.ruoyi.ipd.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class TenantExcludesConsistencyTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir"))
            .getParent().getParent();
    private static final Path APP_YML = REPO_ROOT.resolve(
            "ruoyi-admin/src/main/resources/application.yml");
    private static final Path SQL_DIR = REPO_ROOT.resolve("docs/script/sql/update");

    /**
     * 表名标识符字符类，CREATE / DROP / excludes 三处共用。
     *
     * <p>2026-10-03 收口：原先这三条正则各自写死 {@code [a-z_]+}，**吞不下含数字的表名**。
     * 后果是 {@code p0_escalation_chain} 在 DDL 侧与 excludes 侧**同时隐形**——
     * 它既没被登记进 tenant.excludes，这道门禁也照样报绿（静默失败）。
     * 实测 {@code docs/script/sql/update} 下 89 张建表里有 1 张含数字，即该盲区的实际暴露面。
     * 三处同源后，两侧口径不会再各自漂移。
     */
    private static final String IDENT_CHARS = "a-zA-Z0-9_";
    private static final Pattern CREATE_TABLE_PAT = Pattern.compile(
        "CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?[`']?([" + IDENT_CHARS + "]+)[`']?\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    // Only this explicit module-retirement migration can narrow historical DDL coverage.
    private static final Path RETIREMENT_SQL = SQL_DIR.resolve("2026-10-02-workflow-modules-offline.sql");
    private static final Pattern DROP_TABLE_PAT = Pattern.compile(
        "(?:^|;)\\s*DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?[`']?([" + IDENT_CHARS + "]+)[`']?\\s*(?=;)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern SQL_COMMENTS = Pattern.compile("/\\*.*?\\*/|--[^\\r\\n]*|#[^\\r\\n]*", Pattern.DOTALL);
    /** excludes 条目：YAML 缩进空格后 {@code - 表名}。原为 {@code [a-z_]+} 且未开大小写折叠，与上面两处一同收口。 */
    private static final Pattern EXCLUDE_ITEM_PAT = Pattern.compile(
        "^\\s*-\\s+([" + IDENT_CHARS + "]+)\\s*$", Pattern.CASE_INSENSITIVE);

    private Set<String> ddlTables() throws IOException {
        Set<String> result = new HashSet<>();
        try (Stream<Path> files = Files.walk(SQL_DIR)) {
            files.filter(Files::isRegularFile)
                 .filter(x -> x.toString().endsWith(".sql"))
                 .forEach(x -> {
                    try {
                        String c = Files.readString(x);
                        Matcher m = CREATE_TABLE_PAT.matcher(c);
                        while (m.find()) {
                            String name = m.group(1).toLowerCase();
                            if (!name.startsWith("sys_") && !name.startsWith("flow_")
                                && !name.startsWith("trace_") && !name.startsWith("snail")) {
                                result.add(name);
                            }
                        }
                    } catch (IOException ignored) {}
                 });
        }
        String retirement = Files.readString(RETIREMENT_SQL);
        Set<String> candidates = retiredTables(retirement);
        Set<String> consumers = new HashSet<>();
        // Current repository module roots only; archived worktrees and temporary candidates are not consumers.
        try (Stream<Path> roots = Files.list(REPO_ROOT)) {
            for (Path moduleRoot : roots.filter(Files::isDirectory)
                    .filter(x -> x.getFileName().toString().startsWith("ruoyi-")).toList()) {
                try (Stream<Path> paths = Files.walk(moduleRoot)) {
                    for (Path source : paths.filter(Files::isRegularFile)
                            .filter(x -> x.toString().replace('\\', '/').contains("/src/main/"))
                            .filter(x -> x.toString().endsWith(".java") || x.toString().endsWith(".xml")
                                || x.toString().endsWith(".sql")).toList()) {
                        consumers.addAll(consumerTables(Files.readString(source), candidates));
                    }
                }
            }
        }
        return requiredDdlTables(result, retirement, consumers);
    }

    static Set<String> retiredTables(String sql) {
        Set<String> retired = new HashSet<>();
        Matcher matcher = DROP_TABLE_PAT.matcher(SQL_COMMENTS.matcher(sql).replaceAll(" "));
        while (matcher.find()) retired.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return retired;
    }

    static Set<String> consumerTables(String source, Set<String> candidates) {
        Set<String> referenced = new HashSet<>();
        for (String table : candidates) {
            if (Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(table) + "(?![a-z0-9_])",
                    Pattern.CASE_INSENSITIVE).matcher(source).find()) referenced.add(table);
        }
        return referenced;
    }

    static Set<String> requiredDdlTables(Set<String> created, String retirementSql, Set<String> consumers) {
        Set<String> retired = retiredTables(retirementSql);
        retired.removeAll(consumers); // A current mapper/entity/SQL reference keeps the original gate red.
        Set<String> required = new HashSet<>(created);
        required.removeAll(retired);
        return required;
    }

    private Set<String> excludedTables() throws IOException {
        String content = Files.readString(APP_YML);
        int start = content.indexOf("# 多租户配置");
        if (start < 0) return Set.of();
        int end = content.indexOf("\n# ", start);
        if (end < 0) end = content.length();
        String section = content.substring(start, end);
        Set<String> result = new HashSet<>();
        for (String line : section.split("\n")) {
            Matcher m = EXCLUDE_ITEM_PAT.matcher(line);
            if (m.matches()) {
                String table = m.group(1).toLowerCase();
                if (!table.startsWith("sys_") && !table.startsWith("flow_")
                    && !table.startsWith("trace_")) {
                    result.add(table);
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("R8-P0-1：所有 DDL IPD 业务表均已在 tenant.excludes 中登记")
    void allDdlTablesExcluded() throws IOException {
        Set<String> ddl = ddlTables();
        Set<String> excluded = excludedTables();
        Set<String> missing = new HashSet<>(ddl);
        missing.removeAll(excluded);
        assertThat(missing)
            .as("以下 DDL 表未登记在 tenant.excludes：%s", missing)
            .isEmpty();
    }

    @Test
    @DisplayName("R8-P0-1：已知缺失表 9 张均在 excludes 中")
    void knownMissingTablesAreCovered() throws IOException {
        Set<String> excluded = excludedTables();
        Set<String> required = new HashSet<>(Arrays.asList(
            "bonus_allocations", "project_scores", "contributions",
            "negative_feedbacks", "requirement_pools", "ai_model_configs",
            "system_config_versions", "legacy_imports", "receipt_ledgers"
        ));
        Set<String> notFound = new HashSet<>(required);
        notFound.removeAll(excluded);
        assertThat(notFound)
            .as("以下表未在 tenant.excludes 中：%s", notFound)
            .isEmpty();
    }
}
