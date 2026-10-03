#!/usr/bin/env python3
"""
agent-baseline-manifest.py 的自测（真 fixture，不是恒绿）

用法：
  python3 scripts/lib/baseline-manifest-test.py                    # 期望 EXIT=0
  BASELINE_FAIL_SEED=1 python3 scripts/lib/baseline-manifest-test.py  # 期望 EXIT=1

层级：
  1. 纯函数单测  —— 用真实样本做确定性断言（命令行解析 / javap 输出解析）
  2. 合成 jar    —— 纯标准库造一个小 fat jar，验嵌套 jar 列举与类定位
  3. 现役 jar E2E —— 若本机有在跑的服务，对真 jar 跑一次 CLI 并断言清单结构
                     （取不到运行实例时明确打印 SKIP，不伪装成通过）

FAIL_SEED 注入一条必然为假的断言：它必须失败才算自证能红；
若它没失败（测试本身坏了），同样 EXIT=1。
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAIN = HERE.parent / "agent-baseline-manifest.py"

FAIL_SEED = os.environ.get("BASELINE_FAIL_SEED") == "1"

TESTS = []
RESULTS = []


def test(fn):
    TESTS.append(fn)
    return fn


def child_env():
    """给 CLI 子进程用的环境：必须剥掉 BASELINE_FAIL_SEED。

    否则 FAIL_SEED 会经环境变量漏进被测子进程，让它无条件 EXIT=1，
    把 E2E 测试变成假红。
    """
    env = dict(os.environ)
    env.pop("BASELINE_FAIL_SEED", None)
    return env


def load_module():
    spec = importlib.util.spec_from_file_location("agent_baseline_manifest", MAIN)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


M = load_module()


# --------------------------------------------------------------------------
# 1. 纯函数单测
# --------------------------------------------------------------------------
# 真实样本：2026-10-03 实测 16039 进程的 ps 输出
REAL_CMDLINE = (
    "/usr/bin/java -Xmx2g -Duser.timezone=Asia/Shanghai -jar "
    "/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/backups/"
    "ruoyi-admin.baseline-pre-teardown-20261003-1532.jar "
    "--spring.profiles.active=ipd-local,dev --server.port=16039"
)
# 真实样本：javap -p 对 ProjectAgentRunOwnership（现役 jar）的输出
REAL_JAVAP = '''Compiled from "ProjectAgentRunOwnership.java"
public final class org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership {
  private static final org.slf4j.Logger log;
  private static final java.util.concurrent.atomic.AtomicLong OWNER_IDS;
  private final org.redisson.api.RedissonClient redisson;
  private final long checkTimeoutMillis;
  public org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership(org.redisson.api.RedissonClient);
  org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership(org.redisson.api.RedissonClient, long);
  private <T> T await(java.util.concurrent.CompletableFuture<T>, java.lang.String);
  private boolean heldBy(org.redisson.api.RFencedLock, long);
  private static boolean alreadyReleased(java.lang.Throwable);
  private void unlock(org.redisson.api.RFencedLock, long);
  public java.util.Optional<org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership$Lease> acquire(java.lang.Long);
  private static void lambda$acquire$1(org.redisson.api.RFencedLock, long, java.lang.Long, java.lang.Boolean, java.lang.Throwable);
  private static void lambda$acquire$0(java.lang.Long, java.lang.Void, java.lang.Throwable);
  static {};
}
'''


@test
def test_extract_jar_from_real_cmdline():
    got = M.extract_jar_from_cmdline(REAL_CMDLINE)
    want = ("/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/backups/"
            "ruoyi-admin.baseline-pre-teardown-20261003-1532.jar")
    assert got == want, f"取到 {got!r}，期望 {want!r}"


@test
def test_extract_jar_edge_cases():
    assert M.extract_jar_from_cmdline("") is None
    assert M.extract_jar_from_cmdline("/usr/bin/java -version") is None
    # 只认 -jar 后面那个词，不猜第一个 .jar 子串
    assert M.extract_jar_from_cmdline(
        "java -cp a.jar -jar /x/real.jar") == "/x/real.jar"
    assert M.extract_jar_from_cmdline('java -jar "/x/with space.jar"') == "/x/with space.jar"
    assert M.extract_jar_from_cmdline("java -jar=/x/eq.jar") == "/x/eq.jar"
    # -jar 在末尾、后面没有词
    assert M.extract_jar_from_cmdline("java -jar") is None


@test
def test_parse_javap_counts():
    p = M.parse_javap_output(REAL_JAVAP)
    assert p["class_decl"] == "org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership", \
        f"类名解析错: {p['class_decl']}"
    assert len(p["methods"]) == 10, f"方法数 {len(p['methods'])}，期望 10"
    assert len(p["fields"]) == 4, f"字段数 {len(p['fields'])}，期望 4"


@test
def test_parse_javap_members():
    p = M.parse_javap_output(REAL_JAVAP)
    assert any("acquire(" in m for m in p["methods"]), "缺 acquire 方法"
    assert any("alreadyReleased(" in m for m in p["methods"]), "缺 alreadyReleased 方法"
    # 静态初始化块必须当可执行体归到方法，不能落进字段
    assert "static {}" in p["methods"], "静态初始化块未归入方法"
    assert not any("static {}" == f for f in p["fields"]), "静态初始化块被误判成字段"
    # 泛型方法里的 <T> 不能被当成字段
    assert any(m.startswith("private <T> T await(") for m in p["methods"]), "泛型方法解析错"
    assert any("OWNER_IDS" in f for f in p["fields"]), "字段 OWNER_IDS 缺失"


@test
def test_parse_javap_negative_control():
    """空输出必须解析出空成员——防止解析器把任何输入都当成功。"""
    p = M.parse_javap_output("")
    assert p["methods"] == [] and p["fields"] == [] and p["class_decl"] is None


@test
def test_parse_javap_nested_class_isolated():
    """嵌套类型的成员不能混进外层成员列表。"""
    text = """public class Outer {
  private int outerField;
  public static class Inner {
    private int innerField;
    public void innerMethod();
  }
  public void outerMethod();
}
"""
    p = M.parse_javap_output(text)
    assert len(p["methods"]) == 1 and "outerMethod" in p["methods"][0], p["methods"]
    assert len(p["fields"]) == 1 and "outerField" in p["fields"][0], p["fields"]
    assert len(p["nested_classes"]) == 1, p["nested_classes"]
    assert not any("innerMethod" in m for m in p["methods"]), "嵌套方法泄漏到外层"


# --------------------------------------------------------------------------
# 2. 合成 fat jar
# --------------------------------------------------------------------------
def build_synthetic_fat_jar(path: Path):
    """纯标准库造一个最小 Spring Boot fat jar 形态的 zip。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF",
                   "Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\n")
        z.writestr("BOOT-INF/classes/org/ruoyi/App.class", b"fake")

        # 嵌套 ruoyi-ipd jar，内含一个类条目
        inner = Path(tempfile.mkdtemp()) / "ruoyi-ipd-3.1.0.jar"
        with zipfile.ZipFile(inner, "w") as iz:
            iz.writestr("org/ruoyi/ipd/Demo.class", b"fake")
        entry = zipfile.ZipInfo("BOOT-INF/lib/ruoyi-ipd-3.1.0.jar",
                                date_time=(2026, 10, 3, 15, 31, 24))
        z.writestr(entry, inner.read_bytes())

        # 另一个非 ruoyi 的嵌套 jar
        other = Path(tempfile.mkdtemp()) / "spring-core-6.0.jar"
        with zipfile.ZipFile(other, "w") as oz:
            oz.writestr("org/springframework/Core.class", b"fake")
        e2 = zipfile.ZipInfo("BOOT-INF/lib/spring-core-6.0.jar",
                             date_time=(2026, 1, 1, 0, 0, 0))
        z.writestr(e2, other.read_bytes())
    return path


