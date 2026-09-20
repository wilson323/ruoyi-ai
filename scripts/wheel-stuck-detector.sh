#!/usr/bin/env bash
# scripts/wheel-stuck-detector.sh — H-13 飞轮转速监控脚本（骨架）
# 来源：R131 §四.4.3 飞轮转速监控
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：TODO (R132 落档时故意注入超 48h 卡 → exit 1)

set -euo pipefail

# === 配置区 ===
WHEEL_REGISTRY="${WHEEL_REGISTRY:-docs/ipd-系统说明/BCP-Registry.md}"
STUCK_THRESHOLD_HOURS="${STUCK_THRESHOLD_HOURS:-48}"
LOG_PATH="docs/ipd-系统说明/log.md"

# === 主逻辑 ===
main() {
  echo "[H-13] wheel-stuck-detector.sh 启动 (基线: R131 acdbaac3)"

  # 1. 读 BCP-Registry（飞轮 SSOT 登记位）
  if [ ! -f "$WHEEL_REGISTRY" ]; then
    echo "❌ 飞轮 SSOT 不存在: $WHEEL_REGISTRY"
    echo "   提示：R132 派单需先创建 docs/ipd-系统说明/BCP-Registry.md"
    exit 0  # SSOT 缺失 = 不阻断，但提示
  fi

  # 2. 扫 BCP 卡每齿停留时间
  # TODO: 解析 BCP-Registry.md 表格，提取 BCP-ID + 状态 + 最后推进时间
  # TODO: 与当前时间对比，停留 > STUCK_THRESHOLD_HOURS 的卡 = 卡死

  # 3. 卡死升级动作
  # TODO: 看板卡标 🔴（撞车 0 让路下不实跑，TODO R132 接入 kanban MCP）
  # TODO: 自动派单 worktree（撞车 0 让路下不实跑）
  # TODO: 飞书 webhook 通知（撞车 0 让路下不实跑）

  # 4. 写 log.md T-触发记录段（docs-only 落档）
  # TODO: append "## H-13-触发-$(date +%Y%m%d-%H%M)" 到 log.md

  echo "[H-13] 飞轮转速监控骨架运行完成（无 BCP-Registry 阻断）"
  echo "      下次刷新：R132 派单落地 + 首个 BCP 卡闭环验证"
}

main "$@"
