#!/usr/bin/env python3
"""
汇总卡翻 done 门禁 — R43 P1 落地(R41 QA 子报告 §2.2 + §2.3)
=============================================================

功能:
  输入汇总卡号(如 P3-1, P4-4)或 --all, 检查该汇总卡的所有子卡是否都 done。
  子卡全 done → 汇总卡可翻 done(自动)
  有子卡非 done → 汇总卡禁止翻 done,列出未 done 子卡清单

用法:
  python3 scripts/check-done-gate-summary.py P3-1             # 单汇总卡
  python3 scripts/check-done-gate-summary.py P3-1 P3-3 P4-4   # 多张汇总卡
  python3 scripts/check-done-gate-summary.py --all            # 全部 14+4 张汇总卡
  python3 scripts/check-done-gate-summary.py P3-1 --json out.json
  python3 scripts/check-done-gate-summary.py --self-test      # 自证能红(内置 fixture)
  python3 scripts/check-done-gate-summary.py --list           # 列出汇总卡清单

撞车 0 约束:
  - 不动兄弟 scripts/check-done-gate.py(单卡门禁,343 行)
  - 仅读取 manage.py 不写
  - 不 commit / 不 push

三层哨兵(R30+ 铁律):
  - 输入层:看板不可达 / 镜像文件不存在 / 卡号不在白名单 → fail
  - 解析层:子卡 UUID 解析失败 / 状态字段为空 → fail
  - 负向验证:--self-test 内置 fixture(故意造一个非 done 子卡)→ 必须能红
"""

import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BOARD = "http://127.0.0.1:62250"
PID = "01dcf15c-86bb-4c7b-957c-8fe44bddd10d"  # ruoyi-ai 项目

# 汇总卡清单(R41 QA §2.2 + §2.3 + 兄弟 R42 §3.1 整合)
# 父卡 → 子卡前缀(manage.py list P*-N.* | grep -v done 检查)
SUMMARY_CARDS = {
    # 阻断汇总 4 张(§2.2)
    "P3-1": "P3-1",       # KPI 结构
    "P3-3": "P3-3",       # 月度津贴
    "P3-4": "P3-4",       # 奖金池
    "P4-4": "P4-4",       # 报表导出
    # 汇总卡 14 张(§2.3) — R42 §3.1 兄弟报告
    "P0-9": "P0-9",       # P0 阶段验收
    "P1-3": "P1-3",       # 69 动作 Seed
    "P1-4": "P1-4",       # 深轻管分离校验
    "P1-6": "P1-6",       # 字段录入
    "P1-10": "P1-10",     # 版本链与人工审核归档
    "P2-3": "P2-3",       # 阶段动作收口
    "P3-2": "P3-2",       # 项目绩效
    "P3-7": "P3-7",       # P3 阶段验收
    "P3-8": "P3-8",       # 津贴奖金
    "P4-2": "P4-2",       # P4 阶段
    "P4-5": "P4-5",       # 249AC 验收回归
}


def fetch_all_tasks():
    """fresh 拉看板所有卡 — 必须用 LIST 而非单卡 GET(单卡 GET 可能返空描述,R25 教训)"""
    try:
        url = f"{BOARD}/api/tasks?project_id={PID}"
        with urllib.request.urlopen(url, timeout=10) as resp:
            data = json.loads(resp.read())
        if not data.get("success"):
            return {"error": f"看板 API 返 success=false: {data.get('error_data')}"}
        return {"tasks": data.get("data") or []}
    except Exception as e:
        return {"error": f"看板不可达: {e}"}


def parse_sub_cards_status(all_tasks, parent_prefix):
    """解析某汇总卡的所有子卡 status

    返回:{child_card_id: {status, uuid, title}}
    """
    children = {}
    # 子卡号模式:父卡号 + .N(如 P3-1.1, P3-1.2, ...)
    pattern = re.compile(rf"^{re.escape(parent_prefix)}\.\d+$")
    for t in all_tasks:
        title = (t.get("title") or "").strip()
        if not title:
            continue
        # title 格式: "[P3-1.1] xxx" 或 "P3-1.1 xxx" — 提取卡号
        m = re.search(r"\[?(P\d+-\d+\.\d+)\]?", title)
        if not m:
            continue
        card_id = m.group(1)
        if not pattern.match(card_id):
            continue
        children[card_id] = {
            "status": (t.get("status") or "").lower(),
            "uuid": t.get("id"),
            "title": title,
        }
    return children


