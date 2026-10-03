package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agui.model.AguiTool;
import java.util.*;

/** Server output roles; prose and model metadata never establish a document receipt. */
public final class ProjectAgentOutputContract {
    public static final int VERSION = 1;
    public static final String CLARIFICATION_TOOL = "request_clarification";
    public static final String STEP = "OUTPUT_TYPE";
    public enum Kind { ANSWER, DOCUMENT, CLARIFICATION }
    private ProjectAgentOutputContract() { }
    public static AguiTool clarificationTool() {
        var option = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"),
            "label", Map.of("type", "string")), "required", List.of("id", "label"), "additionalProperties", false);
        var question = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"),
            "prompt", Map.of("type", "string"), "options", Map.of("type", "array", "items", option)),
            "required", List.of("id", "prompt", "options"), "additionalProperties", false);
        return new AguiTool(CLARIFICATION_TOOL, "Ask the user when facts or scope require clarification. This is a question, never permission approval.",
            Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "enum", List.of("CLARIFICATION")),
                "questions", Map.of("type", "array", "items", question)), "required", List.of("kind", "questions"), "additionalProperties", false));
    }
    public static Map<String,Object> responseSchema(Map<String,Object> input) {
        if (input == null || !input.keySet().equals(Set.of("kind", "questions")) || !"CLARIFICATION".equals(input.get("kind"))
            || !(input.get("questions") instanceof List<?> questions) || questions.isEmpty() || questions.size() > 8)
            throw new IllegalArgumentException("Invalid clarification questions");
        var properties = new LinkedHashMap<String,Object>();
        for (var raw : questions) {
            if (!(raw instanceof Map<?,?> question) || !question.keySet().equals(Set.of("id", "prompt", "options"))) throw new IllegalArgumentException("Invalid question");
            String id = id(question.get("id")); String prompt = text(question.get("prompt"));
            if (!(question.get("options") instanceof List<?> options) || options.size() == 1 || options.size() > 12) throw new IllegalArgumentException("Invalid options");
            var choices = new ArrayList<String>();
            for (var rawOption : options) {
                if (!(rawOption instanceof Map<?,?> option) || !option.keySet().equals(Set.of("id", "label"))) throw new IllegalArgumentException("Invalid option");
                String choice = id(option.get("id")); text(option.get("label"));
                if (choices.contains(choice)) throw new IllegalArgumentException("Duplicate option"); choices.add(choice);
            }
            Map<String,Object> field = choices.isEmpty()
                ? Map.of("type", "string", "title", prompt, "minLength", 1, "maxLength", 4000)
                : Map.of("type", "string", "title", prompt, "enum", List.copyOf(choices));
            if (properties.putIfAbsent(id, field) != null) throw new IllegalArgumentException("Duplicate question");
        }
        return Map.of("type", "object", "properties", Map.copyOf(properties), "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    public static void validateAnswers(Map<String,Object> input, Object payload) {
        var schema = responseSchema(input);
        var properties = (Map<?,?>)schema.get("properties");
        if (!(payload instanceof Map<?,?> answers) || !answers.keySet().equals(properties.keySet()))
            throw new IllegalArgumentException("Clarification answers must match original questions");
        for (var key : properties.keySet()) {
            var field = (Map<?,?>)properties.get(key);
            if (!(answers.get(key) instanceof String selected)) throw new IllegalArgumentException("Clarification answer must be text");
            if (field.get("enum") instanceof List<?> choices) {
                if (!choices.contains(selected)) throw new IllegalArgumentException("Unknown clarification option identifier");
            } else if (selected.isBlank() || selected.length() > 4000) {
                throw new IllegalArgumentException("Clarification text must be nonblank and at most 4000 characters");
            }
        }
    }
    private static String id(Object value) {
        if (!(value instanceof String id) || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid stable identifier"); return id;
    }
    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("Invalid question text"); return text;
    }
    public static String prompt() {
        return "\nOutput contract: ordinary answers are ANSWER, never document drafts. For DOCUMENT, write the complete document into a file and use the existing deliver_artifact tool; the server validates the actual delivered file. A bound action requires DOCUMENT, a closing summary alone cannot complete it. If facts or scope need clarification, use request_clarification with kind CLARIFICATION and stable question/option ids, or options [] for a free text fact; do not merely print questions and finish. Clarification is not permission approval.\n";
    }
}
