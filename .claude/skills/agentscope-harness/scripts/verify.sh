#!/usr/bin/env bash
# verify.sh — agentscope-harness 主入口（DisCo 形态要求的动态断言）
#
#   正向： bash verify.sh              三段门禁全跑，期望 EXIT=0
#   负向： bash verify.sh --self-red   播种五组正反控并断言其结果，**同样期望 EXIT=0**
#          （它验证的是「门禁会红」这件事本身是否符合预期；退 != 0 意味着门禁自身失效
#           ——恒绿或恒红，此时禁止声称本 skill 已验证。2026-10-02 修正：此处旧文案
#           误写「期望 EXIT!=0」，与 :303 的 self-red: PASS 判据及 AGENTS.md 记录相矛盾，
#           会让后续会话把正常的 EXIT=0 误读成失败。）
#
# 为什么必须有 --self-red（.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md）：
#   从不红的门禁 = 假门禁。本 skill 的判据全是 grep 模式，写宽了恒绿、写窄了恒红，
#   两种都毫无价值，只能靠「播种已知违规 → 必须变红」+「播种已知合规 → 必须保持绿」
#   这一对正反控来自证。四组种子各自独立断言，便于归因到具体哪一段失效。
#
# 与模板的差异（有意为之）：模板示范的是 `sed -i.bak` 原地改 env-probe.sh 的 exit 行，
#   一旦脚本中途被中断就会留下变异文件污染事实源。这里改用**临时副本 + mktemp 种子**，
#   全程不改动本 skill 与仓库任何真实文件，trap 兜底清理。
#
# macOS bash 3.2 兼容：不用关联数组 / mapfile。
set -uo pipefail

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPTS="$SKILL_DIR/scripts"
TMPD=""
EXIT_CODE=0

cleanup() { [ -n "$TMPD" ] && [ -d "$TMPD" ] && rm -rf "$TMPD"; }
trap cleanup EXIT

hr()   { printf '%s\n' "------------------------------------------------------------"; }
ok()   { printf '  [ OK ] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; EXIT_CODE=1; }
info() { printf '  [INFO] %s\n' "$1"; }

MODE="positive"
case "${1:-}" in
  --self-red)   MODE="selfred" ;;
  ""|--verify)  MODE="positive" ;;
  -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
  *)            echo "用法: verify.sh [--self-red]" >&2; exit 2 ;;
esac

# ============================================================
# 正向模式
# ============================================================
if [ "$MODE" = "positive" ]; then
  echo "== [1/3] env-probe.sh（环境探测：环境缺失只 WARN，不阻断） =="
  bash "$SCRIPTS/env-probe.sh"; E1=$?
  hr
  echo "== [2/3] skill-lint.sh（本 skill 自身契约：知识自身错 → FAIL） =="
  bash "$SCRIPTS/skill-lint.sh"; E2=$?
  hr
  echo "== [3/3] harness-contract-check.sh（AgentScope 代码侧契约） =="
  bash "$SCRIPTS/harness-contract-check.sh"; E3=$?
  hr
  echo "== 汇总 =="
  printf '  env-probe=%s  skill-lint=%s  harness-contract=%s\n' "$E1" "$E2" "$E3"
  [ "$E1" -ne 0 ] && bad "env-probe 未通过（仓库根 / 基础工具缺失，属真问题非环境抖动）"
  [ "$E2" -ne 0 ] && bad "skill-lint 未通过（本 skill 自身不合规，禁止上岗）"
  [ "$E3" -ne 0 ] && bad "harness-contract-check 未通过（代码侧违反 AgentScope 契约）"
  echo
  if [ "$EXIT_CODE" -eq 0 ]; then
    echo "verify: PASS"
    echo "  下一步（自证能红，上岗前必跑一次）: bash $0 --self-red   # 同样期望 EXIT=0（断言五组正反控全符合预期）"
  else
    echo "verify: FAIL"
  fi
  exit $EXIT_CODE
fi

