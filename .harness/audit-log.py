#!/usr/bin/env python3
"""audit-log.py — Layer 3 审计日志钩子（Anthropic 治理机制 #5：每个动作可归因）

由 hooks/settings.json 的 PostToolUse 调用：
    python3 "$CLAUDE_PROJECT_DIR/.harness/audit-log.py" post

职责：把每次工具调用追加到 .harness/audit/<YYYY-MM-DD>.jsonl，供事后归因、复盘、审计。
设计原则：永不阻断（任何异常都静默退出 0），审计是旁路，不是闸门。

记录字段对齐 ai-approval-audit-trail Skill：
  ts / event / tool / agent_id / input_digest / cwd / session
敏感处理：只记摘要哈希，不落原文，避免把凭据写进日志。
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import sys
from datetime import datetime, timezone

MAX_FIELD = 200


def _digest(value: str) -> str:
    """只留摘要，不落原文 —— 审计日志不能成为第二个泄密点。"""
    if not value:
        return ""
    return hashlib.sha256(value.encode("utf-8", "replace")).hexdigest()[:16]


def _truncate(value: str, limit: int = MAX_FIELD) -> str:
    if not value:
        return ""
    value = str(value).replace("\n", " ")
    return value if len(value) <= limit else value[:limit] + "…"


def _project_dir() -> pathlib.Path:
    env = os.environ.get("CLAUDE_PROJECT_DIR")
    if env:
        return pathlib.Path(env)
    return pathlib.Path.cwd()


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] != "post":
        return 0

    try:
        raw = sys.stdin.read()
    except Exception:
        return 0

    payload = {}
    if raw.strip():
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"_unparsed": _truncate(raw)}

    tool_input = payload.get("tool_input", {}) or {}
    tool_name = payload.get("tool_name", "unknown")

    # 只记可归因的最小集；原文一律走哈希
    record = {
        "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "event": "tool_use",
        "tool": tool_name,
        "agent_id": os.environ.get("CLAUDE_AGENT_ID", "primary"),
        "session": _truncate(os.environ.get("CLAUDE_SESSION_ID", ""), 64),
        "cwd": _truncate(str(payload.get("cwd", "")), 120),
        "input_digest": _digest(json.dumps(tool_input, sort_keys=True, ensure_ascii=False)),
    }

    # 对文件类工具，记路径（路径本身是审计必需，且不含凭据）
    fp = tool_input.get("file_path")
    if fp:
        record["path"] = _truncate(fp, 160)
    cmd = tool_input.get("command")
    if cmd:
        # 命令记首词（动作类型），全文不落盘
        record["cmd_head"] = _truncate(str(cmd).strip().split()[0] if str(cmd).strip() else "", 40)

    project = _project_dir()
    audit_dir = project / ".harness" / "audit"
    try:
        audit_dir.mkdir(parents=True, exist_ok=True)
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        with (audit_dir / f"{day}.jsonl").open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception:
        pass  # 审计失败绝不阻断工作

    return 0


if __name__ == "__main__":
    sys.exit(main())
