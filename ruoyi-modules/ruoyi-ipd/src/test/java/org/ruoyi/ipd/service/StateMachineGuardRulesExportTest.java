package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.StateTransitionRule;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard 规则表导出契约（补遗 §5-1：规则表导出 JSON → 前端消费 + 跨仓契约测试）
 *
 * <p>病根背景：前端 _shared/ipd-state-machines.ts 手写 9 台机器 transitions，与后端
 * DefaultStateMachineGuard 规则表无机械对账，出现 C7/C9/C10 一类词表/迁移图漂移
 * （例：bonus_pool DRAFT→DISTRIBUTED 直分路径后端已登记、前端机器缺失）。本测试把守卫
 * 规则表导出为可版本化的契约 JSON（本仓 docs + 前端仓 _shared 双写），前端机器从该 JSON
 * 派生 transitions，形成单一事实源。
 *
 * <p>模式（与 baseline 棘轮同构：默认 compare 会红，显式 flag 才重写）：
 * <ul>
 *   <li>默认：比较两份既有 JSON 与内存规则表现态；不一致 → FAIL 并输出 sha256 对照。
 *       → 改 DefaultStateMachineGuard 规则不重导出契约文件，本测试必红（防漂移机制化）</li>
 *   <li>-Dguard.rules.export=true：显式重写两份契约文件（改规则后本地跑一次，git diff 呈现契约变更供 review）</li>
 * </ul>
 *
 * <p>导出字段：entityType/fromState/toState/trigger/crossDomain（key 可由前四者推导不重复导出；
 * description 为人读注释、非机器契约，不导出——避免改注释引起契约文件噪声 diff）。
 * meta.version = 排序后 rules JSON 的 sha256，前端契约测试可据此锁定版本。
 */
@Tag("dev")
class StateMachineGuardRulesExportTest {

    private static final String EXPORT_FLAG = "guard.rules.export";
    /** 本仓契约文件（SSOT 留档，供文档/镜像对账） */
    private static final Path BE_FILE =
        Path.of("docs", "ipd-系统说明", "state-machine-guard-rules.json");
    /** 前端仓契约文件（前端 ipd-state-machines.ts 运行时消费源） */
    private static final String FE_ENV_KEY = "IPD_FE_SHARED_DIR";
    private static final String FE_DEFAULT_DIR =
        "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared";

    private final DefaultStateMachineGuard guard = new DefaultStateMachineGuard(null, null);
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeEach
    void seed() {
        guard.resetRules();
        guard.initRules();
    }

    /** 由内存规则表现态生成契约 rules 数组（按 key 排序，字段固定顺序） */
    private List<Map<String, Object>> buildRules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StateTransitionRule r : guard.rulesSnapshot().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entityType", r.getEntityType());
            m.put("fromState", r.getFromState());
            m.put("toState", r.getToState());
            m.put("trigger", r.getTrigger());
            m.put("crossDomain", r.isCrossDomain());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("entityType"))
            + "|" + m.get("fromState") + "|" + m.get("toState") + "|" + m.get("trigger")));
        return out;
    }

    private String render(List<Map<String, Object>> rules) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "DefaultStateMachineGuard.initRules（后端守卫规则表导出，补遗 §5-1）");
        meta.put("version", "sha256:" + sha256(mapper.writeValueAsString(rules)));
        meta.put("ruleCount", rules.size());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("rules", rules);
        return mapper.writeValueAsString(root) + System.lineSeparator();
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private Path feFile() {
        String dir = System.getProperty(FE_ENV_KEY,
            System.getenv().getOrDefault(FE_ENV_KEY, FE_DEFAULT_DIR));
        return Path.of(dir, "state-machine-guard-rules.json");
    }

    @Test
    @DisplayName("守卫规则表 ↔ 契约 JSON 双仓文件一致（export 模式重写后比较模式必须绿）")
    void contractFilesMatchInMemoryRules() throws Exception {
        String expected = render(buildRules());
        boolean export = Boolean.getBoolean(EXPORT_FLAG);

        List<Path> targets = new ArrayList<>();
        targets.add(resolveRepoRelative(BE_FILE));
        Path fe = feFile();
        // 前端仓缺失属环境错误（与 check-api-contract-fe-be.mjs 语义一致），不得静默跳过
        assertThat(fe.getParent()).as("前端 _shared 目录必须存在（或设 " + FE_ENV_KEY + "）").exists();
        targets.add(fe);

        List<String> diffs = new ArrayList<>();
        for (Path p : targets) {
            File f = p.toFile();
            if (export) {
                Files.createDirectories(f.getParentFile().toPath());
                Files.writeString(f.toPath(), expected, StandardCharsets.UTF_8);
                continue;
            }
            assertThat(f).as("契约文件缺失：%s（跑 -D%s=true 生成）", f, EXPORT_FLAG).exists();
            String actual = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            if (!actual.equals(expected)) {
                diffs.add(f + " sha 不符：期望 " + sha256(expected) + " 实际 " + sha256(actual));
            }
        }
        if (!diffs.isEmpty()) {
            throw new AssertionError(
                "守卫规则表与契约 JSON 漂移（改了 DefaultStateMachineGuard 规则须重导出：\n"
                    + "  mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=StateMachineGuardRulesExportTest"
                    + " -Dguard.rules.export=true test\n）：\n" + String.join("\n", diffs));
        }
    }

    /** 回溯仓库根（Surefire fork 的工作目录=模块 basedir，向上最多 3 级找含 docs/ipd-系统说明 的目录） */
    private Path resolveRepoRelative(Path rel) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i <= 3 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs").resolve("ipd-系统说明"))) {
                return dir.resolve(rel);
            }
        }
        throw new IllegalStateException("未能从 " + dir + " 回溯到仓库根（docs/ipd-系统说明 缺失）");
    }
}
