package org.ruoyi.ipd.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R149 B1：工作日推算（{@link Workdays}）节假日感知重载单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>旧 {@link Workdays#add(Date, int)} 双参签名：仅跳过周末（向后兼容）</li>
 *   <li>新 {@link Workdays#add(Date, int, java.util.Collection)} 三参签名：周末 ∪ holidays 合并跳过</li>
 *   <li>边界：{@code workdays <= 0} ⇒ 直接返回 from；{@code from=null} ⇒ IAE；holidays=null ⇒ 等价于空集合</li>
 *   <li>时分秒保留：起始 09:30:15 经累加 5 个工作日后仍为 09:30:15（仅日期变化）</li>
 *   <li>2026 国庆连假场景：2026-09-25 起 10 个工作日跨越国庆 7 天假期 + 中秋 1 天 → 应正确跨过</li>
 * </ul>
 */
@Tag("dev")
class WorkdaysTest {

    private static Date at(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day, hour, minute, second);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static LocalDate ld(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    /* ====================== 1. 旧签名向后兼容 ====================== */

    @Test
    @DisplayName("[R149-B1] add(Date, int) 双参签名：仅跳过周末（不感知节假日）")
    void legacyAdd_weekendsOnly() {
        // 2026-01-02 周五 → +1 工作日 ⇒ 2026-01-05 周一（跳过周六/周日）
        Date from = at(2026, 1, 2, 9, 0, 0);
        Date out = Workdays.add(from, 1);
        assertThat(toLocalDate(out)).isEqualTo(ld(2026, 1, 5));
    }

    /* ====================== 2. 新签名：周末 ∪ holidays ====================== */

    @Test
    @DisplayName("[R149-B1] add(Date, int, Collection) 三参签名：跳过周末 + holidays")
    void addWithHolidays_skipsBoth() {
        // 2026-01-01 周四 元旦；模拟 1-1+1-2 元旦连假，+1 工作日 ⇒ 2026-01-05 周一
        // (跳过 1-2 假 + 1-3 周六 + 1-4 周日 + 1-5 周一算第 1 个工作日)
        Date from = at(2026, 1, 1, 9, 0, 0);
        Set<LocalDate> holidays = new HashSet<>(List.of(ld(2026, 1, 1), ld(2026, 1, 2)));
        Date out = Workdays.add(from, 1, holidays);
        assertThat(toLocalDate(out)).isEqualTo(ld(2026, 1, 5));
    }

    @Test
    @DisplayName("[R149-B1] add(Date, int, null) holidays=null ⇒ 等价于纯周末跳过")
    void addWithNullHolidays_sameAsLegacy() {
        Date from = at(2026, 1, 2, 9, 0, 0);
        Date out1 = Workdays.add(from, 1);
        Date out2 = Workdays.add(from, 1, null);
        assertThat(out2).isEqualTo(out1);
    }

    @Test
    @DisplayName("[R149-B1] add(Date, int, emptySet) holidays 空集合 ⇒ 等价于纯周末跳过")
    void addWithEmptyHolidays_sameAsLegacy() {
        Date from = at(2026, 1, 2, 9, 0, 0);
        Date out1 = Workdays.add(from, 1);
        Date out2 = Workdays.add(from, 1, Collections.emptySet());
        assertThat(out2).isEqualTo(out1);
    }

    /* ====================== 3. 边界条件 ====================== */

    @Test
    @DisplayName("[R149-B1] workdays=0 ⇒ 直接返回 from")
    void zeroWorkdays_returnsFrom() {
        Date from = at(2026, 1, 2, 9, 30, 15);
        Date out = Workdays.add(from, 0);
        assertThat(out).isEqualTo(from);
        // 三参签名同样
        Date out2 = Workdays.add(from, 0, List.of(ld(2026, 1, 1)));
        assertThat(out2).isEqualTo(from);
    }

    @Test
    @DisplayName("[R149-B1] workdays<0 ⇒ 直接返回 from（防御性兜底）")
    void negativeWorkdays_returnsFrom() {
        Date from = at(2026, 1, 2, 9, 0, 0);
        Date out = Workdays.add(from, -5);
        assertThat(out).isEqualTo(from);
    }

    @Test
    @DisplayName("[R149-B1] from=null ⇒ IllegalArgumentException")
    void nullFrom_throws() {
        assertThatThrownBy(() -> Workdays.add(null, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("from");
        assertThatThrownBy(() -> Workdays.add(null, 1, Collections.emptySet()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[R149-B1] holidays 集合含 null 元素 ⇒ 自动过滤（不抛 NPE）")
    void holidaysWithNullElement_skipped() {
        Date from = at(2026, 1, 2, 9, 0, 0);
        // 含 null 的 list；Workdays 应只比较非 null 元素
        Date out = Workdays.add(from, 1, Arrays.asList(ld(2026, 1, 1), null));
        assertThat(toLocalDate(out)).isEqualTo(ld(2026, 1, 5));
    }

    /* ====================== 4. 时分秒保留 ====================== */

    @Test
    @DisplayName("[R149-B1] 起始时分秒 经 N 个工作日累加后保持不变")
    void preservesTimeOfDay() {
        Date from = at(2026, 1, 2, 9, 30, 45);
        Date out = Workdays.add(from, 5);
        Calendar c = Calendar.getInstance();
        c.setTime(out);
        assertThat(c.get(Calendar.HOUR_OF_DAY)).isEqualTo(9);
        assertThat(c.get(Calendar.MINUTE)).isEqualTo(30);
        assertThat(c.get(Calendar.SECOND)).isEqualTo(45);
    }

    /* ====================== 5. 真实场景：2026 国庆连假 ====================== */

    @Test
    @DisplayName("[R149-B1] 2026 中秋+国庆连假：9-25 起 +10 工作日跨越 13 个日历日")
    void nationalDayLongHolidayScenario() {
        // 2026-09-25 周五 中秋；+10 工作日
        // 跳过：09-26/27(周末), 09-28~10-02(中秋 1 天+国庆前 4 天), 10-03/04(周末),
        // 10-05~07(国庆后 3 天), 10-10/11(周末)；共 12 假日 + 4 周末
        // 累计：10-08 周四工作(1) → 10-09 周五工作(2) → 跳周末 → 10-12 工作(3) → 10-16 工作(4-7) →
        // 跳周末 → 10-19 工作(8) → 10-20 工作(9) → 10-21 工作(10)
        Date from = at(2026, 9, 25, 9, 0, 0);
        Set<LocalDate> holidays = new HashSet<>(List.of(
            ld(2026, 9, 25), ld(2026, 9, 26), ld(2026, 9, 27), ld(2026, 9, 28),
            ld(2026, 9, 29), ld(2026, 9, 30),
            ld(2026, 10, 1), ld(2026, 10, 2), ld(2026, 10, 3),
            ld(2026, 10, 4), ld(2026, 10, 5), ld(2026, 10, 6), ld(2026, 10, 7)));
        Date out = Workdays.add(from, 10, holidays);
        assertThat(toLocalDate(out)).isEqualTo(ld(2026, 10, 21));
    }

    @Test
    @DisplayName("[R149-B1] 2026 春节：2-17 起 +5 工作日（7 天春节假 + 1 个调休补班日）")
    void springFestivalScenario() {
        // 2026-02-17 周二 春节；+5 工作日
        // 春节假：02-17~02-23（共 7 天，包含周末补假 02-21/22）—— 工作日跳 02-18~02-23
        // 02-24 周二 = 工作(1)，02-25 工作(2)，02-26 工作(3)，02-27 工作(4)
        // 02-28 周六 跳，03-01 周日 跳，03-02 周一 工作(5)
        Date from = at(2026, 2, 17, 9, 0, 0);
        Set<LocalDate> holidays = new HashSet<>(List.of(
            ld(2026, 2, 17), ld(2026, 2, 18), ld(2026, 2, 19),
            ld(2026, 2, 20), ld(2026, 2, 21), ld(2026, 2, 22), ld(2026, 2, 23)));
        Date out = Workdays.add(from, 5, holidays);
        assertThat(toLocalDate(out)).isEqualTo(ld(2026, 3, 2));
    }

    /* ====================== 工具 ====================== */

    private static LocalDate toLocalDate(Date d) {
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
