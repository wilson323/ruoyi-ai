#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ac-import.py — OD-AM-02 半自动批量导入脚本

读 docs/ipd-系统说明/外部资源/IPD系统_验收清单.md，解析 237 条 AC 表行
（匹配模式：^| AC-{MOD}-{NN}{sub}? | ...），输出 JSON draft 写到
docs/ipd-系统说明/治理/acceptance-matrix.imported-draft.json。

行为：
- 默认 --dry-run：不写文件，只打印 diff 报告
- --write：写盘但不动原 acceptance-matrix.json（避免冲掉 10 条样板）
- 复用 acceptance-matrix.json 已存在的字段（status / owner / notes 等），
  未在 catalog 中出现的字段保持空白待 owner 补

退出码：0=成功；1=catalog 不存在；2=解析到 0 行（异常）；3=用户中断
"""
import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CATALOG_PATH = REPO_ROOT / "docs/ipd-系统说明/外部资源/IPD系统_验收清单.md"
MATRIX_PATH = REPO_ROOT / "docs/ipd-系统说明/治理/acceptance-matrix.json"
DRAFT_PATH = REPO_ROOT / "docs/ipd-系统说明/治理/acceptance-matrix.imported-draft.json"
REPORT_PATH = REPO_ROOT / "docs/ipd-系统说明/治理/ac-import-diff-report.txt"

# 匹配 catalog 中表格行：| AC-INC-01 | 标题文字 | 预期 | ☐ |
#
# 2026-10-03 修复静默漏条：清单里部分行把 AC 编号写成 `| **AC-GATE-14** 🆕 |`，
# 原正则要求编号紧跟在 `| ` 之后，导致这 12 条（AC-GATE-14~21 / 1a~1d）
# **整行被丢弃且无任何提示**。更隐蔽的是丢完恰好剩 237 条，与文档标题声明的
# 「237 条」数值吻合，把缺陷完全盖住——典型的假绿。
# 现放开编号两侧的 `**` 加粗包裹，并由 assert_count_matches_doc() 兜底。
ROW_RE = re.compile(
    r"^\|\s*\*{0,2}\s*(?P<ac>AC-[A-Z]+-\d+[a-z]?)\s*\*{0,2}"
    r"(?:\s*[⚠️🆕\s]*)?\s*\|\s*"
    r"(?P<title>[^|]+?)\s*\|\s*[^|]+\s*\|\s*[☐☑]\s*\|\s*$"
)
AC_ID_RE = re.compile(r"^AC-[A-Z]+-\d+[a-z]?$")
# 文档标题声明的总数，如「IPD 237 条 AC 验收清单（P0-P4）」
DOC_COUNT_RE = re.compile(r"(\d+)\s*条\s*AC")


def parse_catalog(path: Path):
    """Return list of dicts: {ac_id, title}"""
    if not path.exists():
        sys.stderr.write(f"[ERROR] catalog 不存在: {path}\n")
        sys.exit(1)
    rows = []
    seen = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW_RE.match(line)
        if not m:
            continue
        ac_id = m.group("ac")
        if ac_id in seen:
            continue
        seen.add(ac_id)
        title = re.sub(r"\s+", " ", m.group("title")).strip()
        rows.append({"ac_id": ac_id, "title": title})
    return rows


# 已核实的清单真实条数（2026-10-03 逐行实测）。
#
# 为什么用硬刻度而不是「对文档标题声明数」：
# 原缺陷丢完恰好剩 237 条，与标题声明的 237 条**数值相同**，任何
# 「parsed < declared 才报错」的对账都会放行——即对原始缺陷是假绿。
# 因此这里锁死一个独立实测出的刻度，解析数与之不符（无论增或减）即失败。
# 新增 AC 时本常量需一并更新，这是刻意的摩擦：条数变化必须被显式确认。
EXPECTED_AC_COUNT = 249


def assert_count_matches_doc(path: Path, parsed_count: int):
    """兜底：解析条数必须等于 EXPECTED_AC_COUNT。

    2026-10-03 静默漏条事故中，ROW_RE 无法匹配 `| **AC-GATE-14** 🆕 |` 形式的行，
    12 条 AC 被整行丢弃且无任何提示，丢完恰好等于文档标题声明的 237，完全隐形。
    本函数是第二道防线：即便 ROW_RE 被改坏，「解析数 ≠ 249」也会立刻失败。
    另附带对账文档标题声明数，仅作提示（该标题当前过期，待勘误）。
    """
    if not path.exists():
        sys.stderr.write(f"[ERROR] catalog 不存在: {path}\n")
        sys.exit(1)
    if parsed_count != EXPECTED_AC_COUNT:
        sys.stderr.write(
            f"[FATAL] 解析出 {parsed_count} 条，与已核实刻度 {EXPECTED_AC_COUNT} 条不符 —— "
            f"要么 ROW_RE 漏匹配了某些行格式，要么清单确实增删了 AC。\n"
            f"        若确认是清单变更，请同步更新脚本里的 EXPECTED_AC_COUNT 常量。\n"
        )
        sys.exit(2)
    declared = None
    for line in path.read_text(encoding="utf-8").splitlines()[:20]:
        m = DOC_COUNT_RE.search(line)
        if m:
            declared = int(m.group(1))
            break
    if declared is not None and declared != parsed_count:
        print(
            f"[WARN] 文档标题仍声明 {declared} 条，实际 {parsed_count} 条 —— "
            f"标题数字过期，属勘误级问题，请在 docs/ipd-系统说明/log.md 登记后修正。"
        )
    print(f"[OK] 解析条数与已核实刻度一致: {parsed_count}")


def load_matrix(path: Path):
    if not path.exists():
        return {"rows": []}
    return json.loads(path.read_text(encoding="utf-8"))


def build_draft(parsed, existing_matrix):
    """构造 draft JSON：保留矩阵已有行 + 解析出的 catalog 行（按 ac_id 去重合并）"""
    by_id = {}
    for row in existing_matrix.get("rows", []):
        by_id[row["ac_id"]] = row
    added = updated = unchanged = 0
    for r in parsed:
        ac = r["ac_id"]
        if ac in by_id:
            # 已存在 → 若 title 为空则补，否则保留
            existing = by_id[ac]
            if not existing.get("title"):
                existing["title"] = r["title"]
                updated += 1
            else:
                unchanged += 1
        else:
            category = ac.split("-")[1]
            by_id[ac] = {
                "ac_id": ac,
                "category": category,
                "title": r["title"],
                "docRef": "docs/ipd-系统说明/外部资源/IPD系统_验收清单.md",
                "unitTestClass": "",
                "integrationTestPath": "",
                "auditLog": "",
                "status": "manual",
                "owner": "rd",
                "linkedCommits": [],
                "zkRef": None,
                "notes": "OD-AM-02 半自动导入，unitTestClass/auditLog 待 owner 补",
            }
            added += 1
    return {
        "rows": list(by_id.values()),
        "_stats": {
            "catalog_parsed": len(parsed),
            "draft_total": len(by_id),
            "added": added,
            "updated": updated,
            "unchanged": unchanged,
            "ts": datetime.now().astimezone().isoformat(timespec="seconds"),
        },
    }


def write_report(parsed, draft, out_path: Path):
    lines = []
    lines.append("=== OD-AM-02 ac-import.py 导入报告 ===")
    lines.append(f"时间: {datetime.now().astimezone().isoformat(timespec='seconds')}")
    lines.append(f"catalog 解析条数: {len(parsed)}")
    lines.append(f"draft 总条数:     {draft['_stats']['draft_total']}")
    lines.append(f"  新增: {draft['_stats']['added']}")
    lines.append(f"  标题补全: {draft['_stats']['updated']}")
    lines.append(f"  保持不变: {draft['_stats']['unchanged']}")
    lines.append("")
    by_cat = {}
    for r in draft["rows"]:
        c = r.get("category") or r["ac_id"].split("-")[1]
        by_cat.setdefault(c, []).append(r["ac_id"])
    lines.append("=== 按 category 分布 ===")
    for c in sorted(by_cat):
        lines.append(f"  {c:8s} {len(by_cat[c]):4d}")
    lines.append("")
    missing_in_matrix = [r["ac_id"] for r in parsed
                         if r["ac_id"] not in {x["ac_id"] for x in draft["rows"]}]
    if missing_in_matrix:
        lines.append(f"=== ⚠️  catalog 有但 draft 缺 {len(missing_in_matrix)} 条 ===")
        lines.extend(f"  {x}" for x in missing_in_matrix[:50])
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description="OD-AM-02 半自动 AC 批量导入")
    p.add_argument("--write", action="store_true", help="写 draft 文件（默认 dry-run）")
    p.add_argument("--report", action="store_true", help="同时写 diff 报告")
    args = p.parse_args()

    parsed = parse_catalog(CATALOG_PATH)
    if not parsed:
        sys.stderr.write("[ERROR] catalog 解析到 0 行，请检查 ROW_RE 模式\n")
        sys.exit(2)
    assert_count_matches_doc(CATALOG_PATH, len(parsed))

    matrix = load_matrix(MATRIX_PATH)
    draft = build_draft(parsed, matrix)

    print(f"catalog 解析: {len(parsed)} 条")
    print(f"draft 总计:   {draft['_stats']['draft_total']} 条")
    print(f"  新增: {draft['_stats']['added']}")
    print(f"  标题补全: {draft['_stats']['updated']}")
    print(f"  保持不变: {draft['_stats']['unchanged']}")

    if args.write:
        DRAFT_PATH.write_text(
            json.dumps(draft, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"[WRITE] draft 写到 {DRAFT_PATH.relative_to(REPO_ROOT)}")
    else:
        print("[DRY-RUN] 未写盘，加 --write 写入")

    if args.report or args.write:
        report = write_report(parsed, draft, REPORT_PATH)
        print(f"[REPORT] 写到 {REPORT_PATH.relative_to(REPO_ROOT)}")
        print("-" * 60)
        print(report)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(3)
