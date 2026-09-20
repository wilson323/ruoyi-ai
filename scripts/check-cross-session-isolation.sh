#!/usr/bin/env bash
# scripts/check-cross-session-isolation.sh — 跨会话身份隔离门禁（骨架）
# 来源：R143 子任务 R143.1 + R142 M-Root-9 跨会话身份隔离盲区
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：CSI_FAIL_SEED=1 → exit 1（FAIL_SEED 双向触发标配）

set -uo pipefail

CSI_FAIL_SEED="${CSI_FAIL_SEED:-0}"
if [ "$CSI_FAIL_SEED" = "1" ]; then
  echo "[CSI] FAIL_SEED=1 → 故意注入跨会话身份隔离失败"
  echo "CSI|FAIL|seed_injected|cross-session-isolation-breach"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 检查 .git/index.lock 是否存在（兄弟会话 commit 中）
# 2. 检查 .harness/memory/ 是否被 2+ 个会话并发写
# 3. 检查 BCP-Registry.md / BCP-Closure-Log.md 是否被外部改动（mtime 5min 内）
# 4. 输出跨会话身份隔离报告
echo "[CSI] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