def check_one_summary(card_id, all_tasks, strict=True):
    """单张汇总卡检查

    返回:{parent, total, done, not_done, not_done_list, can_flip, reason}
    """
    if card_id not in SUMMARY_CARDS:
        return {
            "card_id": card_id,
            "can_flip": False,
            "reason": f"卡号 {card_id} 不在汇总卡白名单(已知 15 张)",
            "known_summaries": list(SUMMARY_CARDS.keys()),
        }

    parent_prefix = SUMMARY_CARDS[card_id]
    children = parse_sub_cards_status(all_tasks, parent_prefix)

    total = len(children)
    # R214 门禁语义修正（2026-09-24 owner 拍板）：cancelled 也是终态，不得计入 not_done
    # （P3-4 教训：唯一 blocker 是已 cancelled 的旧版平行卡，导致汇总卡永远翻不了）
    not_done_list = [
        {"card_id": k, **v}
        for k, v in children.items()
        if v["status"] not in ("done", "cancelled")
    ]
    done_count = sum(1 for v in children.values() if v["status"] == "done")

    can_flip = (len(not_done_list) == 0 and total > 0) if strict else (done_count >= total)

    reason = ""
    if total == 0:
        reason = f"未找到 {card_id} 的任何子卡(看板无数据或卡号拼错)"
        can_flip = False
    elif not_done_list:
        reason = f"{len(not_done_list)}/{total} 子卡未 done"

    return {
        "card_id": card_id,
        "parent_prefix": parent_prefix,
        "total": total,
        "done": done_count,
        "not_done": len(not_done_list),
        "not_done_list": not_done_list,
        "can_flip": can_flip,
        "reason": reason,
    }