# ============================================================
# 负向模式：--self-red
# ============================================================
echo "== --self-red：播种已知样本，验证门禁真的会红 / 不会恒红 =="
TMPD="$(mktemp -d "${TMPDIR:-/tmp}/as-selfred.XXXXXX")"
mkdir -p "$TMPD/bad" "$TMPD/good" "$TMPD/work"
info "种子目录: ${TMPD}（trap 自动清理，不触碰仓库真实文件）"

# ---- 违规样本：C1 缺 workspace / C2 复合键散落 2 文件 / C4 缺身份 / C6 硬编码凭据 ----
cat > "$TMPD/bad/BadAssembly.java" <<'JAVA'
package seed.bad;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.harness.agent.HarnessAgent;

public class BadAssembly {
    HarnessAgent build() {
        return HarnessAgent.builder()
                .name("seed-agent")
                .model(ModelRegistry.resolve("minimax:MiniMax-M3"))
                .build();
    }
}
JAVA

cat > "$TMPD/bad/BadScope.java" <<'JAVA'
package seed.bad;

import io.agentscope.core.agent.RuntimeContext;

public class BadScope {
    public static String slotId(String projectId, String userId, String agentId, String sessionId) {
        return projectId + ":" + userId + ":" + agentId + ":" + sessionId;
    }

    public static RuntimeContext ctx() {
        return RuntimeContext.builder().build();
    }
}
JAVA

cat > "$TMPD/bad/BadController.java" <<'JAVA'
package seed.bad;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;

public class BadController {
    String chat(HarnessAgent agent, Msg msg, String projectId, String userId, String sessionId) {
        String slotId = projectId + ":" + userId + ":" + sessionId;
        RuntimeContext ctx = RuntimeContext.builder().userId(slotId).sessionId(sessionId).build();
        return agent.call(msg, ctx).block();
    }
}
JAVA

cat > "$TMPD/bad/BadDataSource.java" <<'JAVA'
package seed.bad;

public class BadDataSource {
    void configure(javax.sql.DataSource ds, com.mysql.cj.jdbc.MysqlDataSource mds) {
        mds.setUser("seed-not-a-real-credential");
        mds.setPassword("seed-not-a-real-credential");
    }
}
JAVA
# BadDataSource 需被识别为接线文件才进扫描集
printf 'import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;\n' >> "$TMPD/bad/BadDataSource.java"

# ---- 合规样本：单一收口 + fail-closed 三要素 + builder 齐全 + 调用带身份 ----
cat > "$TMPD/good/GoodScopeKey.java" <<'JAVA'
package seed.good;

import io.agentscope.core.agent.RuntimeContext;

public final class GoodScopeKey {
    public static final String ANONYMOUS_USER_SEGMENT = "__anon__";

    public record Scope(String userId, String sessionId) {
        public RuntimeContext toRuntimeContext() {
            return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        }

        public String slotId() {
            return userId + ":" + sessionId;
        }
    }

    public static Scope of(String projectId, String userId, String agentId, String sessionId) {
        validateSegment("projectId", projectId);
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        validateSegment("sessionId", sessionId);
        return new Scope("p" + projectId + ":u" + userId, "a" + agentId + ":s" + sessionId);
    }

    private static void validateSegment(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scope segment required: " + name);
        }
        if (value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("scope segment must not contain ':': " + name);
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("scope segment must not contain '..': " + name);
        }
    }
}
JAVA

cat > "$TMPD/good/GoodAssembly.java" <<'JAVA'
package seed.good;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

public class GoodAssembly {
    private final HarnessAgent agent;

    GoodAssembly(Path ws) {
        this.agent = HarnessAgent.builder()
                .name("seed-agent")
                .workspace(ws)
                .build();
    }

    String chat(Msg msg, String projectId, String userId, String agentId, String sessionId) {
        RuntimeContext ctx = GoodScopeKey.of(projectId, userId, agentId, sessionId).toRuntimeContext();
        return agent.call(msg, ctx).block();
    }
}
JAVA

