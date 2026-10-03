#!/usr/bin/env bash
# C5 定向正反控：SDK actor 执行与同名记录访问器。
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMPD="$(mktemp -d "${TMPDIR:-/tmp}/as-call-test.XXXXXX")"
trap 'rm -rf "$TMPD"' EXIT
EXIT_CODE=0
check() {
  local name="$1" expected="$2"
  mkdir -p "$TMPD/$name"
  cat > "$TMPD/$name/Probe.java"
  HARNESS_SCAN_ROOTS="$TMPD/$name" HARNESS_SKIP_POM_GATES=1 bash "$SCRIPT_DIR/harness-contract-check.sh" > "$TMPD/$name.log" 2>&1
  local actual=$?
  if [ "$actual" -eq "$expected" ]; then printf 'PASS %s exit=%s\n' "$name" "$actual"; else cat "$TMPD/$name.log"; EXIT_CODE=1; fi
}
check accessor 0 <<'JAVA'
import io.agentscope.harness.agent.HarnessAgent;
class Probe { void validate(HarnessAgent.Builder builder, ParentReceipt receipt) { var call = receipt.call(); } }
JAVA
check sdk_without_context 1 <<'JAVA'
import io.agentscope.harness.agent.HarnessAgent;
class Probe { void execute(HarnessAgent agent, Msg msg) { agent.call(msg); } }
JAVA
check sdk_empty_call 1 <<'JAVA'
import io.agentscope.harness.agent.HarnessAgent;
class Probe { void execute(HarnessAgent agent) { agent.call(); } }
JAVA
check sdk_with_context 0 <<'JAVA'
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
class Probe { void execute(HarnessAgent agent, Msg msg, RuntimeContext context) { agent.call(msg, context); } }
JAVA
check comment_only 0 <<'JAVA'
import io.agentscope.harness.agent.HarnessAgent;
class Probe { // HarnessAgent agent; agent.call(msg);
void validate() { } }
JAVA
exit "$EXIT_CODE"
