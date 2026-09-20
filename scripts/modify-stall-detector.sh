#!/usr/bin/env bash
# scripts/modify-stall-detector.sh — H-14 GEP Modify-stall 检测脚本（骨架）
# 来源：R131 §二.2.3 GEP 8 阶段修复路径
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：TODO (R132 故意在 .evolver/ 放 in-progress 卡超 48h → exit 1)

set -euo pipefail

# === 配置区 ===
EVOLVER_DIR="${EVOLVER_DIR:-.evolver}"
STALL_THRESHOLD_HOURS="${STALL_THRESHOLD_HOURS:-48}"
FAILURES_JSONL="${FAILURES_JSONL:-.evolver/failures.jsonl}"

# === 主逻辑 ===
main() {
  echo "[H-14] modify-stall-detector.sh 启动 (基线: R131)"

  # 1. 扫 .evolver/ 下 in-progress 卡
  if [ ! -d "$EVOLVER_DIR" ]; then
    echo "❌ .evolver/ 目录不存在（GEP 文档形态非运行态）"
    echo "   提示：R132 派单需先在 .evolver/ 启动 in-progress 卡"
    exit 0  # 目录缺失 = 不阻断
  fi

  # 2. 检测卡超 48h 的 in-progress 卡
  # TODO: find .evolver/ -name "*.md" -mtime +2 识别超 48h 卡
  # TODO: 提取 card_id + age_hours

  # 3. 写 failures.jsonl（让 failures.jsonl 从 0 行 → 持续累加）
  if [ ! -f "$FAILURES_JSONL" ]; then
    mkdir -p "$(dirname "$FAILURES_JSONL")"
    touch "$FAILURES_JSONL"
    echo "[H-14] 创建 $FAILURES_JSONL（GEP 进入运行态第 1 步）"
  fi

  # TODO: append "{ts, type:'stall', card_id, age_hours, trace}" 到 failures.jsonl

  # 4. 触发 #130 反思链指针
  # TODO: 触发条件满足时写反脆弱指针 #130 记录到 memory

  echo "[H-14] GEP Modify-stall 检测骨架运行完成"
  echo "      failures.jsonl 现状: $(wc -l < "$FAILURES_JSONL" 2>/dev/null || echo 0) 行"
  echo "      下次刷新：R132 派单 .evolver/ 启动 in-progress 卡"
}

main "$@"
