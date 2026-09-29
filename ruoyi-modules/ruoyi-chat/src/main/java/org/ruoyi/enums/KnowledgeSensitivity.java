package org.ruoyi.enums;

import org.ruoyi.common.core.utils.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库敏感级枚举（PUBLIC / INTERNAL / SECRET）。
 * <p>
 * 设计权威：docs/ipd-系统说明/知识库结构与属性最佳实践-20260928.md §8.1/§8.2。
 * <ul>
 *   <li>过滤语义（检索过滤的前提）：「敏感级 ≤ 上限」一律以<b>允许值集合</b>表达——
 *       {@link #allowedValuesUpTo(KnowledgeSensitivity)} 是唯一权威展开（PUBLIC→[PUBLIC]、
 *       INTERNAL→[PUBLIC,INTERNAL]、SECRET→全部三值）；Weaviate 侧 ContainsAny + 值数组、
 *       MySQL 侧 {@code sensitivity IN (...)} 消费。禁止回到字符串序比较：
 *       字典序 INTERNAL &lt; PUBLIC &lt; SECRET 与敏感级升序 PUBLIC &lt; INTERNAL &lt; SECRET
 *       不一致（'I'&lt;'P'&lt;'S'），序比较曾导致 cap=PUBLIC 命中 INTERNAL 库（越权放大）、
 *       cap=INTERNAL 丢 PUBLIC 库（Validator 证伪的 P0）。新增敏感级取值只改本枚举的
 *       allowedValuesUpTo 分档，消费端零改动。</li>
 *   <li>SECRET 纪律：任何自动规则（派生、默认值、迁移 UPDATE）不得产出 SECRET，
 *       升密仅人审显式改（§8.1 规则 1）。</li>
 *   <li>上限权威源在 IPD 侧（§8.2），ruoyi-chat 不内嵌角色→上限表。</li>
 * </ul>
 *
 * @author ruoyi
 * @date 2026-09-28
 */
public enum KnowledgeSensitivity {

    /**
     * 公开（等价旧 share=1）
     */
    PUBLIC,

    /**
     * 内部（等价旧 share=0；Part A 列默认值）
     */
    INTERNAL,

    /**
     * 机密（仅人审显式设置）
     */
    SECRET;

    /**
     * 全部合法取值（校验/白名单用），顺序为枚举声明序而非敏感级序。
     */
    public static final List<String> NAMES = Arrays.stream(values())
        .map(Enum::name).toList();

    /**
     * 宽松解析：空白返回 null（=不设置），忽略首尾空白与大小写；
     * 非法取值返回 null 由调用方决定拒绝语义（本枚举不抛错，保持纯函数）。
     */
    public static KnowledgeSensitivity parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (KnowledgeSensitivity level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 「敏感级 ≤ 上限」的允许值集合（升序）：PUBLIC→[PUBLIC]、INTERNAL→[PUBLIC,INTERNAL]、
     * SECRET→全部三值。分档显式写死而不依赖枚举声明序/字典序——重排枚举声明不改变本语义。
     *
     * @param max 上限档；不得为 null（null 语义=不启用闸门，由调用方先行判断）
     * @return 不可变的允许值集合
     */
    public static List<KnowledgeSensitivity> allowedValuesUpTo(KnowledgeSensitivity max) {
        return switch (max) {
            case PUBLIC -> List.of(PUBLIC);
            case INTERNAL -> List.of(PUBLIC, INTERNAL);
            case SECRET -> List.of(PUBLIC, INTERNAL, SECRET);
        };
    }

    /**
     * {@link #allowedValuesUpTo(KnowledgeSensitivity)} 的 String 名形态便捷重载
     * （消费端拼 MySQL IN 集合 / Weaviate ContainsAny 值数组用，单源防双轨展开）。
     *
     * @param rawMax 上限档名（null/空白/非法值一律快速失败——上游唯一写入口
     *               {@code QueryVectorBo#applyBackendAccessFilters} 已做枚举校验，
     *               到达本方法的非空值必然合法，失败即编程错误，宁可炸不可放行）
     * @return 不可变的允许值名集合
     * @throws IllegalArgumentException null/空白/非法取值
     */
    public static List<String> allowedNamesUpTo(String rawMax) {
        KnowledgeSensitivity max = parse(rawMax);
        if (max == null) {
            throw new IllegalArgumentException("非法敏感级上限（仅 PUBLIC/INTERNAL/SECRET）: " + rawMax);
        }
        return allowedValuesUpTo(max).stream().map(Enum::name).toList();
    }
}
