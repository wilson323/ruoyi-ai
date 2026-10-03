#!/usr/bin/env python3
"""
源码 → class → JAR → 进程 追溯工具（AgentScope 审计 A01）

动机：本轮审计已两次因「量尺没验过」差点得出反结论。本脚本先造量尺：
给一个正在跑的 PID 或一个 jar 路径，输出可一眼看穿的清单——

  线上跑的这个进程，其 class 到底来自哪次构建、含不含某个方法。

能力：
  --pid  从 `ps -p` 读命令行取 jar 路径（-jar 后的那个），用 `lsof` 取监听端口；
  --jar  直接指定 fat jar；
  解开 Spring Boot fat jar 的 BOOT-INF/lib/ruoyi-*.jar 与 BOOT-INF/classes，
  对 --class 指定的关键类用 `javap -p -classpath <嵌套jar> <类>` 提取方法/字段签名。

输出 JSON（stdout）：
  {pid, cmdline, port, jar_path, jar_mtime, inner_jars[{name,build_time}],
   classes[{name, methods[], fields[]}], git_head, git_dirty, ...}

退出码：
  0 = 清单完整
  1 = 校验失败（或 BASELINE_FAIL_SEED=1 自证能红）
  2 = 用法错误 / 取不到 jar

只读：只调用 ps / lsof / javap / git，不启停任何进程，不写任何文件（除临时目录）。
纯标准库，无第三方依赖。

自证能红：
  BASELINE_FAIL_SEED=1 python3 scripts/agent-baseline-manifest.py --jar <path>
  期望 EXIT=1 —— 注入一条必然违反的断言，并要求校验器把它抓出来；
  若校验器抓不出来，说明校验恒绿，同样 EXIT=1 并报「FAIL_SEED 未被检出」。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime

# 归档 / 备份目录：出现即意味着「跑的不是构建产物」，必须显式告警
ARCHIVE_HINTS = (".codex/", ".harness/", "/backups/", ".codex\\", ".harness\\")

# 解压嵌套 jar 做类名检索时的单文件上限，避免把 300MB 的巨型依赖整个读进内存
MAX_JAR_BYTES_FOR_SCAN = 64 * 1024 * 1024

_CLASS_DECL_RE = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_$][\w$.]*)")
_NESTED_DECL_RE = re.compile(r"\b(class|interface|enum|record)\s+[A-Za-z_$][\w$.]*")
_STATIC_INIT_RE = re.compile(r"^static\s*\{\s*\}$")


# --------------------------------------------------------------------------
# 进程 / 端口
# --------------------------------------------------------------------------
def read_cmdline(pid: int) -> str:
    """读进程命令行全长（macOS/Linux 通用，ps -o command=）。"""
    out = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        capture_output=True, text=True, check=False,
    )
    return out.stdout.strip()


def tokenize_cmdline(cmdline: str):
    """按 shell 规则切词，保留带引号/带空格的路径。

    朴素 .split() 会把 `-jar "/x/with space.jar"` 切成 `"/x/with` + `space.jar"`
    —— 得到一个错误的 jar 路径却无人察觉。本工具存在的意义就是防止这种
    「量尺形状不对」，所以这里必须按引号切。
    """
    try:
        return shlex.split(cmdline, posix=True)
    except ValueError:
        return cmdline.split()


def extract_jar_from_cmdline(cmdline: str):
    """从命令行里取 `-jar <path>` 的路径；没有则返回 None。

    不猜、不取第一个 .jar 子串——只认 -jar 后紧跟的那个词。
    """
    if not cmdline:
        return None
    tokens = tokenize_cmdline(cmdline)
    for i, tok in enumerate(tokens):
        if tok == "-jar" and i + 1 < len(tokens):
            return tokens[i + 1]
        if tok.startswith("-jar="):
            return tok[len("-jar="):]
    return None


def read_listen_ports(pid: int):
    """读进程正在 LISTEN 的端口列表（lsof 只读）。"""
    out = subprocess.run(
        ["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN"],
        capture_output=True, text=True, check=False,
    )
    ports = []
    for line in out.stdout.splitlines()[1:]:
        cols = line.split()
        if not cols:
            continue
        name = cols[-2] if len(cols) >= 2 else ""
        m = re.search(r":(\d+)$", name)
        if m:
            p = int(m.group(1))
            if p not in ports:
                ports.append(p)
    return sorted(ports)


# --------------------------------------------------------------------------
# JAR 结构
# --------------------------------------------------------------------------
def _fmt_zip_time(t) -> str:
    return "%04d-%02d-%02dT%02d:%02d:%02d" % tuple(t)


def list_inner_jars(jar_path: str, only_ruoyi: bool = True):
    """列出 fat jar 内 BOOT-INF/lib 下的嵌套 jar 及其构建时间（zip 条目本地时间）。"""
    result = []
    with zipfile.ZipFile(jar_path) as z:
        for info in z.infolist():
            name = info.filename
            if not name.startswith("BOOT-INF/lib/") or not name.endswith(".jar"):
                continue
            base = name[len("BOOT-INF/lib/"):]
            if "/" in base:
                continue
            if only_ruoyi and not base.startswith("ruoyi-"):
                continue
            result.append({
                "name": base,
                "entry": name,
                "size_bytes": info.file_size,
                "build_time": _fmt_zip_time(info.date_time),
            })
    result.sort(key=lambda d: d["name"])
    return result


def fat_jar_layout(jar_path: str) -> dict:
    """判断是不是 Spring Boot fat jar，以及 classes/ 前缀。"""
    with zipfile.ZipFile(jar_path) as z:
        names = z.namelist()
        has_lib = any(n.startswith("BOOT-INF/lib/") for n in names)
        has_classes = any(n.startswith("BOOT-INF/classes/") for n in names)
        return {
            "is_fat_jar": has_lib or has_classes,
            "classes_prefix": "BOOT-INF/classes/" if has_classes else "",
            "entry_count": len(names),
        }


def class_relpath(fqn: str) -> str:
    return fqn.replace(".", "/") + ".class"


def extract_nested(jar_path: str, entry: str, dest_dir: str) -> str:
    """把嵌套 jar 解到一个临时文件，返回其路径（javap 需要真实文件）。"""
    with zipfile.ZipFile(jar_path) as z:
        z.extract(entry, dest_dir)
    return os.path.join(dest_dir, entry)


def resolve_class(jar_path: str, fqn: str, dest_dir: str, layout: dict,
                  ordered_entries, extract_cache: dict, scan_state: dict):
    """定位类所在的可作为 classpath 的 jar/目录，返回 (classpath, 说明)。

    Spring Boot fat jar 里的类由 JarLauncher 从嵌套 jar 加载，外层 jar 本身
    不能直接当 classpath 用，必须先解出来。
    """
    rel = class_relpath(fqn)

    # 1) BOOT-INF/classes/ 内（应用自身类）
    if layout["classes_prefix"]:
        with zipfile.ZipFile(jar_path) as z:
            if layout["classes_prefix"] + rel in z.namelist():
                if "__classes__" not in extract_cache:
                    z.extractall(dest_dir, [n for n in z.namelist()
                                            if n.startswith(layout["classes_prefix"])])
                    extract_cache["__classes__"] = os.path.join(
                        dest_dir, layout["classes_prefix"].rstrip("/"))
                return extract_cache["__classes__"], "BOOT-INF/classes"

    # 2) 非 fat jar：jar 自己就是 classpath
    if not layout["is_fat_jar"]:
        with zipfile.ZipFile(jar_path) as z:
            if rel in z.namelist():
                return jar_path, "jar-self"
        return None, "not-found"

    # 3) 逐个嵌套 jar 找（优先 ruoyi-*，再其余），找到即停
    for entry in ordered_entries:
        scan_state["scanned"] += 1
        if entry in extract_cache:
            path = extract_cache[entry]
        else:
            with zipfile.ZipFile(jar_path) as z:
                try:
                    info = z.getinfo(entry)
                except KeyError:
                    continue
                if info.file_size > MAX_JAR_BYTES_FOR_SCAN:
                    scan_state["skipped_large"].append(
                        {"entry": entry, "size_bytes": info.file_size})
                    continue
            path = extract_nested(jar_path, entry, dest_dir)
            extract_cache[entry] = path
        with zipfile.ZipFile(path) as nz:
            if rel in nz.namelist():
                return path, entry
    return None, "not-found"


# --------------------------------------------------------------------------
# javap 解析
# --------------------------------------------------------------------------
def parse_javap_output(text: str) -> dict:
    """把 `javap -p` 输出切成 {class_decl, methods[], fields[], nested_classes[]}。

    javap 每个成员一行；靠大括号深度区分「外层类的成员」与「嵌套类型体内的成员」，
    靠是否含 '(' 区分方法 / 字段。注解行、Compiled from 行不计入。
    """
    methods, fields, nested = [], [], []
    class_decl = None
    depth = 0
    started = False

    for raw in text.splitlines():
        line = raw.strip()
        if not started:
            m = _CLASS_DECL_RE.search(line)
            if m and line.endswith("{"):
                class_decl = m.group(2)
                started = True
                depth = 1
            continue

        if line.endswith("{"):
            if _NESTED_DECL_RE.search(line):
                nested.append(line.rstrip("{").strip())
            depth += 1
            continue

        if line == "}":
            depth -= 1
            if depth <= 0:
                break
            continue

        if depth == 1 and line.endswith(";"):
            member = line[:-1].strip()
            if not member:
                continue
            if _NESTED_DECL_RE.search(member):
                nested.append(member)
            elif _STATIC_INIT_RE.match(member):
                methods.append(member)          # 静态初始化块：可执行体，归方法
            elif "(" in member:
                methods.append(member)
            else:
                fields.append(member)

    return {
        "class_decl": class_decl,
        "methods": methods,
        "fields": fields,
        "nested_classes": nested,
    }


def find_javap() -> str:
    """定位 javap：JAVAP env > JAVA_HOME/bin > PATH。"""
    explicit = os.environ.get("JAVAP")
    if explicit and os.path.isfile(explicit):
        return explicit
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = os.path.join(java_home, "bin", "javap")
        if os.path.isfile(cand):
            return cand
    found = shutil.which("javap")
    return found or "javap"


def javap_class(javap: str, classpath: str, fqn: str) -> dict:
    out = subprocess.run(
        [javap, "-p", "-classpath", classpath, fqn],
        capture_output=True, text=True, check=False,
    )
    if out.returncode != 0:
        return {
            "name": fqn, "found": False,
            "error": (out.stderr or out.stdout).strip()[:500],
        }
    parsed = parse_javap_output(out.stdout)
    return {
        "name": fqn,
        "found": True,
        "class_decl": parsed["class_decl"],
        "methods": parsed["methods"],
        "method_count": len(parsed["methods"]),
        "fields": parsed["fields"],
        "field_count": len(parsed["fields"]),
        "nested_classes": parsed["nested_classes"],
    }


# --------------------------------------------------------------------------
# git
# --------------------------------------------------------------------------
def git_facts(repo: str) -> dict:
    def run(*args):
        try:
            r = subprocess.run(["git", "-C", repo, *args],
                               capture_output=True, text=True, check=False)
            return r.stdout.strip() if r.returncode == 0 else None
        except FileNotFoundError:
            return None

    head = run("rev-parse", "HEAD")
    if head is None:
        return {"git_head": None, "git_dirty": None, "git_head_time": None,
                "git_head_subject": None, "git_branch": None, "git_status_count": None}
    status = run("status", "--porcelain") or ""
    changed = [ln for ln in status.splitlines() if ln.strip()]
    ct = run("log", "-1", "--format=%cI")
    subject = run("log", "-1", "--format=%s")
    branch = run("rev-parse", "--abbrev-ref", "HEAD")
    return {
        "git_head": head,
        "git_dirty": len(changed) > 0,
        "git_status_count": len(changed),
        "git_head_time": ct,
        "git_head_subject": subject,
        "git_branch": branch,
    }


# --------------------------------------------------------------------------
# 清单组装
# --------------------------------------------------------------------------
def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(pid, jar_path, classes, repo, do_sha256=True) -> dict:
    m = {
        "pid": pid,
        "cmdline": None,
        "port": None,
        "ports": [],
        "jar_path": None,
        "jar_mtime": None,
        "inner_jars": [],
        "classes": [],
        "git_head": None,
        "git_dirty": None,
        "warnings": [],
    }

    if pid is not None:
        m["cmdline"] = read_cmdline(pid) or None
        m["ports"] = read_listen_ports(pid)
        m["port"] = m["ports"][0] if m["ports"] else None
        if not jar_path:
            jar_path = extract_jar_from_cmdline(m["cmdline"] or "")
        if not m["cmdline"]:
            m["warnings"].append(f"ps 未取到 PID {pid} 的命令行（进程不存在或权限不足）")
        elif not m["ports"]:
            m["warnings"].append(f"lsof 未取到 PID {pid} 的 LISTEN 端口")

    if not jar_path:
        m["warnings"].append("未能从命令行解析出 -jar 路径")
        m.update(git_facts(repo))
        return m

    jar_path = os.path.abspath(os.path.expanduser(jar_path))
    m["jar_path"] = jar_path

    if not os.path.isfile(jar_path):
        m["warnings"].append(f"jar 不存在: {jar_path}")
        m.update(git_facts(repo))
        return m

    st = os.stat(jar_path)
    m["jar_mtime"] = datetime.fromtimestamp(st.st_mtime).isoformat(timespec="seconds")
    m["jar_mtime_epoch"] = int(st.st_mtime)
    m["jar_size_bytes"] = st.st_size
    if do_sha256:
        m["jar_sha256"] = sha256_file(jar_path)

    # 归档 / 备份路径告警——本轮实测线上 16039 进程就跑在 .codex/ 下
    if any(h in jar_path for h in ARCHIVE_HINTS):
        m["warnings"].append(
            f"运行 jar 位于归档/备份路径，不是构建产物目录 target/：{jar_path}")

    m.update(git_facts(repo))

    try:
        layout = fat_jar_layout(jar_path)
    except zipfile.BadZipFile as e:
        m["warnings"].append(f"不是合法 jar/zip: {e}")
        return m

    m["is_fat_jar"] = layout["is_fat_jar"]
    m["jar_entry_count"] = layout["entry_count"]

    all_entries = []
    with zipfile.ZipFile(jar_path) as z:
        for n in z.namelist():
            if n.startswith("BOOT-INF/lib/") and n.endswith(".jar") and "/" not in n[len("BOOT-INF/lib/"):]:
                all_entries.append(n)
    all_entries.sort()

    m["inner_jar_total"] = len(all_entries)
    try:
        m["inner_jars"] = list_inner_jars(jar_path, only_ruoyi=True)
    except zipfile.BadZipFile as e:
        m["warnings"].append(f"读取嵌套 jar 失败: {e}")

    if not m["inner_jars"] and m["is_fat_jar"]:
        m["warnings"].append("fat jar 内未发现任何 ruoyi-* 嵌套 jar")

    # 类检索顺序：先 ruoyi-*，再其余
    ruoyi_entries = [e for e in all_entries
                     if os.path.basename(e).startswith("ruoyi-")]
    other_entries = [e for e in all_entries if e not in set(ruoyi_entries)]
    ordered = ruoyi_entries + other_entries

    if classes:
        javap = find_javap()
        m["javap"] = javap
        scan_state = {"scanned": 0, "skipped_large": []}
        with tempfile.TemporaryDirectory(prefix="baseline-manifest-") as tmp:
            extract_cache: dict = {}
            for fqn in classes:
                cp, origin = resolve_class(jar_path, fqn, tmp, layout,
                                           ordered, extract_cache, scan_state)
                if cp is None:
                    m["classes"].append({
                        "name": fqn, "found": False,
                        "error": "未在任何嵌套 jar / BOOT-INF/classes 中找到该类",
                    })
                    continue
                entry = javap_class(javap, cp, fqn)
                entry["classpath_origin"] = origin
                m["classes"].append(entry)
        m["jar_scan"] = {
            "scanned": scan_state["scanned"],
            "skipped_large": scan_state["skipped_large"],
        }
        if scan_state["skipped_large"]:
            m["warnings"].append(
                "有 %d 个嵌套 jar 因体积超限未参与类检索（见 jar_scan.skipped_large），"
                "「未找到」结论可能不成立" % len(scan_state["skipped_large"]))

    for c in m["classes"]:
        if not c.get("found"):
            m["warnings"].append(f"类未解析成功: {c['name']}")

    # 构建时间 vs HEAD 时间
    if m.get("jar_mtime_epoch") and m.get("git_head_time"):
        try:
            head_epoch = int(datetime.fromisoformat(m["git_head_time"]).timestamp())
            delta = head_epoch - m["jar_mtime_epoch"]
            m["jar_vs_head_seconds"] = delta
            if delta > 60:
                m["warnings"].append(
                    "jar 比当前 HEAD 提交早 %d 分钟——进程跑的是旧构建" % (delta // 60))
        except ValueError:
            pass

    return m


def validate_manifest(m: dict):
    """校验器：返回错误列表。空列表 = 清单可用。"""
    errors = []
    if m.get("pid") is not None:
        if not m.get("cmdline"):
            errors.append("给了 --pid 但未取到命令行")
        if m.get("port") is None:
            errors.append("给了 --pid 但未取到监听端口")
    if not m.get("jar_path"):
        errors.append("未解析出 jar 路径")
        return errors
    if not os.path.isfile(m["jar_path"]):
        errors.append(f"jar 不存在: {m['jar_path']}")
        return errors
    if not m.get("jar_mtime"):
        errors.append("未取到 jar mtime")
    if m.get("is_fat_jar") and not m.get("inner_jars"):
        errors.append("fat jar 内未列出任何 ruoyi-* 嵌套 jar")
    for c in m.get("classes", []):
        if not c.get("found"):
            errors.append(f"类未解析: {c['name']}")
    return errors


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="源码→class→JAR→进程 追溯清单（只读）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--pid", type=int, help="从进程命令行取 jar，并用 lsof 取监听端口")
    src.add_argument("--jar", help="直接指定 fat jar 路径")
    p.add_argument("--class", dest="classes", action="append", default=[],
                   metavar="FQN", help="要 javap 反编译的类全限定名，可重复")
    p.add_argument("--repo", default=os.getcwd(), help="读 git 信息的仓库路径（默认 cwd）")
    p.add_argument("--no-sha256", action="store_true", help="跳过大 jar 的 sha256（默认算）")
    p.add_argument("--indent", type=int, default=2, help="JSON 缩进（0 = 紧凑）")
    p.add_argument("--errors-only", action="store_true", help="只打印 warnings/errors 摘要")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)

    manifest = build_manifest(
        pid=args.pid,
        jar_path=args.jar,
        classes=args.classes,
        repo=args.repo,
        do_sha256=not args.no_sha256,
    )
    errors = validate_manifest(manifest)

    injected = os.environ.get("BASELINE_FAIL_SEED") == "1"
    if injected:
        # 自证能红：注入一条必然违反的断言，要求校验器抓出来。
        # 抓不出来 = 校验器恒绿 = 假绿，同样算失败。
        seeded = json.loads(json.dumps(manifest))
        seeded.setdefault("warnings", []).append(
            "BASELINE_FAIL_SEED：注入一条不存在的类，校验器必须报错")
        seeded.setdefault("classes", []).append(
            {"name": "org.ruoyi.__fail_seed__.DoesNotExist", "found": False})
        seeded_errors = validate_manifest(seeded)
        caught = [e for e in seeded_errors if "__fail_seed__" in e]
        sys.stderr.write(
            "BASELINE_FAIL_SEED=1\n"
            f"  注入项被校验器抓出：{len(caught)} 条（期望 >=1）\n"
            f"  校验器总报错：{len(seeded_errors)} 条\n"
        )
        if not caught:
            sys.stderr.write("  FAIL_SEED 未被检出 —— 校验器恒绿（假绿），判失败\n")
        else:
            sys.stderr.write("  FAIL_SEED 已被检出 —— 校验器不是恒绿\n")
        sys.stderr.write("  => 自证能红成立，EXIT=1\n")
        return 1

    if args.errors_only:
        print(f"jar_path: {manifest.get('jar_path')}")
        print(f"port: {manifest.get('port')}  pid: {manifest.get('pid')}")
        print(f"inner_jars: {len(manifest.get('inner_jars', []))}")
        for c in manifest.get("classes", []):
            status = "found" if c.get("found") else "NOT-FOUND"
            n = c.get("method_count", "-")
            print(f"  class {c['name']}: {status} methods={n}")
        for w in manifest.get("warnings", []):
            print(f"  WARN {w}")
        for e in errors:
            print(f"  ERROR {e}")
        print("RESULT:", "PASS" if not errors else "FAIL")
        return 0 if not errors else 1

    indent = args.indent if args.indent > 0 else None
    print(json.dumps(manifest, ensure_ascii=False, indent=indent, sort_keys=False))

    if errors:
        sys.stderr.write("校验失败：\n")
        for e in errors:
            sys.stderr.write(f"  - {e}\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
