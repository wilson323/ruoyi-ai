package org.ruoyi.ipd.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 工作日推算（删除审核 F29：2+2 个工作日期限）。
 *
 * <p>规则：起始时刻起，逐日推进，跳过周六/周日，<b>累计 N 个工作日后的同一时刻</b>。
 *
 * <p><b>R149 B1 升级</b>：可选传入 holidays 集合（在周末基础上额外跳过的日期，如
 * {@code 2026-10-01} 国庆节）；holidays 来源 {@code system_configs.config_key='calendar.holidays'}，
 * 形如 {@code {"2026":["0101","0102",...], "2026-work":["0927","0928"]}}
 * （后者为调休补班——本类只读"放假"集合，"补班"集合由调用方决定是否反向合并）。
 * 旧 {@link #add(Date, int)} 行为不变（向后兼容，仅周末跳过）。
 */
public final class Workdays {

    private Workdays() {
    }

    /**
     * 工作日推算（仅跳过周末，R149 B1 之前的行为）。
     *
     * @param from     起始日期
     * @param workdays 要累加的工作日数（≥ 0）
     * @return 累加完成后返回
     */
    public static Date add(Date from, int workdays) {
        return add(from, workdays, Collections.emptySet());
    }

    /**
     * 工作日推算（周末 + 节假日集合一并跳过，R149 B1 新增）。
     *
     * <p>遍历逻辑：从 {@code from} 次日起逐日推进，遇到「周末 ∪ holidays」则跳过，
     * 其余日期累加 {@code workdays} 次后返回。{@code holidays} 为 {@code null} 或空 ⇒ 等价于纯周末跳过。
     *
     * <p><b>时区</b>：{@link Date} 不含时区信息，本方法以系统默认时区将 {@code Date} 拆解为
     * {@link LocalDate}，再与 {@code holidays}（{@code LocalDate}）按日期字面值比较。
     * 单企业私有部署单实例定位，无需跨国时区语义。
     *
     * @param from     起始日期
     * @param workdays 要累加的工作日数（≤ 0 直接返回 from）
     * @param holidays 节假日日期集合（{@code LocalDate} 形式），可空；为 null 视为空集合
     * @return 累加完成后返回（保留原始时分秒）
     */
    public static Date add(Date from, int workdays, Collection<LocalDate> holidays) {
        if (from == null) {
            throw new IllegalArgumentException("from 不能为空");
        }
        if (workdays <= 0) {
            return from;
        }
        Set<LocalDate> holidaySet = toHolidaySet(holidays);
        // 保留原始时分秒；Date → LocalDate 按系统默认时区拆解
        ZoneId zone = ZoneId.systemDefault();
        LocalDate startDate = from.toInstant().atZone(zone).toLocalDate();
        LocalDate cursor = startDate;
        int added = 0;
        while (added < workdays) {
            cursor = cursor.plusDays(1);
            // 周末优先 + 节假日集合一并跳过
            DayOfWeekLite dow = DayOfWeekLite.of(cursor);
            if (dow.isWeekend()) {
                continue;
            }
            if (holidaySet.contains(cursor)) {
                continue;
            }
            added++;
        }
        // 把"日期"换回"日期+时间"：以 from 当天的 HH:mm:ss 还原
        java.time.LocalTime timeOfDay = from.toInstant().atZone(zone).toLocalTime();
        return Date.from(cursor.atTime(timeOfDay).atZone(zone).toInstant());
    }

    /**
     * 把节假日集合标准化为 {@code Set<LocalDate>}（null-safe + 去重）。
     */
    private static Set<LocalDate> toHolidaySet(Collection<LocalDate> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return Collections.emptySet();
        }
        Set<LocalDate> set = new HashSet<>(holidays.size());
        for (LocalDate d : holidays) {
            if (d != null) {
                set.add(d);
            }
        }
        return set;
    }

    /**
     * 轻量星期枚举（避免引入 java.time.DayOfWeek 与系统默认时区耦合）。
     * 仅暴露 weekend 判断，单一职责。
     */
    private enum DayOfWeekLite {
        MON, TUE, WED, THU, FRI, SAT, SUN;

        static DayOfWeekLite of(LocalDate d) {
            // DayOfWeek.getValue() 1=MON..7=SUN
            return values()[d.getDayOfWeek().getValue() - 1];
        }

        boolean isWeekend() {
            return this == SAT || this == SUN;
        }
    }
}
