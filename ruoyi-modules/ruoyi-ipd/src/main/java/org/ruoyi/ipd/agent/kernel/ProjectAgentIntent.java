package org.ruoyi.ipd.agent.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图理解：在模型生成之前判断本轮要不要需求澄清、要不要执行计划。
 *
 * <p>判断只看用户本轮原文、是否已绑定动作、已加载技能正文。不调用模型、不读盘。
 * 需要澄清时不展开计划。已绑定动作的步骤来自技能「## 步骤」有序列表，不用用户句子去补「核对×」。
 * 未绑定动作的计划确认以固定第一行起头，其后每一非空行就是一条步骤，不再切句。
 */
public final class ProjectAgentIntent {

    private static final Pattern VAGUE = Pattern.compile(
        "^(?:请|帮我|麻烦)?(?:看看|看一下|分析一下|做一下|处理一下|继续|你好|在吗)[。！!？?]*$");

    private static final Pattern STEP_LINE = Pattern.compile("^(\\d+)\\.\\s+(.+)$");

    private static final String[] VERBS = {
        "分析", "对比", "调研", "测算", "定义", "预测", "定位", "评估", "梳理", "检索", "整理", "输出",
    };

    private static final int MAX_SKILL_STEPS = 8;
    private static final int MAX_FREE_STEPS = 6;

    /** 未绑定动作的计划确认：消息第一行必须与此逐字相同。 */
    public static final String CONFIRMED_PLAN_HEADER = "按已确认计划执行";

    private ProjectAgentIntent() {
    }

    /**
     * 本轮意图结论。
     *
     * @param needsClarification 范围或方向还没定，应先提问
     * @param questions 要问用户的问题；不需要澄清时为空
     * @param needsPlan 应按步骤执行
     * @param steps 计划步骤；不需要计划时为空
     * @param summary 给界面的一句话结论
     */
    public record Decision(boolean needsClarification, List<String> questions, boolean needsPlan,
                           List<String> steps, String summary) {
    }

    /**
     * 判断本轮是否需要澄清和计划。
     *
     * @param message 用户原文，可空
     * @param actionCode 已绑定的动作编码，可空
     * @param skillBodies 已加载技能正文，顺序与运行快照一致；可空
     * @return 意图结论；列表不可变
     */
    public static Decision decide(String message, String actionCode, List<String> skillBodies) {
        String text = message == null ? "" : message.trim();
        String action = actionCode == null ? "" : actionCode.trim();
        if (action.isEmpty()) {
            List<String> confirmed = confirmedSteps(text);
            if (confirmed != null) {
                return planned(confirmed);
            }
        }
        if (text.isEmpty() || VAGUE.matcher(text).matches()) {
            return clarified(questionsForVague(action));
        }
        Choice choice = findChoice(text);
        if (choice != null) {
            return clarified(List.of(choice.question()));
        }
        List<String> skillSteps = firstSkillSteps(skillBodies);
        if (!action.isEmpty() && !skillSteps.isEmpty()) {
            return planned(skillSteps);
        }
        if (!action.isEmpty()) {
            return planned(List.of("按动作 " + action + " 与已加载技能执行，不另列计划。"));
        }
        List<String> clauses = verbClauses(text);
        if (clauses.size() >= 2) {
            return planned(clauses);
        }
        return direct();
    }

    /**
     * 把结论写进系统提示，要求模型按已判定的结果行动。
     *
     * @param decision 意图结论
     * @return 提示词片段
     */
    public static String prompt(Decision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append("意图判断已由服务端完成，必须遵守，不得改口：\n");
        if (decision.needsClarification()) {
            sb.append("需求澄清：需要。先只提出下列问题，不要调用工具，不要写交付物。\n");
            appendNumbered(sb, decision.questions());
        } else {
            sb.append("需求澄清：不需要。不要反问已经明确的范围。\n");
        }
        if (decision.needsPlan()) {
            sb.append("执行计划：需要。按下列顺序执行，不得增删改序，完成一步再进入下一步。\n");
            appendNumbered(sb, decision.steps());
        } else if (!decision.needsClarification()) {
            sb.append("执行计划：不需要。直接回答，不要先写一份计划。\n");
        } else {
            sb.append("执行计划：澄清完成前不要展开。\n");
        }
        return sb.toString();
    }

    private static Decision clarified(List<String> questions) {
        return new Decision(true, List.copyOf(questions), false, List.of(),
            "范围还没定，先澄清，不进入执行。");
    }

    private static Decision planned(List<String> steps) {
        return new Decision(false, List.of(), true, List.copyOf(steps), "范围已明确，按计划执行。");
    }