# ---- SR1: 违规样本必须变红 ----
hr
echo "== [SR1] harness-contract-check 对违规样本必须 FAIL =="
HARNESS_SCAN_ROOTS="$TMPD/bad" HARNESS_SKIP_POM_GATES=1 \
  bash "$SCRIPTS/harness-contract-check.sh" > "$TMPD/sr1.log" 2>&1
SR1=$?
sed 's|^|  |' "$TMPD/sr1.log"
if [ "$SR1" -ne 0 ]; then
  ok "SR1 变红（exit=${SR1}）—— 门禁抓到了真违规"
  for c in 'C1' 'C2' 'C4' 'C6'; do
    grep -q "\[$c\]" "$TMPD/sr1.log" && info "$c 段有输出" || bad "$c 段无输出（判据可能失效）"
  done
  grep -q '\[FAIL\].*workspace' "$TMPD/sr1.log" && ok "C1 抓到缺 .workspace(" || bad "C1 未抓到缺 .workspace("
  grep -q '\[FAIL\].*散落' "$TMPD/sr1.log"       && ok "C2 抓到复合键散落"     || bad "C2 未抓到复合键散落"
else
  bad "SR1 未变红（exit=0）—— 门禁是假的，违规样本被放过"
fi

# ---- SR2: 合规样本必须保持绿（防恒红 / 假阳性） ----
hr
echo "== [SR2] harness-contract-check 对合规样本必须 PASS（反向对照） =="
HARNESS_SCAN_ROOTS="$TMPD/good" HARNESS_SKIP_POM_GATES=1 \
  bash "$SCRIPTS/harness-contract-check.sh" > "$TMPD/sr2.log" 2>&1
SR2=$?
sed 's|^|  |' "$TMPD/sr2.log"
if [ "$SR2" -eq 0 ]; then
  ok "SR2 保持绿（exit=0）—— 单一收口 + fail-closed 的合规写法不被误伤"
else
  bad "SR2 合规样本被判红（exit=${SR2}）—— 门禁恒红或存在假阳性，见上方 FAIL 行"
fi

# ---- SR3: skill-lint 变异副本必须变红 ----
hr
echo "== [SR3] skill-lint 对变异 skill 必须 FAIL =="
cp -R "$SKILL_DIR" "$TMPD/work/mutant"
# 变异 1：SKILL.md 撑破 100 行入口上限
i=0
while [ "$i" -lt 40 ]; do printf '填充行 %s（自证用，验证后随临时目录删除）\n' "$i" >> "$TMPD/work/mutant/SKILL.md"; i=$((i + 1)); done
# 变异 2：删掉一篇 reference 的「来源」槽
sed -i.srbak '/^## .*来源/d' "$TMPD/work/mutant/references/harness-patterns.md"
rm -f "$TMPD/work/mutant/references/harness-patterns.md.srbak"
bash "$TMPD/work/mutant/scripts/skill-lint.sh" > "$TMPD/sr3.log" 2>&1
SR3=$?
grep -E '\[FAIL\]|skill-lint:' "$TMPD/sr3.log" | sed 's|^|  |'
if [ "$SR3" -ne 0 ]; then
  ok "SR3 变红（exit=${SR3}）"
  grep -q '\[FAIL\].*超 100 行' "$TMPD/sr3.log" && ok "L1 抓到 SKILL.md 超行" || bad "L1 未抓到 SKILL.md 超行"
  grep -q '\[FAIL\].*缺槽'      "$TMPD/sr3.log" && ok "L4 抓到 reference 缺槽" || bad "L4 未抓到 reference 缺槽"
else
  bad "SR3 未变红（exit=0）—— skill-lint 是假的"
fi

