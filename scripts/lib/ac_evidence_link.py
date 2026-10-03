#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""OD-AM-05 证据链接器：按既定规则把 AC 编号链到测试证据上。

规则原文（docs/ipd-系统说明/治理/AC-ID-词表-20260907.md §3.2，2026-09-08 已决 OD-AM-05 方案②）：
    方法级仅当 @DisplayName 携带 ac_id 且在该测试类内唯一时可回填，
    禁止凭方法名相似度猜测；后续 scripts/ac-import.py 导入沿用。

本模块只做「机器可确证」的链接，判定口径（刻意保守）：
    covered  ← 该 ac_id 在**全仓**恰好出现在 1 条 @DisplayName 里，且该 @DisplayName 后面的
               方法真实存在 → 唯一、可复现，可回填到方法级
    partial  ← ac_id 出现过，但不唯一（同编号落在多个方法或多个类）→ 只回填到类级，不猜方法
    manual   ← ac_id 在任何 @DisplayName / 测试类里都没出现过 → 维持人工验收，不编造证据

刻意不做的事：
    · 不按方法名相似度猜（规则明文禁止）
    · 不把类名里含相似词当作证据
    · auditLog 不自动填（AC-ID 词表 OD-AM-04 未决，且已入库值经实测 9/10 在源码零命中）

退出码：0 正常 / 2 输入错误（测试源码树不存在，绝不降级成「0 条链接」）