@test
def test_fat_jar_layout_and_inner_jars():
    with tempfile.TemporaryDirectory() as td:
        jar = Path(td) / "app.jar"
        build_synthetic_fat_jar(jar)

        layout = M.fat_jar_layout(str(jar))
        assert layout["is_fat_jar"] is True, layout
        assert layout["classes_prefix"] == "BOOT-INF/classes/", layout

        ruoyi = M.list_inner_jars(str(jar), only_ruoyi=True)
        assert len(ruoyi) == 1, f"ruoyi 嵌套 jar 数 {len(ruoyi)}，期望 1: {ruoyi}"
        assert ruoyi[0]["name"] == "ruoyi-ipd-3.1.0.jar", ruoyi
        assert ruoyi[0]["build_time"] == "2026-10-03T15:31:24", \
            f"构建时间解析错: {ruoyi[0]['build_time']}"

        alljars = M.list_inner_jars(str(jar), only_ruoyi=False)
        assert len(alljars) == 2, f"全部嵌套 jar 数 {len(alljars)}，期望 2"


@test
def test_resolve_class_in_synthetic_jar():
    with tempfile.TemporaryDirectory() as td:
        jar = Path(td) / "app.jar"
        build_synthetic_fat_jar(jar)
        layout = M.fat_jar_layout(str(jar))
        with zipfile.ZipFile(jar) as z:
            entries = sorted(n for n in z.namelist() if n.startswith("BOOT-INF/lib/"))

        cache, state = {}, {"scanned": 0, "skipped_large": []}
        cp, origin = M.resolve_class(str(jar), "org.ruoyi.ipd.Demo", td, layout,
                                     entries, cache, state)
        assert cp is not None, f"类未定位到 (origin={origin})"
        assert origin == "BOOT-INF/lib/ruoyi-ipd-3.1.0.jar", origin

        # BOOT-INF/classes 内的类走另一条分支
        cp2, origin2 = M.resolve_class(str(jar), "org.ruoyi.App", td, layout,
                                       entries, cache, state)
        assert cp2 is not None and origin2 == "BOOT-INF/classes", origin2

        # 反向对照：不存在的类必须返回 not-found
        cp3, origin3 = M.resolve_class(str(jar), "org.ruoyi.Nope", td, layout,
                                       entries, cache, state)
        assert cp3 is None and origin3 == "not-found", (cp3, origin3)


