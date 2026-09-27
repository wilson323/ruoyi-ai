#!/usr/bin/env python3
"""
看板翻卡静默失败一致性巡检（治理项 C9/O8）
历史事故：Vibe Kanban PUT 返回 200 但卡面未真正更新。本脚本机制化检测此类静默失败。

复用（禁止双轨）：通过 importlib 动态加载正主 docs/ipd-系统说明/vibe-kanban/manage.py，
复用其 STATES 符号表 / plan() 镜像解析 / find_task() 身份匹配 / api() 只读端点。
本文件不自造符号表；任何口径变更改 manage.py，此处自动跟随。

只读纪律：全程仅 GET，绝不发起 PUT/POST。硬性守卫 SSOT 项目 ID，拒绝读取 ZKER-staff。
"""
import importlib.util
import sys
import urllib.error
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MANAGE = REPO / "docs/ipd-系统说明/vibe-kanban/manage.py"
SSOT_PID = "01dcf15c-86bb-4c7b-957c-8fe44bddd10d"
FORBIDDEN_PREFIX = "3fbd49a0"  # ZKER-staff：严禁读取任何数据

EXIT_OK, EXIT_INCONSISTENT, EXIT_ENV = 0, 1, 2


def env_fail(msg):
    print(f"[ENV-ERROR][exit=2] {msg}")
    sys.exit(EXIT_ENV)


def load_manage():
    """动态加载正主 manage.py（__name__ 非 __main__，不触发其 CLI）。"""
    if not MANAGE.exists():
        env_fail(f"未找到 manage.py（口径唯一定义处）: {MANAGE}")
    spec = importlib.util.spec_from_file_location("vk_manage", MANAGE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def parse_args(argv):
    opts = {"project_id": SSOT_PID, "base_url": None, "mirror": None,
            "verbose": False}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--project-id":
            opts["project_id"] = argv[i + 1]; i += 2
        elif a == "--base-url":
            opts["base_url"] = argv[i + 1]; i += 2
        elif a == "--mirror":
            opts["mirror"] = argv[i + 1]; i += 2
        elif a == "--verbose":
            opts["verbose"] = True; i += 1
        else:
            print(f"未知参数: {a}\n用法: {argv} [--project-id ID] [--base-url URL] [--mirror PATH] [--verbose]")
            sys.exit(EXIT_ENV)
    return opts


def main():
    opts = parse_args(sys.argv[1:])
    pid = opts["project_id"]
    if pid.startswith(FORBIDDEN_PREFIX):
        env_fail(f"安全护栏：禁止读取 ZKER-staff 项目 {pid}（0 请求已发出）")
    if pid != SSOT_PID:
        print(f"[WARN] 非默认项目 id={pid} —— 仅限自检用途")

    mod = load_manage()
    if opts["base_url"]:
        mod.BASE = opts["base_url"]
    if opts["mirror"]:
        mod.PLAN = Path(opts["mirror"])
    valid_states = set(mod.STATES)  # todo/inprogress/inreview/done/cancelled

    # —— 检查①a：看板 API 可达（GET /api/projects，NoRedirect 沿用 manage.py） ——
    try:
        projects = mod.api("/api/projects")
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as exc:
        env_fail(f"看板不可达或非 JSON 响应: {mod.BASE} ({exc})")
    target = next((p for p in projects if p.get("id") == pid), None)
    if target is None:
        visible = [p.get("id", "?")[:8] for p in projects
                   if not str(p.get("id", "")).startswith(FORBIDDEN_PREFIX)]
        env_fail(f"看板中不存在项目 id={pid}（本机可见非 ZKER 项目前缀: {visible}）")
    if target.get("remote_project_id"):
        env_fail("目标项目挂接远端看板，本巡检仅支持本机模式")

    try:
        tasks = mod.api(f"/api/tasks?project_id={pid}")
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as exc:
        env_fail(f"卡片列表拉取失败: {exc}")
    if not isinstance(tasks, list):
        env_fail(f"/api/tasks 期望 list，实际 {type(tasks).__name__}")

    # —— 检查①b：全卡 status 字段非空、可解析、落在 manage.py 五态集合内 ——
    integrity = []
    for t in tasks:
        st = t.get("status")
        if not isinstance(st, str) or not st.strip():
            integrity.append((t, f"status 空/不可解析: {st!r}"))
        elif st not in valid_states:
            integrity.append((t, f"status 非法值（不在五态 {sorted(valid_states)}）: {st!r}"))

    # —— 检查②：镜像(SSOT) vs 看板 映射一致性，口径全走 manage.py plan/find_task ——
    if not mod.PLAN.exists():
        env_fail(f"未找到 SSOT 镜像: {mod.PLAN}")
    try:
        rows = mod.plan()
    except Exception as exc:  # 镜像自身不可解析 => 无法比对 => 环境错误
        env_fail(f"SSOT 镜像不可读/非法（plan() 抛错）: {exc}")

    drift = []
    for r in rows:
        key, want = r["key"], r["status"]
        try:
            task = mod.find_task(tasks, key)
        except RuntimeError as exc:  # 同号平行卡：翻卡目标不唯一，静默失败温床
            drift.append((key, f"看板身份匹配失败(同号平行卡): {exc}"))
            continue
        if task is None:
            drift.append((key, f"镜像={want}({mod.STATES[want]}) L{r['line'] + 1} -> 看板无 [KEY] 卡（建卡/翻卡丢失）"))
        elif task.get("status") != want:
            got = task.get("status")
            sym = mod.STATES.get(got, "?")
            drift.append((key, f"镜像={want}({mod.STATES[want]}) vs 看板={got}({sym}) card={task['id'][:8]} title={str(task.get('title'))[:48]}"))

    if opts["verbose"]:
        from collections import Counter
        dist = dict(Counter(str(t.get("status")) for t in tasks))
        print(f"[INFO] base={mod.BASE} project={target['name']}({pid[:8]}) 看板卡={len(tasks)} 状态分布={dist} 镜像行={len(rows)}")

    if integrity or drift:
        print(f"--- 欠账清单（一致性缺口={len(drift)} / 字段完整性违规={len(integrity)}）---")
        for key, msg in drift:
            print(f"  [drift]     {key:14} {msg}")
        for t, msg in integrity:
            print(f"  [integrity] {str(t.get('id'))[:8]:14} {msg} title={str(t.get('title'))[:48]}")
        print(f"FAIL: 发现 {len(drift) + len(integrity)} 项欠账（翻卡静默失败/映射漂移信号）[exit=1]")
        sys.exit(EXIT_INCONSISTENT)
    print(f"PASS: 看板 {len(tasks)} 卡 status 全部有效；镜像 {len(rows)} 行与看板映射完全一致 [exit=0]")
    sys.exit(EXIT_OK)


if __name__ == "__main__":
    main()

# 自检红样例（不写任何数据，不改仓库文件）：
#   python3 scripts/check-kanban-flip-silent-fail.py --project-id deadbeef-0000-0000-0000-000000000000  # 期望 exit 2
#   python3 scripts/check-kanban-flip-silent-fail.py --base-url http://127.0.0.1:59999                    # 期望 exit 2
#   python3 scripts/check-kanban-flip-silent-fail.py --project-id 3fbd49a0-0000-0000-0000-000000000000    # 护栏 期望 exit 2
#   cp 镜像到 /tmp 改一行状态后 --mirror /tmp/x.md                                                          # 期望 exit 1
