package org.ruoyi.ipd.agent.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Literal argv authorization for the official execute delegate; never starts a process. */
final class ProjectAgentOfficialCommandPolicy {
    // Original command contract from 29b64b97^ CommandToolConfig / ExecutablePolicy.
    private static final Set<String> PROGRAMS = Set.of(
        "git", "java", "javac", "mvn", "gradle", "node", "npm", "npx", "pnpm", "python", "python3", "rg");
    private static final Set<String> SHELLS = Set.of(
        "cmd", "command", "powershell", "pwsh", "sh", "bash", "dash", "zsh", "fish", "csh", "ksh", "wsl");
    private static final int MAX_ARGUMENTS = 256;
    private static final int MAX_ARGUMENT_CHARS = 16_384;
    private static final int MAX_PROGRAM_CHARS = 4_096;

    private ProjectAgentOfficialCommandPolicy() { }

    static Map<String, Object> canonicalInput(Map<String, Object> input) {
        if (input == null || !(input.get("command") instanceof String command))
            throw rejected("A single command is required");
        if (command.length() > MAX_PROGRAM_CHARS + MAX_ARGUMENTS * (MAX_ARGUMENT_CHARS + 3))
            throw rejected("Command exceeds the literal argument limit");
        List<String> words = words(command);
        String program = words.get(0);
        String normalized = normalized(program);
        if (program.length() > MAX_PROGRAM_CHARS || program.contains("/") || program.contains("\\")
                || !PROGRAMS.contains(normalized) || SHELLS.contains(normalized))
            throw rejected("Program is outside the authorized command contract");
        List<String> arguments = words.subList(1, words.size());
        if (!arguments.isEmpty() && normalized.equals(normalized(arguments.get(0))))
            throw rejected("Command repeats its executable as the first argument");
        rejectInline(normalized, arguments);
        var result = new LinkedHashMap<String, Object>(input);
        result.put("command", words.stream().map(ProjectAgentOfficialCommandPolicy::quote)
            .collect(java.util.stream.Collectors.joining(" ")));
        Object requestedTimeout = input.get("timeout");
        if (requestedTimeout != null && (!(requestedTimeout instanceof Number timeout)
                || !Double.isFinite(timeout.doubleValue()) || timeout.doubleValue() != timeout.longValue()
                || timeout.longValue() <= 0 || timeout.longValue() > 120))
            throw rejected("Command timeout must be whole seconds between 1 and 120");
        result.put("timeout", requestedTimeout == null ? 30 : ((Number) requestedTimeout).intValue());
        Object directory = input.get("working_directory");
        if (directory != null) {
            if (!(directory instanceof String path)) throw rejected("Working directory must be relative text");
            rejectControls(path);
            String pathText = path.strip();
            if (pathText.startsWith("/") || pathText.startsWith("~") || pathText.contains("..")
                    || pathText.contains("\\") || pathText.matches("^[A-Za-z]:.*"))
                throw rejected("Working directory must remain inside the workspace");
        }
        // Keep optional null values and all official inputs; only the command representation changes.
        return Collections.unmodifiableMap(result);
    }

    private static List<String> words(String command) {
        rejectControls(command);
        var result = new ArrayList<String>();
        var word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (quote == '\'') {
                if (current == '\'') quote = 0;
                else word.append(current);
            } else if (quote == '"') {
                if (current == '"') quote = 0;
                else if (current == '\\') {
                    if (++index >= command.length()) throw rejected("Command ends inside a quoted escape");
                    char escaped = command.charAt(index);
                    if (escaped != '"' && escaped != '\\' && escaped != '$' && escaped != '`') word.append('\\');
                    word.append(escaped);
                } else word.append(current);
            } else if (current == ' ') {
                if (started) { add(result, word); started = false; }
            } else if (current == '\'' || current == '"') {
                quote = current; started = true;
            } else if (current == '\\') {
                if (++index >= command.length()) throw rejected("Command ends inside an escape");
                word.append(command.charAt(index)); started = true;
            } else {
                if (";&|<>$`(){}[]*?!~#".indexOf(current) >= 0)
                    throw rejected("Command must use literal arguments without shell operators or expansion");
                word.append(current); started = true;
            }
            if (word.length() > MAX_ARGUMENT_CHARS) throw rejected("A literal argument exceeds its length limit");
        }
        if (quote != 0) throw rejected("Command contains an unclosed quote");
        if (started) add(result, word);
        if (result.isEmpty()) throw rejected("A single command is required");
        return List.copyOf(result);
    }

    private static void add(List<String> words, StringBuilder word) {
        if (word.toString().isBlank()) throw rejected("Literal arguments must not be blank");
        words.add(word.toString()); word.setLength(0);
        if (words.size() > MAX_ARGUMENTS + 1) throw rejected("Command exceeds its literal argument count");
    }

    private static void rejectInline(String program, List<String> arguments) {
        for (String argument : arguments) {
            if (program.equals("node") && (argument.equals("--eval") || argument.startsWith("--eval=")
                    || argument.equals("--print") || argument.startsWith("--print=")
                    || argument.startsWith("-e") || argument.startsWith("-p")
                        && !argument.startsWith("--")))
                throw rejected("Inline interpreter source is outside the authorized command contract");
            if ((program.equals("python") || program.equals("python3"))
                    && (argument.equals("-") || argument.startsWith("-c")))
                throw rejected("Inline interpreter source is outside the authorized command contract");
        }
    }

    private static void rejectControls(String value) {
        for (int index = 0; index < value.length(); index++)
            if (Character.isISOControl(value.charAt(index))) throw rejected("Command inputs must not contain control characters");
    }

    private static String normalized(String program) {
        String name = program.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".exe", ".cmd", ".bat", ".com"))
            if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        return name;
    }

    private static String quote(String argument) { return "'" + argument.replace("'", "'\\''") + "'"; }
    private static IllegalArgumentException rejected(String reason) { return new IllegalArgumentException(reason); }
}