# --------------------------------------------------------------------------
# 3. 现役 jar E2E
# --------------------------------------------------------------------------
def find_live_pid():
    """找 16039 的监听进程（只读 lsof）；找不到返回 None。"""
    env_pid = os.environ.get("BASELINE_TEST_PID")
    if env_pid:
        return int(env_pid)
    r = subprocess.run(["lsof", "-nP", "-iTCP:16039", "-sTCP:LISTEN"],
                       capture_output=True, text=True, check=False)
    for line in r.stdout.splitlines()[1:]:
        cols = line.split()
        if len(cols) > 1 and cols[0] == "java":
            return int(cols[1])
    return None


LIVE_PID = find_live_pid()
LIVE_JAR = os.environ.get("BASELINE_TEST_JAR")


@test
def test_e2e_live_or_jar():
    if LIVE_PID is None and not LIVE_JAR:
        print("    SKIP: 未发现 16039 监听进程，也没有 BASELINE_TEST_JAR")
        return
    args = [sys.executable, str(MAIN)]
    if LIVE_PID is not None:
        args += ["--pid", str(LIVE_PID)]
    else:
        args += ["--jar", LIVE_JAR]
    args += ["--class", "org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership"]

    r = subprocess.run(args, capture_output=True, text=True, check=False,
                       env=child_env())
    assert r.returncode == 0, f"CLI EXIT={r.returncode}\nSTDERR={r.stderr[:800]}"
    m = json.loads(r.stdout)

    for key in ("pid", "cmdline", "port", "jar_path", "jar_mtime",
                "inner_jars", "classes", "git_head", "git_dirty"):
        assert key in m, f"清单缺字段 {key}"

    assert m["jar_path"] and os.path.isfile(m["jar_path"]), m["jar_path"]
    assert len(m["inner_jars"]) > 0, "未列出任何 ruoyi-* 嵌套 jar"

    cls = m["classes"][0]
    assert cls["found"] is True, f"类未解析: {cls}"
    assert cls["method_count"] > 0, "方法数为 0——javap 没真跑或解析器失效"
    assert any("acquire(" in x for x in cls["methods"]), \
        "该类应含 acquire 方法；缺说明 javap 取到的不是同一份 class"

    if LIVE_PID is not None:
        assert m["port"] == 16039, f"端口取错: {m['port']}"
        assert m["pid"] == LIVE_PID, m["pid"]


