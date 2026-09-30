package org.ruoyi.ipd.agent;

/**
 * 项目智能体（C02 W1）固定常量。
 *
 * <p>agentId 固定为 {@link #AGENT_ID}，与副驾 {@code ipd_copilot} 区分执行入口；
 * 开关 {@link #ENABLED_PROPERTY} 默认关闭（matchIfMissing=false），关闭时能力接口
 * 返回 available=false 与 {@link #REASON_DISABLED}，运行接口拒绝且零写入，不降级到副驾。
 */
public final class ProjectAgentConstants {

    /** 项目智能体固定 agentId（四维隔离键 agent 段 + 工作区目录名，单段标识）。 */
    public static final String AGENT_ID = "ipd_project_agent";

    /** 独立功能开关键。 */
    public static final String ENABLED_PROPERTY = "ipd.project-agent.enabled";

    /** 开关关闭时的对外原因（能力接口 unavailableReason / 运行接口拒绝文案）。 */
    public static final String REASON_DISABLED = "项目智能体未启用（ipd.project-agent.enabled=false）";

    /** 反馈目标：运行的助手回复（targetId = runId，W1 一次运行一条回复）。 */
    public static final String TARGET_RUN_MESSAGE = "RUN_MESSAGE";

    /** 反馈目标：产物版本（targetId = ipd_agent_artifact_version.id）。 */
    public static final String TARGET_ARTIFACT_VERSION = "ARTIFACT_VERSION";

    /** 产物版本状态：草稿。 */
    public static final String ARTIFACT_STATUS_DRAFT = "DRAFT";

    /** 产物版本状态：已应用。 */
    public static final String ARTIFACT_STATUS_APPLIED = "APPLIED";

    /** apply 后索引未就绪时的真实状态（禁止伪造 READY）。 */
    public static final String INDEX_STATUS_NOT_INDEXED = "NOT_INDEXED";

    /** 用户输入上限（字符）。 */
    public static final int MESSAGE_MAX_CHARS = 4000;

    /** 幂等键上限（字符）。 */
    public static final int IDEMPOTENCY_KEY_MAX_CHARS = 64;

    /** 反馈原因上限（字符）。 */
    public static final int FEEDBACK_REASON_MAX_CHARS = 500;

    /** 事件分页上限。 */
    public static final int EVENTS_PAGE_LIMIT = 200;

    /** TEXT_DELTA 合并落库阈值（字符），避免逐 token 写库。 */
    public static final int TEXT_FLUSH_CHARS = 200;

    /**
     * 未满字符阈值时，距上次落库超过该间隔也写出一段。
     * 模型慢速吐字时界面仍能跟着增长，而不是一直空白到满 200 字或运行结束。
     */
    public static final long TEXT_FLUSH_INTERVAL_MS = 120L;

    private ProjectAgentConstants() {
    }
}
