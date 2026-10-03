#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
java-param-doc-check.py —— 「JavaDoc @param 与方法签名是否一致」的可靠判定器

为什么需要单独一个字符级扫描器（2026-10-03 实证）：
  原 `check-doc-code-sync.sh` 第 4 条规则用 grep 比较「整个文件的 @param 行数」与
  「含逗号的方法行数」，量纲不对，且永远只打 INFO。想把它变成真正的判据时，
  用正则逐方法比对连续失败了 4 次，伪阳性样本抽查 3/3 全是假的：
    - 多行签名（`public X m(\n  @A(...) T a,\n  T b)`）被当成零参数
    - 参数内注解（`@Pattern(regexp="person|project|kpi_record")`）被当成了参数名
  结论：**正则做不了这件事**。改用字符级扫描（跟踪字符串字面量、括号/泛型深度），
  并在深度 0 的逗号处切分，才得到稳定结果。

判定口径（刻意收窄，避免变成变相的「强制写注释」）：
  只检查「已经写了 @param」的方法。若 javadoc 里一个 @param 都没有，不计入违规
  ——那是「没写参数文档」，不是「文档与代码不一致」。owner 2026-10-03 决策：
  本项目不强制「每个公开方法都写注释」，故「缺注释」一律降为 INFO。
  真正的违规只有两种：
    ① 文档里的 @param 名在签名里不存在（多半是改了参数名没同步文档）
    ② 签名里有参数在文档里找不到（且该 javadoc 至少写了一个 @param，说明作者在维护它）

用法：
    python3 scripts/lib/java-param-doc-check.py <源码根目录> [--json]
  退出码：0 = 无不一致；1 = 发现不一致；2 = 输入错误