    private static Decision direct() {
        return new Decision(false, List.of(), false, List.of(), "可以直接回答，不单独列计划。");
    }

    private static List<String> questionsForVague(String action) {
        List<String> questions = new ArrayList<>();
        questions.add("请说明要交付的结果，以及范围限定在本项目的哪一块。");
        if (!action.isEmpty()) {
            questions.add("动作 " + action + " 已选定，请补充这次要覆盖的范围。");
        }
        return questions;
    }

    /**
     * 选择型「还是」：两侧各 2～12 字，且不是「还是要 / 还是继续 / 还是需要 / 还是先」。
     */
    private static Choice findChoice(String text) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf("还是", from);
            if (index < 0) {
                return null;
            }
            String after = text.substring(index + 2);
            if (startsStill(after)) {
                from = index + 2;
                continue;
            }
            String left = span(text, index, false);
            String right = span(after, after.length(), true);
            if (withinSide(left) && withinSide(right)) {
                return new Choice(left, right);
            }
            from = index + 2;
        }
        return null;
    }

    private static boolean startsStill(String after) {
        return after.startsWith("要") || after.startsWith("继续")
            || after.startsWith("需要") || after.startsWith("先");
    }

    private static boolean withinSide(String side) {
        return side.length() >= 2 && side.length() <= 12;
    }

    /** 从「还是」向一侧取连续文字，遇标点或空白即停。 */
    private static String span(String text, int edge, boolean forward) {
        if (forward) {
            int end = 0;
            while (end < edge && !isBreak(text.charAt(end))) {
                end++;
            }
            return text.substring(0, end);
        }
        int start = edge;
        while (start > 0 && !isBreak(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, edge);
    }

    private static boolean isBreak(char ch) {
        return Character.isWhitespace(ch) || "。；;、，,！!？?".indexOf(ch) >= 0;
    }

    private static List<String> firstSkillSteps(List<String> skillBodies) {
        if (skillBodies == null) {
            return List.of();
        }
        for (String body : skillBodies) {
            List<String> steps = orderedSteps(body);
            if (!steps.isEmpty()) {
                return steps;
            }
        }
        return List.of();
    }

    /**
     * 识别未绑定动作的计划确认。
     *
     * <p>第一行去掉首尾空白后必须恰好是 {@link #CONFIRMED_PLAN_HEADER}。其后每一非空行是一条步骤，
     * 空行丢掉，不按标点切句，也不用动词表补步。只有开头时返回空列表，调用方应继续等待。
     * 不是确认消息时返回 {@code null}。
     *
     * @param message 用户原文，可空；会先做整段 trim
     * @return 已确认步骤；不是确认消息时为 {@code null}
     */
    public static List<String> confirmedSteps(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\\R", -1);
        if (!CONFIRMED_PLAN_HEADER.equals(lines[0].trim())) {
            return null;
        }
        List<String> steps = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                steps.add(line);
            }
        }
        return List.copyOf(steps);
    }

    /**
     * 解析技能 Markdown「## 步骤」下的有序列表，最多 8 条，正文去掉编号后逐字保留。
     *
     * @param markdown 技能正文，可空
     * @return 步骤；没有该节时为空
     */
    static List<String> orderedSteps(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String[] lines = markdown.split("\\R");
        boolean inSection = false;
        List<String> steps = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (!inSection) {
                if ("## 步骤".equals(line)) {
                    inSection = true;
                }
                continue;
            }
            if (line.startsWith("#")) {
                break;
            }
            Matcher matcher = STEP_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            steps.add(matcher.group(2).trim());
            if (steps.size() == MAX_SKILL_STEPS) {
                break;
            }
        }
        return List.copyOf(steps);
    }

    private static List<String> verbClauses(String text) {
        List<String> steps = new ArrayList<>();
        for (String part : text.split("[。；;\\n、]")) {
            String clause = part.trim();
            if (clause.length() < 2 || !containsAny(clause, VERBS)) {
                continue;
            }
            steps.add(clause);
            if (steps.size() == MAX_FREE_STEPS) {
                break;
            }
        }
        return List.copyOf(steps);
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void appendNumbered(StringBuilder sb, List<String> lines) {
        int index = 1;
        for (String line : lines) {
            sb.append(index).append(". ").append(line).append('\n');
            index++;
        }
    }

    private record Choice(String left, String right) {
        String question() {
            return "这句话里有未选定的方向：「" + left + "」还是「" + right + "」。请指定其中一个后再执行。";
        }
    }
}