@test
def test_e2e_negative_control_missing_class():
    """反向对照：不存在的类必须让 CLI EXIT=1 并报错，而不是静默通过。"""
    if LIVE_PID is None and not LIVE_JAR:
        print("    SKIP: 无运行实例")
        return
    args = [sys.executable, str(MAIN)]
    args += ["--pid", str(LIVE_PID)] if LIVE_PID is not None else ["--jar", LIVE_JAR]
    args += ["--class", "org.ruoyi.__does_not_exist__.Nope"]
    r = subprocess.run(args, capture_output=True, text=True, check=False,
                       env=child_env())
    assert r.returncode == 1, f"不存在的类未导致 EXIT=1，实际 {r.returncode}"
    assert "类未解析" in r.stderr or "类未解析" in r.stdout, r.stderr[:300]


# --------------------------------------------------------------------------
# FAIL_SEED 注入项（必须失败）
# --------------------------------------------------------------------------
if FAIL_SEED:
    @test
    def test_FAIL_SEED_injected_must_fail():
        p = M.parse_javap_output(REAL_JAVAP)
        # 探针：故意断言一个不存在的方法。此断言「失败」才是正确行为——
        # 下方失败信息正是它按预期失败时打印出来的。
        assert any("methodThatDoesNotExist" in m for m in p["methods"]), \
            "FAIL_SEED 探针按预期失败（注入生效，测试器能变红）"


def run() -> int:
    print(f"== baseline-manifest 自测 ==  (FAIL_SEED={FAIL_SEED})")
    failures = []
    for fn in TESTS:
        name = fn.__name__
        try:
            fn()
        except AssertionError as e:
            failures.append((name, f"断言失败: {e}"))
            print(f"  FAIL  {name}: {e}")
        except Exception as e:  # noqa: BLE001
            failures.append((name, f"{type(e).__name__}: {e}"))
            print(f"  ERROR {name}: {type(e).__name__}: {e}")
        else:
            print(f"  ok    {name}")

    total = len(TESTS)
    print(f"\n{total - len(failures)}/{total} 通过")

    if FAIL_SEED:
        poisoned = "test_FAIL_SEED_injected_must_fail"
        failed_names = [n for n, _ in failures]
        unexpected = [n for n in failed_names if n != poisoned]
        if poisoned not in failed_names:
            print("FAIL_SEED: 探针竟然通过了——测试器坏了，抓不出已知为假的断言 -> EXIT=1")
        elif not unexpected:
            print("FAIL_SEED: 探针如期失败，其余全绿 -> 自证能红成立, EXIT=1")
        else:
            print(f"FAIL_SEED: 探针如期失败，但另有 {len(unexpected)} 项真实失败："
                  f"{unexpected} -> EXIT=1")
        return 1

    if failures:
        print("RESULT: FAIL")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(run())