自证：`--self-test` 会就地构造一个「把 @param 名字写错」的临时样本，验证确实会报 1。
"""
import json
import re
import sys
import tempfile
from pathlib import Path

# 公开方法签名开头。`record` 不是方法，必须排除——原实现把它误算成方法（14 条假阳性来源）。
METHOD_RE = re.compile(r"^\s*public\s+[\w<>?,\[\]\s]+\s+(\w+)\s*\(")
PARAM_DOC_RE = re.compile(r"@param\s+(\w+)")
# 参数段里的注解，可能带括号参数（括号内还可能有字符串/嵌套），先整段剔除再取标识符
ANNOTATION_RE = re.compile(r"@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?")


def scan_params(sig: str):
    """从「左括号之后、配平右括号之前」的文本里抽出参数名。

    字符级扫描，而不是正则切分：
      - 字符串/字符字面量内的逗号与括号不算结构（`@Pattern(regexp="a|b,c")`）
      - 跟踪 ()<>[] 深度，只在深度 0 的逗号处分段
    每段剔除注解后取最后一个标识符 = 参数名。
    """
    parts, cur, depth, i, n = [], "", 0, 0, len(sig)
    while i < n:
        c = sig[i]
        if c in "\"'":                       # 字面量：整段吞掉，含转义
            q = c
            cur += c
            i += 1
            while i < n and sig[i] != q:
                if sig[i] == "\\":
                    cur += sig[i]
                    i += 1
                if i < n:
                    cur += sig[i]
                    i += 1
            if i < n:
                cur += sig[i]
                i += 1
            continue
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        if c == "," and depth == 0:
            parts.append(cur)
            cur = ""
            i += 1
            continue
        cur += c
        i += 1
    if cur.strip():
        parts.append(cur)

    names = []
    for p in parts:
        cleaned = ANNOTATION_RE.sub("", p.strip())
        toks = re.findall(r"[A-Za-z_$][\w$]*", cleaned)
        if toks:
            names.append(toks[-1])
    return names


def iter_methods(path: Path):
    """产出 (行号, 方法名, 实参名列表, javadoc 里的 @param 名列表, 是否 @Override)。"""
    lines = path.read_text(errors="ignore").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        m = METHOD_RE.match(line)
        if m and "record " not in line:
            name = m.group(1)
            open_idx = line.index("(", m.start())
            sig = line[open_idx + 1:]
            # 左括号已消费，故深度初值为 1；跨行签名必须继续读，否则多行签名会被当零参数
            depth = 1 + sig.count("(") - sig.count(")")
            j = i
            while depth > 0 and j + 1 < len(lines):
                j += 1
                sig += " " + lines[j].strip()
                depth = 1 + sig.count("(") - sig.count(")")
            if ")" in sig:
                sig = sig[:sig.rindex(")")]
            params = scan_params(sig)

            # 向上：跳过多行注解，找紧邻的 javadoc 块
            k = i - 1
            while k >= 0 and (not lines[k].strip() or lines[k].strip().startswith("@")):
                k -= 1
            doc, is_override = [], False
            if k >= 0 and lines[k].strip().startswith("*/"):
                s = k
                while s >= 0 and not lines[s].strip().startswith("/**"):
                    s -= 1
                if s >= 0:
                    is_override = any(
                        lines[x].strip().startswith("@Override") for x in range(s, i)
                    )
                    doc = PARAM_DOC_RE.findall("\n".join(lines[s:k + 1]))
            yield i + 1, name, params, doc, is_override
            i = j
        i += 1


def check(root: Path):
    """返回违规列表 [(相对路径, 行号, 方法名, 实参, 文档名, 多余, 缺失)]。"""
    violations = []
    scanned = with_param_doc = overrides = 0
    for pattern in ("*Controller.java", "*Service.java"):
        for f in sorted(root.rglob(pattern)):
            for ln, name, params, doc, is_override in iter_methods(f):
                scanned += 1
                if is_override:                  # 注释应从接口继承，豁免
                    overrides += 1
                    continue
                if not doc:                      # 没写 @param 不算「不一致」
                    continue
                with_param_doc += 1
                extra = [d for d in doc if d not in params]
                missing = [p for p in params if p not in doc]
                if extra or missing:
                    violations.append(
                        (
                            str(f.relative_to(root)),
                            ln,
                            name,
                            params,
                            doc,
                            extra,
                            missing,
                        )
                    )
    return violations, {"scanned": scanned, "with_param_doc": with_param_doc,
                        "overrides_exempt": overrides}


def self_test(root: Path) -> int:
    """就地变异：把一个已存在的 @param 名字改错，确认判定器会报违规。"""
    target = None
    for f in sorted(root.rglob("*Controller.java")):
        if PARAM_DOC_RE.search(f.read_text(errors="ignore")):
            target = f
            break
    if target is None:
        print("[SELF-TEST] 找不到含 @param 的 Controller，无法自证", file=sys.stderr)
        return 2
    text = target.read_text(errors="ignore")
    mutated = PARAM_DOC_RE.sub(lambda m: "@param " + m.group(1) + "X", text, count=1)
    if mutated == text:
        print("[SELF-TEST] 变异未生效", file=sys.stderr)
        return 2
    tmp = Path(tempfile.mkdtemp())
    try:
        (tmp / target.name).write_text(mutated, encoding="utf-8")
        v, _ = check(tmp)
        if v:
            print(f"[SELF-TEST] ✅ 判定器能报违规（注入后命中 {len(v)} 处）")
            return 0
        print("[SELF-TEST] ❌ 注入假阳性后仍报 0 处 —— 判定器是假绿", file=sys.stderr)
        return 1
    finally:
        for p in tmp.iterdir():
            p.unlink()
        tmp.rmdir()


def main() -> int:
    flags = {"--json", "--self-test"}
    args = [a for a in sys.argv[1:] if a not in flags]
    as_json = "--json" in sys.argv
    if "--self-test" in sys.argv:
        root = Path(args[0]) if args else Path("ruoyi-modules/ruoyi-ipd/src/main/java")
        if not root.is_dir():
            print(f"[ERROR] 目录不存在: {root}", file=sys.stderr)
            return 2
        return self_test(root)

    root = Path(args[0]) if args else Path("ruoyi-modules/ruoyi-ipd/src/main/java")
    if not root.is_dir():
        # 输入缺失一律 exit 2，绝不降级成「0 条通过」（与 scripts/lib/audit-gate-input.sh 同约定）
        print(f"[ERROR] 输入目录不存在: {root}", file=sys.stderr)
        return 2
    violations, stats = check(root)
    if as_json:
        print(json.dumps({"violations": violations, "stats": stats}, ensure_ascii=False))
    else:
        for v in violations:
            print(f"{v[0]}:{v[1]} {v[2]}  实参={v[3]} 文档={v[4]} 多余={v[5]} 缺失={v[6]}")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
