package org.ruoyi.workflow.workflow.node.switcher;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class SwitcherCase {

    private String uuid;
    private String operator;
    private List<Condition> conditions;
    /**
     * 可选：原始 SpEL 布尔表达式（沙箱求值）。非空时优先于 conditions 求值；
     * 不配置则走 conditions 旧配置，存量流程零迁移。
     */
    private String spel;
    @JsonProperty("target_node_uuid")
    private String targetNodeUuid;

    @Data
    public static class Condition {
        private String uuid;
        @JsonProperty("node_uuid")
        private String nodeUuid;
        @JsonProperty("node_param_name")
        private String nodeParamName;
        private String operator;
        private String value;
    }
}
