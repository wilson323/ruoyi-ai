package org.ruoyi.ipd.agent.store;

/**
 * LIKE 包含匹配的绑定参数。百分号和下划线按字面转义，调用方再写 ESCAPE。
 */
final class LikePatterns {

    private LikePatterns() {
    }

    /**
     * 把搜索词收成「包含」模式，不把用户输入拼进 SQL 结构。
     *
     * @param raw 已去空白的搜索词
     * @return 带首尾百分号、特殊字符已转义的模式
     */
    static String containsPattern(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