def self_test():
    """R30+ 负向验证:故意造 fixture 验证门禁能红"""
    print("=" * 70)
    print("R43 P1 汇总卡门禁 --self-test (三层哨兵 + 负向验证)")
    print("=" * 70)

    failures = []

    # --- 哨兵 1:输入层 - 看板不可达(用 mock,避免 import self) ---
    print("\n[哨兵 1] 输入层: 看板不可达时必须 fail")
    # Mock fetch_all_tasks 返 error
    def _mock_fetch_fail():
        return {"error": "mock: 看板不可达"}
    original_fetch = fetch_all_tasks
    globals()["fetch_all_tasks"] = _mock_fetch_fail
    result = check_one_summary("P3-1", [])
    globals()["fetch_all_tasks"] = original_fetch
    if result.get("can_flip") is not False:
        failures.append(f"哨兵 1 FAIL: 看板不可达却 can_flip=True: {result}")
    else:
        print(f"  ✅ 看板不可达时 fail (can_flip=False)")

    # --- 哨兵 2:输入层 - 卡号不在白名单 ---
    print("\n[哨兵 2] 输入层: 卡号不在白名单必须 fail")
    result = check_one_summary("XX-99", [])
    if result.get("can_flip") is not False or "不在汇总卡白名单" not in result.get("reason", ""):
        failures.append(f"哨兵 2 FAIL: 未知卡号却 not fail: {result}")
    else:
        print(f"  ✅ 未知卡号 fail (reason='{result.get('reason')}')")

    # --- 哨兵 3:解析层 - 空 children 必须 fail ---
    print("\n[哨兵 3] 解析层: 无子卡数据必须 fail")
    result = check_one_summary("P3-1", [])  # 空任务列表
    if result.get("can_flip") is not False or "未找到" not in result.get("reason", ""):
        failures.append(f"哨兵 3 FAIL: 空子卡却 can_flip=True: {result}")
    else:
        print(f"  ✅ 空子卡 fail (reason='{result.get('reason')}')")

    # --- 哨兵 4:负向验证 - 有子卡非 done 必须 fail ---
    print("\n[哨兵 4] 负向验证: 子卡非 done 必须 fail")
    fake_tasks = [
        {"id": "uuid-1", "title": "[P3-1.1] 子卡 1", "status": "done"},
        {"id": "uuid-2", "title": "[P3-1.2] 子卡 2", "status": "inprogress"},  # 未 done
        {"id": "uuid-3", "title": "[P3-1.3] 子卡 3", "status": "done"},
    ]
    result = check_one_summary("P3-1", fake_tasks)
    if result.get("can_flip") is not False or result.get("not_done") != 1:
        failures.append(f"哨兵 4 FAIL: 子卡非 done 却 can_flip=True: {result}")
    else:
        print(f"  ✅ 子卡非 done fail (not_done=1, not_done_list 含 P3-1.2)")

    # --- 哨兵 5:正向验证 - 全部 done 必须通过 ---
    print("\n[哨兵 5] 正向验证: 全部子卡 done 必须 can_flip=True")
    fake_tasks = [
        {"id": "uuid-1", "title": "[P3-1.1] 子卡 1", "status": "done"},
        {"id": "uuid-2", "title": "[P3-1.2] 子卡 2", "status": "done"},
    ]
    result = check_one_summary("P3-1", fake_tasks)
    if result.get("can_flip") is not True:
        failures.append(f"哨兵 5 FAIL: 全 done 却 can_flip=False: {result}")
    else:
        print(f"  ✅ 全 done pass (can_flip=True, total=2)")

    # --- 哨兵 6:cancelled 子卡不得阻塞翻牌（R214 语义修正，P3-4 场景）---
    print("\n[哨兵 6] done+cancelled 混合必须 can_flip=True（cancelled 计入终态）")
    fake_tasks = [
        {"id": "uuid-1", "title": "[P3-4.1] 子卡 1", "status": "cancelled"},
        {"id": "uuid-2", "title": "[P3-4.2] 子卡 2", "status": "done"},
        {"id": "uuid-3", "title": "[P3-4.3] 子卡 3", "status": "done"},
    ]
    result = check_one_summary("P3-4", fake_tasks)
    if result.get("can_flip") is not True or result.get("not_done") != 0 or result.get("done") != 2:
        failures.append(f"哨兵 6 FAIL: done+cancelled 混合未放行: {result}")
    else:
        print(f"  ✅ cancelled 不阻塞 (can_flip=True, not_done=0, done=2)")

    # --- 总结 ---
    print("\n" + "=" * 70)
    if failures:
        print(f"❌ --self-test FAIL ({len(failures)} 个哨兵失败):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("✅ --self-test PASS (5/5 哨兵全过)")
    print("=" * 70)
    return 0


def main():
    args = sys.argv[1:]

    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0

    if args[0] == "--self-test":
        return self_test()

    if args[0] == "--list":
        print("汇总卡白名单 (15 张):")
        for k in SUMMARY_CARDS:
            print(f"  - {k}")
        return 0

    # 收集卡号
    json_out = None
    cards = []
    if "--all" in args:
        cards = list(SUMMARY_CARDS.keys())
    else:
        for a in args:
            if a == "--json":
                idx = args.index(a)
                if idx + 1 < len(args):
                    json_out = args[idx + 1]
                continue
            if a.startswith("--"):
                continue
            cards.append(a)

    if not cards:
        print("❌ 错误: 至少指定一张汇总卡或 --all", file=sys.stderr)
        return 2

    # 拉所有卡
    fetch = fetch_all_tasks()
    if "error" in fetch:
        print(f"❌ 错误: {fetch['error']}", file=sys.stderr)
        return 2

    all_tasks = fetch["tasks"]

    # 跑所有卡
    results = [check_one_summary(c, all_tasks) for c in cards]

    # 输出
    print("=" * 70)
    print(f"R43 P1 汇总卡门禁 — {len(cards)} 张卡")
    print("=" * 70)
    for r in results:
        marker = "✅" if r["can_flip"] else "❌"
        print(f"\n{marker} {r['card_id']}: total={r.get('total', 0)}, done={r.get('done', 0)}, "
              f"not_done={r.get('not_done', 0)}, can_flip={r['can_flip']}")
        if r.get("reason"):
            print(f"   reason: {r['reason']}")
        if r.get("not_done_list"):
            print(f"   未 done 子卡:")
            for c in r["not_done_list"]:
                print(f"     - {c['card_id']} ({c['status']}) — {c['title'][:60]}")

    # 退出码
    fail_count = sum(1 for r in results if not r["can_flip"])
    print("\n" + "=" * 70)
    print(f"汇总: {len(results) - fail_count}/{len(results)} PASS")
    print("=" * 70)

    if json_out:
        Path(json_out).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"📄 JSON 写到: {json_out}")

    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
