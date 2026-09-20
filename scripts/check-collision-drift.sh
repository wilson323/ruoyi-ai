#!/usr/bin/env bash
# scripts/check-collision-drift.sh — 撞车 0 让路边界漂移预警门禁（骨架）
# 来源：R143 子任务 R143.2 + R15/R46/R49/R138 撞车复发教训
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：CDRIFT_FAIL_SEED=1 → exit 1（FAIL_SEED 双向触发标配）

set -uo pipefail

CDRIFT_FAIL_SEED="${CDRIFT_FAIL_SEED:-0}"
if [ "$CDRIFT_FAIL_SEED" = "1" ]; then
  echo "[CDRIFT] FAIL_SEED=1 → 故意注入撞车 0 让路边界漂移"
  echo "CDRIFT|FAIL|seed_injected|collision-drift-detected"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 扫 git status --porcelain 是否有兄弟会话 modified（reports/ 2 文件）
# 2. 扫 working tree 是否动 Java 源码 / SQL / yml（撞车 0 红线）
# 3. 扫端口/PID 是否被抢/杀（16039/23306/8080/15666）
# 4. 输出撞车 0 让路边界报告
echo "[CDRIFT] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
