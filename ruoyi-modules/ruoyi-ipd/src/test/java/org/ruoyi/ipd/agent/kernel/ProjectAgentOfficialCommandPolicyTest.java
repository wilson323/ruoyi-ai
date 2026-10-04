package org.ruoyi.ipd.agent.kernel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialCommandPolicyTest {
    @Test void originalProgramsRemainAvailableWithBoundedTimeout() {
        for (String program : List.of("git", "java", "javac", "mvn", "gradle", "node", "npm", "npx", "pnpm", "python", "python3", "rg")) {
            var input = ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", program + " --version"));
            assertEquals("'" + program + "' '--version'", input.get("command"));
            assertEquals(30, input.get("timeout"));
        }
        assertEquals(120, ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "git status", "timeout", 120)).get("timeout"));
        for (Object timeout : List.of(0, -1, 121, 1.5, "30"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(
                Map.of("command", "git status", "timeout", timeout)));
    }

    @Test void shellOperatorsAndExpansionsCannotReachOfficialShell() {
        for (String command : List.of("git status; printf injected", "git status && printf injected", "git status | rg x",
                "git status > output", "git status < input", "git status $(printf injected)", "git status `printf injected`",
                "git status $HOME", "git status *.java", "git status # comment", "git status\nprintf injected"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", command)), command);
    }

    @Test void shellAndUnlistedExecutablePathsRemainUnauthorized() {
        for (String command : List.of("sh -c 'printf injected'", "bash script.sh", "powershell -Command x", "curl https://example.com",
                "/bin/git status", "./git status", "git git status"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", command)), command);
    }

    @Test void inlineInterpretersCannotHideSourceInCombinedOptions() {
        for (String command : List.of("node -e 'source'", "node -p 'source'", "node --eval=source", "node -esource",
                "node --print=source", "python -c source", "python3 -csource", "python3 -"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", command)), command);
        assertEquals("'node' '--check' 'file.js'", ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "node --check file.js")).get("command"));
        assertEquals("'python3' 'script.py'", ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "python3 script.py")).get("command"));
    }

    @Test void quotedLiteralMetacharactersRemainDataUnderRealPosixParsing() throws Exception {
        String command = "git status 'file; printf injected' \"$(printf expanded)\" 'it'\\''s' \"space value\" escaped\\;literal";
        String canonical = (String) ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", command)).get("command");
        // Parse as positional arguments only; this probe never invokes the requested program.
        var process = new ProcessBuilder("/bin/sh", "-c", "set -- " + canonical + "; printf '%s\\n' \"$@\"")
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(List.of("git", "status", "file; printf injected", "$(printf expanded)", "it's", "space value", "escaped;literal"),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void malformedQuotesEmptyArgumentsAndArgumentLimitsFailClosed() {
        for (String command : List.of(" ", "git 'unclosed", "git \"unclosed", "git trailing\\", "git ''", "git \"   \"", "git arg\u0000value"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", command)));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "git " + "x".repeat(16_385))));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "git " + "x ".repeat(257))));
        assertDoesNotThrow(() -> ProjectAgentOfficialCommandPolicy.canonicalInput(Map.of("command", "git " + "x ".repeat(256))));
    }

    @Test void workingDirectoryStaysRelativeAndOfficialInputsArePreserved() {
        var input = Map.<String, Object>of("command", "git status", "working_directory", "src", "timeout", 10);
        var canonical = ProjectAgentOfficialCommandPolicy.canonicalInput(input);
        assertEquals("src", canonical.get("working_directory")); assertEquals(10, canonical.get("timeout"));
        assertEquals("git status", input.get("command"));
        for (String path : List.of("/etc", "../outside", "C:\\outside", "~", "src\nother"))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOfficialCommandPolicy.canonicalInput(
                Map.of("command", "git status", "working_directory", path)));
    }
}
