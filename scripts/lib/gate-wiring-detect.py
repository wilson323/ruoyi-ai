#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为 check-gate-wiring.sh 判定：一个门禁脚本是否被**真正执行**。

## 为什么单独抽出来

原实现（check-gate-wiring.sh:77）是 `grep -rlF "$脚本名" "$目录"` —— **纯字符串出现
即算接线**。于是「注释里提过」「echo 的文案里写过」「CI 的 `paths:` 触发过滤里列过」
「步骤 `name:` 里带过」全部被当成接线。实测有 7 个门禁因此被误判为已接 CI，
其中 3 个既没接线也没登记 —— 三条检测路径全失效，它们对任何自动化检查都不存在。

判定「是否被执行」需要分辨引用形态，纯 grep 做不到；用 shell 写 quote/缩进感知的
解析器既难读又难测。故抽成本文件，**只负责判定**，报告与退出码仍由
check-gate-wiring.sh 负责（保持 CI 调用方式不变）。

## 判为「被执行」的形态（依据本仓实际语料枚举，不是凭空设计）

1. 直接执行：命令位置出现解释器，后跟路径与脚本名
     `run: bash scripts/check-x.sh` / `bash "$REPO_ROOT/scripts/check-x.sh"`
     `python3 scripts/check-x.py` / `python3 "$ROOT/scripts/check-x.py"`
2. 相对路径直接执行：`./scripts/check-x.sh`
3. 命令串字面量：整行（去缩进与首引号后）以 `scripts/check-x.sh` 开头
     —— .harness/gate.sh 的 `GATE_CMDS=( "scripts/check-x.sh --flag" )` 形态
4. 间接执行：先 `VAR=...scripts/check-x.sh...` 赋值，随后同文件内出现
     `bash "$VAR"` / `exec "$VAR"` / 行首 `"$VAR"` 等执行位置
     —— .claude/hooks/check-pre-commit.sh 与 post-commit-completeness.sh 的形态
5. JS：脚本名出现在 `exec(`/`execSync(`/`spawn(`/`spawnSync(` 的参数里
6. .claude/settings.json：脚本名出现在某个 hook 的 `command` 字段值里

## 明确判为「不是执行」（本仓实测存在的四类假阳性）

- 注释行（首非空字符为 `#`）
- 输出文案：整行命令是 `echo` / `printf` / `cat` / `console.log` / `log(`
     实例：r30-done-gate.yml:96 `echo " … python3 scripts/check-done-gate.py … "`
           ratchet-data-guard.cjs:24 `hint: '合法入口: node scripts/check-api-contract-fe-be.mjs …'`
- workflow 的 `paths:` 触发过滤与步骤 `name:`（只认 `run:` 块内的内容）
- 存在性判断 `[ -x .../scripts/check-x.sh ]`（是检查它在不在，不是跑它）

## 用法

    python3 scripts/lib/gate-wiring-detect.py <候选名文件>
      候选名文件：每行一个脚本 basename
      输出：每行 `名称<TAB>类别<TAB>证据`，类别 ∈ CI | HOOK | HARNESS | NONE
