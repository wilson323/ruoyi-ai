package org.ruoyi.ipd.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R215-WP2.3：@Scheduled 错峰一致性全局哨兵（治理病根③「规则表与接线点靠人肉对账」）。
 *
 * <p><b>扫描机制（新增 job 零遗漏）</b>：不用写死类名单——遍历 {@code src/main/java/org/ruoyi/ipd}
 * 下全部 .java 源文件，正则提取 {@code @Scheduled(cron = "...")}。任何人在任何包新加 cron 型 job，
 * 无需改本测试即自动进入断言范围（借鉴 ControllerPermissionAnnotationContractTest 的源码文本扫描路子；
 * 比反射法维护成本低：不必维护 FQN 清单，也不必担心类加载副作用）。
 *
 * <p><b>断言 A（防撞）</b>：所有「每日固定时刻型」cron（秒/分/时 为常量、日/月/周 为通配）按 HH:mm
 * 去重，任意两个 job 不得占同一分钟。占位符 cron（如 {@code ${ipd.hr.sync.cron:0 0 2 * * ?}}）
 * 取冒号后默认值参与比对。
 *
 * <p><b>断言 B（防漏登记）</b>：每个每日时刻型 job 的「类名 + HH:mm」必须出现在
 * {@link IpdSchedulingConfig} 的源码文本（错峰表 javadoc）里，且类名与时刻之间不得跨 "/" 分隔符
 * （错峰表各条目以 " /" 分隔，防止拿相邻条目凑数）。失败消息点名漏登记的具体 job 与时刻。
 *
 * <p><b>口径豁免（显式、不静默）</b>：
 * <ul>
 *   <li>{@code NotificationOutboxScanner}——fixedDelayString 常驻间隔任务（每 30s），非每日固定时刻，
 *       正则天然不匹配 {@code cron=}，不参与 A/B；错峰表以「每 30s 轮询」口径登记，无撞时刻问题。</li>
 *   <li>{@code HrSyncJob}（02:00）——见 {@link #EXEMPT_FROM_REGISTRATION} 注释。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("R215-WP2.3 @Scheduled 错峰一致性全局哨兵——A 防撞 / B 防漏登记")
class SchedulingCronContractSentinelTest {

    /** 源文件中 @Scheduled cron 注解的正则（仅命中 cron= 形态，fixedDelay/fixedRate 自动排除）。 */
    private static final Pattern SCHEDULED_CRON =
        Pattern.compile("@Scheduled\\s*\\(\\s*cron\\s*=\\s*\"([^\"]+)\"");

    /**
     * 断言 B 的显式豁免名单：HrSyncJob 02:00 属 HR 同步自有时段，cron 为占位符
     * {@code ${ipd.hr.sync.cron:0 0 2 * * ?}}（默认值登记在 HrSyncProperties#cron，运维可改），
     * 不在 IpdSchedulingConfig 错峰表「09:00~09:35 晨间业务窗口」登记口径内。
     * 它仍参与断言 A（防撞）——02:00 若被别的 job 撞上照样红。
     */
    private static final Map<String, String> EXEMPT_FROM_REGISTRATION = Map.of(
        "HrSyncJob", "HR 同步自有时段（02:00），占位符可配 + HrSyncProperties 登记，错峰表口径外——显式豁免"
    );

    /** 发现下限：现有 9 个 cron 注解（GateSignScanScheduler 2 个），防正则失效导致全空假绿。 */
    private static final int MIN_DISCOVERED_CRON_JOBS = 9;

    /** 一个 @Scheduled(cron=...) 命中项：类名 / 相对路径 / 行号 / 原始串 / 生效 cron / 时刻（不可解析为 null）；dayOfMonth 非空=每月固定日型。 */
    private record CronJob(String className, Path file, long line, String rawCron,
                           String effectiveCron, Integer hour, Integer minute, Integer dayOfMonth) {
        String timeKey() { return String.format("%02d:%02d", hour, minute); }
        /** 防撞分组键：每日型按 HH:mm；每月型按「D日@HH:mm」，与每日 10:00 互不串组。 */
        String guardKey() { return dayOfMonth == null ? timeKey() : "D" + dayOfMonth + "@" + timeKey(); }
        String where() { return className + " " + guardKey() + " @ " + file + ":" + line + " (cron=\"" + rawCron + "\")"; }
    }

    @Test
    @DisplayName("SENT-0 源文件扫描能发现全部 cron 型 @Scheduled（下限 " + MIN_DISCOVERED_CRON_JOBS + "，防扫描失效假绿）")
    void scanDiscoversAllCronJobs() throws IOException {
        List<CronJob> jobs = scanCronJobs();
        assertThat(jobs)
            .as("正则扫描 src/main/java/org/ruoyi/ipd 发现的 cron 注解数不得少于 %d（现状 9；若正则/布局变了请同步修哨兵而不是让它空转）",
                MIN_DISCOVERED_CRON_JOBS)
            .hasSizeGreaterThanOrEqualTo(MIN_DISCOVERED_CRON_JOBS);
        // 占位符 cron 必须能解析出默认时刻，否则等于给哨兵开后门
        List<String> unresolvable = jobs.stream()
            .filter(j -> j.hour() == null)
            .map(CronJob::where).toList();
        assertThat(unresolvable)
            .as("以下 cron 解析不出「每日固定时刻」，哨兵无法守护——需要人看形态决定是否豁免：%s", unresolvable)
            .isEmpty();
    }

    @Test
    @DisplayName("SENT-1 断言 A：任意两个每日时刻型 job 不得撞同一分钟（占位符取默认值比对）")
    void noTwoJobsShareSameDailyMinute() throws IOException {
        List<CronJob> timed = dailyTimedJobs();
        Map<String, List<CronJob>> byTime = new LinkedHashMap<>();
        for (CronJob j : timed) {
            byTime.computeIfAbsent(j.guardKey(), k -> new ArrayList<>()).add(j);
        }
        List<String> clashes = byTime.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> "时刻 " + e.getKey() + " 被撞：" + e.getValue().stream().map(CronJob::where).toList())
            .toList();
        assertThat(clashes)
            .as("错峰撞车——同分钟两个 job 会互相争抢调度线程池/DB 峰值。撞点：%s", clashes)
            .isEmpty();
    }

    @Test
    @DisplayName("SENT-2 断言 B：每个每日时刻型 job 的「类名+时刻」必须在 IpdSchedulingConfig 错峰表登记")
    void everyDailyTimedJobIsRegisteredInStaggerTable() throws IOException {
        String registration = Files.readString(
            locateSrcMainJava().resolve("org/ruoyi/ipd/config/IpdSchedulingConfig.java"), StandardCharsets.UTF_8);
        List<String> unregistered = new ArrayList<>();
        for (CronJob j : dailyTimedJobs()) {
            if (EXEMPT_FROM_REGISTRATION.containsKey(j.className())) {
                continue; // 显式豁免，见常量注释
            }
            // 类名与时刻必须落在错峰表同一 "/" 分隔条目内（[^/] 不跨分隔符，防相邻条目凑数）
            Pattern p = Pattern.compile(Pattern.quote(j.className()) + "[^/]*" + Pattern.quote(j.timeKey()));
            if (!p.matcher(registration).find()) {
                unregistered.add(j.where());
            }
        }
        assertThat(unregistered)
            .as("漏登记错峰表——新增/改时刻的 job 必须在 IpdSchedulingConfig javadoc 补「类名 HH:mm」行"
                + "（否则兄弟会话无从知晓撞点）：%s", unregistered)
            .isEmpty();
    }

    // ===== helpers =====

    private List<CronJob> dailyTimedJobs() throws IOException {
        return scanCronJobs().stream().filter(j -> j.hour() != null && j.minute() != null).toList();
    }

    /** 遍历 org/ruoyi/ipd 下全部 .java，正则提取 @Scheduled(cron=...)，含行号供失败消息定位。 */
    private static List<CronJob> scanCronJobs() throws IOException {
        Path ipdRoot = locateSrcMainJava().resolve("org/ruoyi/ipd");
        List<CronJob> jobs = new ArrayList<>();
        List<Path> sources;
        try (var stream = Files.walk(ipdRoot)) {
            sources = stream.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
        for (Path f : sources) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = SCHEDULED_CRON.matcher(src);
            String cls = f.getFileName().toString().replace(".java", "");
            while (m.find()) {
                long line = src.substring(0, m.start()).lines().count();
                String raw = m.group(1);
                String eff = resolvePlaceholder(raw);
                Integer hour = null;
                Integer minute = null;
                Integer dayOfMonth = null;
                int[] hm = dailyFixedMinuteOfHour(eff);
                if (hm != null) {
                    hour = hm[0];
                    minute = hm[1];
                } else {
                    int[] mdh = monthlyFixedTime(eff);
                    if (mdh != null) {
                        dayOfMonth = mdh[0];
                        hour = mdh[1];
                        minute = mdh[2];
                    }
                }
                jobs.add(new CronJob(cls, ipdRoot.relativize(f), line, raw, eff, hour, minute, dayOfMonth));
            }
        }
        return jobs;
    }

    /** {@code ${key:default}} → default；{@code ${key}}（无默认值）→ null（哨兵明确报不可解析）。 */
    private static String resolvePlaceholder(String raw) {
        String v = raw.trim();
        if (v.startsWith("${") && v.endsWith("}")) {
            int i = v.indexOf(':');
            if (i < 0) return null;
            return v.substring(i + 1, v.length() - 1).trim();
        }
        return v;
    }

    /** Spring 6 段 cron「每日固定时刻」判定：秒任意、分/时为常量数字、日/月/周通配。返回 [hour,minute] 或 null。 */
    private static int[] dailyFixedMinuteOfHour(String cron) {
        if (cron == null) return null;
        String[] f = cron.trim().split("\\s+");
        if (f.length != 6) return null;
        boolean secondAny = Set.of("*", "?").contains(f[0]) || f[0].matches("\\d{1,2}");
        boolean minuteFixed = f[1].matches("\\d{1,2}");
        boolean hourFixed = f[2].matches("\\d{1,2}");
        boolean dailyWild = Set.of("*", "?").contains(f[3]) && Set.of("*", "?").contains(f[4])
            && Set.of("*", "?").contains(f[5]);
        if (!(secondAny && minuteFixed && hourFixed && dailyWild)) return null;
        int hour = Integer.parseInt(f[2]);
        int minute = Integer.parseInt(f[1]);
        if (hour >= 24 || minute >= 60) return null;
        return new int[]{hour, minute};
    }

    /**
     * Spring 6 段 cron「每月固定日固定时刻」判定（R220 补盲：AllowanceMonthlyLedgerScheduler
     * {@code 0 0 10 1 * ?} 形态此前落在解析盲区，SENT-0 报「需人看形态决定是否豁免」）：
     * 秒任意、分/时/日为常量数字、月与周通配。返回 [day,hour,minute] 或 null。
     * 每月型参与防撞（guardKey 带日号，不与每日同刻误撞）与错峰表登记断言（按 HH:mm 匹配类名条目）。
     */
    private static int[] monthlyFixedTime(String cron) {
        if (cron == null) return null;
        String[] f = cron.trim().split("\\s+");
        if (f.length != 6) return null;
        boolean secondAny = Set.of("*", "?").contains(f[0]) || f[0].matches("\\d{1,2}");
        boolean minuteFixed = f[1].matches("\\d{1,2}");
        boolean hourFixed = f[2].matches("\\d{1,2}");
        boolean dayFixed = f[3].matches("\\d{1,2}");
        boolean monthWild = Set.of("*", "?").contains(f[4]);
        boolean weekWild = Set.of("*", "?").contains(f[5]);
        if (!(secondAny && minuteFixed && hourFixed && dayFixed && monthWild && weekWild)) return null;
        int day = Integer.parseInt(f[3]);
        int hour = Integer.parseInt(f[2]);
        int minute = Integer.parseInt(f[1]);
        if (day < 1 || day > 31 || hour >= 24 || minute >= 60) return null;
        return new int[]{day, hour, minute};
    }

    /** 定位 src/main/java：从 user.dir（mvn -pl 时即模块根）向上找，兼容不同调用目录。 */
    private static Path locateSrcMainJava() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path candidate = p.resolve("src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("无法从 user.dir=" + System.getProperty("user.dir")
            + " 向上定位 src/main/java——哨兵扫描根失效，请在模块目录下跑 mvn 或修本定位逻辑");
    }
}
