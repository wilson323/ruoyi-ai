#!/usr/bin/env bash
# scripts/check-write-endpoint-ownership.sh — 资源归属校验门禁的 shell 入口
#
# 为什么要一层壳：门禁要接进 .claude/hooks/check-pre-commit.sh 与 CI，
# 那两处只调 `bash <脚本>`。判定逻辑全在同目录的 Python 里（需要按方法体
# 配平大括号、按字段声明类型做一跳服务解析，shell 做不了）。
#
# 公共排除函数在 scripts/lib/audit-gate-input.sh 的 gate_source_files，
# 下一写门禁的人直接复用它，不要自己写 find 条件。
#
# 用法：
#   bash scripts/check-write-endpoint-ownership.sh            # 正常判定
#   bash scripts/check-write-endpoint-ownership.sh --list     # 列出待分类端点
#   bash scripts/check-write-endpoint-ownership.sh --self-red # 自证能红（双向）
#
#   OWNERSHIP_EXEMPT_FILE=/path/to/other.txt bash scripts/check-write-endpoint-ownership.sh
#     换一份豁免清单跑同一套判定。**只为自证能红服务**（验空清单/缺清单/缺理由/
#     僵尸豁免/删基线这 5 种状态）；日常判定与 CI 不要设这个变量。
#
# 归属判据：断言入口从 org/ruoyi/ipd/security 源码按「assert/require 系前缀 +
# 参数含资源标识」自动发现，再沿调用图下钻（端点体 → 本类私有方法 → Service
# 一跳 → Service 内私有方法）。**不依赖固定方法名清单** —— 校验封进私有 helper
# 也看得见，见 .py 里「结构性判据」段的来龙去脉。
#
# 退出码：0=PASS  1=有违规  2=门禁自身错误（输入读不到/清单缺失/清单为空/判据面为空）
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${PYTHON:-python3}"
GATE="$REPO_ROOT/scripts/check-write-endpoint-ownership.py"

if ! command -v "$PY" >/dev/null 2>&1; then
  echo "[ownership-gate] python3 不可用，无法运行门禁" >&2
  exit 2
fi
if [ ! -f "$GATE" ]; then
  echo "[ownership-gate] 判定脚本不存在: $GATE" >&2
  exit 2
fi

# --self-red 自带超时保护：它要复制整棵模块源码，慢机器上别无限等
if [ "${1:-}" = "--self-red" ]; then
  exec perl -e 'alarm 300; exec @ARGV' -- "$PY" "$GATE" --self-red
fi

# 正常判定同样带超时（macOS 无 timeout 命令，用 perl alarm）
exec perl -e 'alarm 240; exec @ARGV' -- "$PY" "$GATE" "$@"