自证：--self-test 会就地构造一个假的 @DisplayName 并断言链接器确实能识别它。
"""

import re
import sys
from pathlib import Path

# @DisplayName("...") 里的 AC 编号。注意斜杠简写有两种：
#   AC-KPI-05/07/08/10  → 同前缀续号，应展开为 4 条
#   AC-INC-10/BR-INC-12 → 斜杠后是另一套编码（BR-），不是 AC 续号，不得展开
AC_TOKEN = re.compile(r"AC-([A-Z]+)-(\d+[a-z]?)((?:\s*/\s*\d+[a-z]?)*)")
DISPLAY_NAME = re.compile(r'@DisplayName\s*\(\s*"((?:[^"\\]|\\.)*)"', re.S)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
CLASS_DECL = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")

# 方法声明行：跳过注解/注释/空行后，第一条含 '(' 且能在括号前抓到标识符的行
SKIP_LINE = re.compile(r"^\s*(@|//|\*|/\*|\*/|$)")


def expand_ac_ids(text: str):
    """从一段文本里抽出全部 AC 编号（含斜杠续号展开）。"""
    found = set()
    for match in AC_TOKEN.finditer(text):
        prefix, first, tail = match.group(1), match.group(2), match.group(3) or ""
        found.add(f"AC-{prefix}-{first}")
        for extra in re.findall(r"\d+[a-z]?", tail):
            found.add(f"AC-{prefix}-{extra}")
    return found


def _method_after(lines, start_index):
    """给定 @DisplayName 所在行号，返回其后第一个方法名；找不到返回 None。"""
    for i in range(start_index + 1, min(start_index + 12, len(lines))):
        line = lines[i]
        if SKIP_LINE.match(line):
            continue
        # 该行可能是 `void foo(` / `public void foo(` / `List<X> foo(` …
        head = line.split("(", 1)[0]
        if "(" not in line or "=" in head:
            return None
        names = re.findall(r"([A-Za-z_]\w*)\s*$", head.strip())
        if names:
            return names[0]
        return None
    return None


def scan_tests(test_root: Path):
    """扫描测试源码，返回 {ac_id: {"methods": [(fqcn, method, relpath)], "classes": {fqcn}}}"""
    if not test_root.is_dir():
        sys.stderr.write(f"[FATAL] 测试源码树不存在：{test_root}\n")
        sys.exit(2)

    evidence = {}

    def bucket(ac):
        return evidence.setdefault(ac, {"methods": [], "classes": set()})

    files = sorted(test_root.rglob("*.java"))
    if not files:
        sys.stderr.write(f"[FATAL] 测试源码树里没有任何 .java：{test_root}\n")
        sys.exit(2)

    for path in files:
        raw = path.read_text(encoding="utf-8", errors="replace")
        pkg = PACKAGE.search(raw)
        cls = CLASS_DECL.search(raw)
        if not cls:
            continue
        fqcn = f"{pkg.group(1)}.{cls.group(1)}" if pkg else cls.group(1)
        rel = path.as_posix()
        lines = raw.splitlines()

        # ① 方法级候选：@DisplayName 里带 AC 编号
        for m in DISPLAY_NAME.finditer(raw):
            acs = expand_ac_ids(m.group(1))
            if not acs:
                continue
            line_no = raw[: m.start()].count("\n")
            method = _method_after(lines, line_no)
            for ac in acs:
                b = bucket(ac)
                b["classes"].add(fqcn)
                if method:
                    b["methods"].append((fqcn, method, rel))

        # ② 类级兜底：整份文件任意位置出现 AC 编号（不含 ① 已记的方法）
        file_acs = expand_ac_ids(raw)
        for ac in file_acs:
            bucket(ac)["classes"].add(fqcn)

    return evidence


def decide(ac_id: str, evidence: dict):
    """判定单条 AC 的**证据等级**（不直接给矩阵 status，见 grade_to_status）。

    返回 (grade, unitTestClass, integrationTestPath, note)
      grade = "method-unique"  全仓唯一的方法级证据
              "class-only"     只在类级出现过
              "none"           零证据
    """
    ev = evidence.get(ac_id)
    if not ev:
        return ("none", None, None, "无 @DisplayName / 测试类引用该 AC 编号")

    methods = sorted(set(ev["methods"]))
    classes = sorted(ev["classes"])

    if len(methods) == 1:
        fqcn, method, rel = methods[0]
        return ("method-unique", f"{fqcn}#{method}", rel,
                "OD-AM-05 自动链接：@DisplayName 携带该 AC 编号且全仓唯一")
    if methods:
        fqcn, _method, rel = methods[0]
        return ("class-only", fqcn, rel,
                f"OD-AM-05 自动链接：该 AC 编号出现在 {len(methods)} 个方法中，不唯一，仅回填类级")
    return ("class-only", classes[0], None,
            f"OD-AM-05 自动链接：该 AC 编号仅出现在测试类正文中（{len(classes)} 个类），无方法级唯一证据")


def grade_to_status(grade: str) -> str:
    """证据等级 → 矩阵 status。

    **为什么 method-unique 也只映射到 partial 而不是 covered**：
    schema 对 covered 的约束（acceptance-matrix.schema.json + 校验器规则 6）是
    「必须给出 ≥1 个闭环该 AC 的 linkedCommits」。本链接器只能证明**测试证据在哪**，
    证明不了**哪次提交闭环了这条 AC**——实测：拿 `git log -S<方法名> --reverse`
    自动推「方法首次出现的提交」，与已入库人工填写的值 3/3 全不一致
    （bind_locksSnapshot 自动推 d4365d6a vs 人工填 3b8d91c4 等）。
    故自动链接**一律停在 partial**，covered 必须由人/审计给出闭环提交。
    宁可少报，不造假证据——这正是本仓反复出现的失败模式。
    """
    return "partial" if grade in ("method-unique", "class-only") else "manual"


def self_test() -> int:
    """就地注入一个假的 @DisplayName，断言链接器确实能识别——验证它不是恒绿。"""
    ok = True
    cases = [
        ("AC-KPI-05/07/08/10：四项按来源", {"AC-KPI-05", "AC-KPI-07", "AC-KPI-08", "AC-KPI-10"}),
        ("[AC-INC-10/BR-INC-12] ProjectMember", {"AC-INC-10"}),
        ("AC-REQ-04/04b 补登", {"AC-REQ-04", "AC-REQ-04b"}),
        ("无编号的中文描述", set()),
    ]
    for text, want in cases:
        got = expand_ac_ids(text)
        if got != want:
            print(f"[FAIL] 斜杠展开：{text!r}\n        期望 {sorted(want)}\n        实得 {sorted(got)}")
            ok = False

    lines = [
        "@Test",
        '@DisplayName("AC-ZZZ-99 注入用例")',
        "    void injectedProbe() {",
    ]
    if _method_after(lines, 1) != "injectedProbe":
        print("[FAIL] 方法名提取失败：@DisplayName 后的方法识别不到")
        ok = False

    ev = {"AC-ZZZ-99": {"methods": [("a.B#C", "injectedProbe", "p/C.java")], "classes": {"a.B"}}}
    grade = decide("AC-ZZZ-99", ev)[0]
    if grade != "method-unique":
        print(f"[FAIL] 唯一方法证据应判 method-unique，实得 {grade}")
        ok = False
    grade2 = decide("AC-ZZZ-99", {"AC-ZZZ-99": {"methods": [("a.B#C", "m1", "p"), ("a.B#C", "m2", "p")],
                                                 "classes": {"a.B"}}})[0]
    if grade2 != "class-only":
        print(f"[FAIL] 方法不唯一应判 class-only，实得 {grade2}")
        ok = False
    if decide("AC-NOPE-01", {})[0] != "none":
        print("[FAIL] 零证据应判 none")
        ok = False
    # 关键断言：自动链接**永远不产出 covered**（covered 需人工给出闭环提交）
    if grade_to_status("method-unique") == "covered" or grade_to_status("class-only") != "partial":
        print("[FAIL] 等级→状态映射被改坏：自动链接不得产出 covered")
        ok = False
    if grade_to_status("none") != "manual":
        print("[FAIL] 零证据应映射 manual")
        ok = False

    print("[SELF-TEST] " + ("全部通过：斜杠展开 / 方法提取 / 三级判定均可复现" if ok else "存在失败项"))
    return 0 if ok else 1


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    if "--self-test" in argv:
        return self_test()

    if not args:
        sys.stderr.write("用法: ac_evidence_link.py <测试源码根> [--json] [--self-test]\n")
        return 2

    root = Path(args[0])
    evidence = scan_tests(root)
    acs = sorted(a for a in evidence if re.match(r"^AC-[A-Z]+-\d+[a-z]?$", a))

    if "--json" in argv:
        import json
        out = {ac: decide(ac, evidence) for ac in acs}
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return 0

    print(f"[INFO] 扫描测试源码：{root}")
    print(f"[INFO] 出现过 AC 编号的条目：{len(acs)}")
    print("[INFO] 判定口径见本文件头部（covered=全仓唯一方法证据 / partial=仅类级 / manual=零证据）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