"""

import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 解释器 / 执行前缀（出现在命令位置才算）
_VERBS = r'(?:bash|sh|zsh|dash|node|python3|python)'
# 包装器：本身不执行脚本，但 `$VAR` 出现在其参数位即构成执行
# （实例：check-write-endpoint-ownership.sh:47 `exec perl -e '...' -- "$PY" "$GATE"`）
_RUNNERS = r'(?:perl|timeout|nohup|stdbuf|env|command|xargs|sudo)'
_CMDPOS = r'(?:^|[;&|(!]|\bthen\b|\bdo\b|\belse\b|\beval\b|\bexec\b)'
_REDIRECT = r'(?:[^\s;&|()<>\'"]*/)*'          # 路径前缀（可含 $VAR/ 与引号前段）
_SKIP_FIRST_WORDS = ('echo', 'printf', 'cat', 'console.log', 'console.error', 'log(', 'print(')

# .claude/settings.json 的 command 值由单独的 JSON 分支处理，不在此列
_JS_SPAWN = re.compile(r'\b(?:exec|execSync|spawn|spawnSync|execFile|execFileSync)\s*\(')


def _read_lines(path):
    try:
        with open(path, encoding='utf-8', errors='ignore') as fh:
            return fh.read().splitlines()
    except OSError:
        return []


def _is_output_text(line):
    """整行是一条「打印」命令 → 里面的脚本名是文案，不算执行。"""
    s = line.strip().lstrip('- ').strip()
    s = re.sub(r'^(?:-\s*)?(?:run|name|with|env)\s*:\s*', '', s).strip()
    for w in _SKIP_FIRST_WORDS:
        if s.startswith(w):
            return True
    return False


def _exec_ref_in_line(line, name):
    """一行里是否出现「命令位置 + 路径 + name」。"""
    if not line or line.lstrip().startswith('#'):
        return False
    if _is_output_text(line):
        return False
    if _exists_test_in_line(line, name):
        return False
    esc = re.escape(name)
    # 1. 解释器 + 路径
    if re.search(_CMDPOS + r'\s*(?:sudo\s+|env\s+\S+\s+)*' + _VERBS + r'\s+["\']?'
                 + _REDIRECT + esc, line):
        return True
    # 2. ./scripts/x.sh
    if re.search(_CMDPOS + r'\s*\./[^\s;&|()\'\"]*' + esc, line):
        return True
    # 3. 命令串字面量：整行以 scripts/x.sh 开头（.harness GATE_CMDS 形态）
    s = line.strip()
    if s[:1] in ('"', "'"):
        s = s[1:]
    if re.match(r'^(?:\./)?(?:[^\s"\']*/)*' + esc + r'(?:[\s"\']|$)', s):
        return True
    return False


def _exists_test_in_line(line, name):
    """`[ -x .../scripts/x.sh ]` 之类：在问「它在不在」，不是在跑它。"""
    return bool(re.search(r'\[\s*-[xefd]?\s*[^\]]*' + re.escape(name), line))


def _yaml_run_block_lines(path):
    """返回 workflow 里属于 `run:` 块的行（含行号）。只认 run 块——
    `paths:` 触发过滤与步骤 `name:` 都不算接线。"""
    lines = _read_lines(path)
    out = []
    i = 0
    while i < len(lines):
        raw = lines[i]
        m = re.match(r'^(\s*)(?:-\s*)?run\s*:\s*(.*)$', raw)
        if not m:
            i += 1
            continue
        indent = len(m.group(1))
        rest = m.group(2).strip()
        if rest and rest not in ('|', '>', '|-', '>-', '|+', '>+', '|2', '>2'):
            out.append((i + 1, rest))
            i += 1
            continue
        i += 1
        while i < len(lines):
            l = lines[i]
            if not l.strip():
                i += 1
                continue
            if (len(l) - len(l.lstrip())) <= indent:
                break
            out.append((i + 1, l))
            i += 1
    return out


def _indirect_var(line, name):
    """`VAR=...name...` → 返回 VAR 名，否则 None。"""
    m = re.match(r'^\s*(?:local\s+|export\s+|readonly\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$',
                 line)
    if not m:
        return None
    var, rhs = m.group(1), m.group(2)
    if _is_output_text(line):
        return None
    return var if name in rhs else None


def _var_is_executed(lines, var):
    """同文件里 VAR 是否出现在执行位置。"""
    v = re.escape(var)
    pat_verb = re.compile(_CMDPOS + r'\s*(?:' + _VERBS + r')\s+["\']?\$\{?' + v + r'\b')
    # 包装器形态：verb/runner 在前，"$VAR" 出现在其后的参数位（如 exec perl -- "$PY" "$GATE"）
    pat_arg = re.compile(_CMDPOS + r'\s*(?:' + _VERBS + r'|' + _RUNNERS + r')\b[^\n]*["\']?\$\{?' + v + r'\b')
    pat_bare = re.compile(r'^\s*["\']?\$\{?' + v + r'\b')
    pat_exec = re.compile(r'\bexec\s+["\']?\$\{?' + v + r'\b')
    for l in lines:
        if l.lstrip().startswith('#') or _is_output_text(l):
            continue
        if pat_verb.search(l) or pat_arg.search(l) or pat_bare.search(l) or pat_exec.search(l):
            return True
    return False


def shell_file_ref(path, name):
    """shell 文件里是否有对 name 的执行引用。返回证据行文本或 None。"""
    lines = _read_lines(path)
    for idx, l in enumerate(lines, 1):
        if _exec_ref_in_line(l, name):
            return f'{path}:{idx}'
    # 间接：赋值 + 该变量在执行位置被用
    for l in lines:
        var = _indirect_var(l, name)
        if var and _var_is_executed(lines, var):
            return f'{path}:{var}='
    return None


def js_file_ref(path, name):
    """JS：只有出现在进程启动调用的参数里才算执行（hint 字符串不算）。"""
    lines = _read_lines(path)
    for idx, l in enumerate(lines, 1):
        if name not in l or l.lstrip().startswith(('*', '//', '#')):
            continue
        if _JS_SPAWN.search(l):
            return f'{path}:{idx}'
    return None


def py_file_ref(path, name):
    """Python：名字出现在「赋值给路径/命令变量」且同文件有子进程调用 → 执行边。
    实例：check-staged-snapshot.py
      checker = snapshots[0] / 'scripts/check-api-contract-fe-be.mjs'
      command = ['node', str(checker), ...]
      subprocess.run(command, ...)
    名字与子进程不在同一行（两级间接），只能按文件级近似。测试夹具里
    write_text 造同名文件不算（不是赋值形态）。"""
    lines = _read_lines(path)
    has_subproc = any(re.search(r'\b(?:subprocess|os\.system|Popen)\b', l)
                      for l in lines)
    if not has_subproc:
        return None
    for idx, l in enumerate(lines, 1):
        if l.lstrip().startswith('#') or name not in l:
            continue
        if re.match(r'^\s*\w[\w.\[\]]*\s*(?:/|\+|=)\s*.*' + re.escape(name), l) \
                and 'write_text' not in l and 'rm' not in l:
            return f'{path}:{idx}'
    return None


def settings_json_ref(path, name):
    """只在 hook 的 command 字段值里算接线。"""
    try:
        with open(path, encoding='utf-8') as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    for event, arr in (data.get('hooks') or {}).items():
        for entry in arr or []:
            for hk in (entry or {}).get('hooks') or []:
                if name in str((hk or {}).get('command') or ''):
                    return f'.claude/settings.json(hooks.{event})'
    return None


def collect(globs):
    out = []
    for g in globs:
        for p in sorted(glob.glob(os.path.join(ROOT, g), recursive=True)):
            if os.path.isfile(p) and '__pycache__' not in p:
                out.append(os.path.relpath(p, ROOT))
    return out


def main():
    if len(sys.argv) < 2:
        print('usage: gate-wiring-detect.py <候选名文件>', file=sys.stderr)
        return 2
    with open(sys.argv[1], encoding='utf-8') as fh:
        names = [l.strip() for l in fh if l.strip()]

    ci_files = [f for f in collect(['.github/workflows/*.yml', '.github/workflows/*.yaml'])]
    hook_files = [f for f in collect(['.claude/hooks/**/*', '.claude/helpers/**/*'])
                  if f.endswith(('.sh', '.py', '.cjs', '.js', '.mjs'))]
    harness_files = collect(['.harness/*.sh'])

    def first_wiring(name):
        """入口直连：CI / HOOK / HARNESS / NONE。"""
        for f in ci_files:
            for idx, line in _yaml_run_block_lines(os.path.join(ROOT, f)):
                if _exec_ref_in_line(line, name):
                    return 'CI', f'{f}:{idx}'
        hit = settings_json_ref(os.path.join(ROOT, '.claude/settings.json'), name)
        if hit:
            return 'HOOK', hit
        for f in hook_files:
            if f.endswith(('.cjs', '.js', '.mjs')):
                hit = js_file_ref(os.path.join(ROOT, f), name)
            elif f.endswith('.py'):
                hit = None  # hooks/helpers 下的 .py 由 shell 包装执行，按 shell 判
            else:
                hit = shell_file_ref(os.path.join(ROOT, f), name)
            if hit:
                return 'HOOK', hit
        for f in harness_files:
            hit = shell_file_ref(os.path.join(ROOT, f), name)
            if hit:
                return 'HARNESS', hit
        return 'NONE', ''

    res = {n: first_wiring(n) for n in names}

    # ── 传递接线：候选 A 被候选 B 转手执行、B 已接线 ⇒ A 也算接线 ──
    # 实例（两个都实测过）：
    #   check-write-endpoint-ownership.py ← ownership.sh:47 `exec perl -- "$PY" "$GATE"`
    #   check-api-contract-fe-be.mjs ← check-staged-snapshot.py:116 `command=['node', checker]`
    # 只沿「已接线」的边继承，非接线的 scripts/*.sh 引用不构成接线。
    scripts_corpus = [f for f in collect(['scripts/*.sh', 'scripts/*.py', 'scripts/*.mjs'])
                      if os.path.basename(f) not in res]  # 排除自身，其它候选都算
    for _ in range(len(names)):  # 不动点迭代（链最长 = 候选数）
        changed = False
        for n, (cls, ev) in list(res.items()):
            if cls != 'NONE':
                continue
            for f in scripts_corpus + [s for s in collect(['scripts/*']) if s.endswith(('.sh', '.py', '.mjs'))]:
                if os.path.basename(f) == n:
                    continue
                caller = os.path.basename(f)
                if caller in res and res[caller][0] == 'NONE':
                    continue  # 调用方自己未接线，不构成传递
                hit = (py_file_ref if f.endswith('.py') else
                       (js_file_ref if f.endswith(('.mjs', '.cjs')) else shell_file_ref))(
                    os.path.join(ROOT, f), n)
                if hit:
                    src_cls = res[caller][0] if caller in res else 'SCRIPT'
                    res[n] = (src_cls, f'via {caller}({hit})')
                    changed = True
                    break
        if not changed:
            break

    for n in names:
        cls, ev = res[n]
        print(f'{n}\t{cls}\t{ev}')


if __name__ == '__main__':
    sys.exit(main())