# ---- SR4: verify.sh 必须传播子脚本失败 ----
hr
echo "== [SR4] verify.sh 必须传播 env-probe / skill-lint 的失败 =="
cp -R "$SKILL_DIR" "$TMPD/work/mutant2"
sed -i.srbak 's|^exit \$EXIT_CODE$|exit 1  # self-red override|' "$TMPD/work/mutant2/scripts/env-probe.sh"
rm -f "$TMPD/work/mutant2/scripts/env-probe.sh.srbak"
i=0
while [ "$i" -lt 40 ]; do printf '填充行 %s\n' "$i" >> "$TMPD/work/mutant2/SKILL.md"; i=$((i + 1)); done
# 扫描根必须显式收窄：变异副本推不出真实仓根，放任默认值会退化成扫 "/"
HARNESS_SCAN_ROOTS="$TMPD/work/mutant2" HARNESS_SKIP_POM_GATES=1 \
  bash "$TMPD/work/mutant2/scripts/verify.sh" > "$TMPD/sr4.log" 2>&1
SR4=$?
grep -E 'env-probe:|skill-lint:|verify:|\[FAIL\]' "$TMPD/sr4.log" | head -20 | sed 's|^|  |'
if [ "$SR4" -ne 0 ]; then
  ok "SR4 变红（exit=${SR4}）"
  grep -q 'env-probe: FAIL' "$TMPD/sr4.log" && ok "env-probe 失败被传播" || bad "env-probe 失败未被 verify.sh 传播"
  grep -q 'skill-lint: FAIL' "$TMPD/sr4.log" && ok "skill-lint 失败被传播" || bad "skill-lint 失败未被 verify.sh 传播"
  grep -q 'verify: FAIL' "$TMPD/sr4.log" && ok "verify.sh 汇总为 FAIL" || bad "verify.sh 未汇总为 FAIL"
else
  bad "SR4 未变红（exit=0）—— verify.sh 吞掉了子脚本失败"
fi

# ---- SR5: 空扫描根必须诚实 SKIP，既不得假绿 PASS 也不得假红 FAIL ----
# 回归锁：`grep -c . f || echo 0` 在空文件时双打印 "0\n0"，曾使 WIRED_N 失算绕过 SKIP
# 而报出 PASS（假绿），同时使 KEY_N 失算把最干净的仓库判成 C2 假红。本组永久钉死该缺陷。
hr
echo "== [SR5] 空扫描根必须输出诚实 SKIP（回归锁：count_lines 双打印缺陷） =="
mkdir -p "$TMPD/empty"
HARNESS_SCAN_ROOTS="$TMPD/empty" HARNESS_SKIP_POM_GATES=1 \
  bash "$SCRIPTS/harness-contract-check.sh" > "$TMPD/sr5.log" 2>&1
SR5=$?
sed 's|^|  |' "$TMPD/sr5.log"
if grep -q 'SKIP(no HarnessAgent usage)' "$TMPD/sr5.log"; then
  ok "SR5 输出诚实 SKIP（空对象不等于通过）"
else
  bad "SR5 未输出 SKIP —— 空扫描根被当成有对象处理"
fi
grep -q 'PASS（接线文件' "$TMPD/sr5.log"              && bad "SR5 出现假绿 PASS —— count_lines 双打印缺陷复发"
grep -q 'integer expression expected' "$TMPD/sr5.log" && bad "SR5 出现整数比较报错 —— count_lines 双打印缺陷复发"
grep -q '\[FAIL\]' "$TMPD/sr5.log"                     && bad "SR5 出现假红 FAIL —— 空扫描根不应命中任何违规判据"
if [ "$SR5" -eq 0 ]; then ok "SR5 退出码 0（诚实 SKIP 不阻断）"; else bad "SR5 退出码 ${SR5}，诚实 SKIP 应退 0"; fi

hr
echo "== --self-red 汇总 =="
printf '  SR1(违规→红)=%s  SR2(合规→绿)=%s  SR3(skill变异→红)=%s  SR4(传播→红)=%s  SR5(空对象→SKIP)=%s\n' \
  "$SR1" "$SR2" "$SR3" "$SR4" "$SR5"
echo
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "self-red: PASS —— 五组正反控全部符合预期，门禁不是假的"
else
  echo "self-red: FAIL —— 存在失效判据，禁止声称本 skill 已验证"
fi
exit $EXIT_CODE
