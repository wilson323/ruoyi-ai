#!/usr/bin/env bash
# DEPRECATED R231：意图不成立，已从门禁名册摘除（看板 8500d227 待拍2，owner 拍板「按建议执行」）
# scripts/check-cross-repo-cd-guard.sh — M4 跨仓 cd 强校验（原设计：pre-commit hook 候选）
# 来源：R131 §二.2.2 M4 + R131 §三.6 D6 + R131 §二.2.3 指针 #132
# 撞车 0 让路：✅ scripts/ 白名单
#
# 废弃原因（意图在结构上无法满足，非判据可调通）：
#   1) 观测对象不可达：本门禁检查 ~/.zsh_history 里的跨仓相对路径 cd，但智能体会话的
#      cd 全部经工具执行（每次调用独立 shell，不落 zsh_history），真阳性永远抓不到；
#      能"抓到"的只有人类手敲终端，而撞车 0 红线约束的主体恰恰是智能体 → 检查与目标
#      行为结构性错位，改成任何判据都无法命中原意图。
#   2) set -eo pipefail 下 grep 无匹配 → 管道非 0 → set -e 直接终止（实测永远 rc=1，
#      "✅通过"永不可达 = 假红）；history 缺失分支又 exit 0 = 假绿。同一脚本同时假红+假绿。
#   结论：按 R231 二选一规则，意图不成立 → 文档化废弃，保留文件不静默 rm。
#   防线替代：跨仓 cd 纪律实际由「撞车 0 让路」人工规约 + 会话工具层绝对路径约束保障
#   （见 UNIFIED-RULES 撞车 0 段）；若未来要让"staged diff 里的相对 cd"能红，应按
#   R134 另立全新门禁（自带实跑证据），而非复活本脚本。
# 行为：保留可执行入口 = 显式宣告废弃并放行（exit 0），供旧接线平滑过渡；
#       check-m1m5-landed.sh 依本头部 "DEPRECATED R231" 标记将其归为「已废弃」不计死件。
# 退出码词表：0=已废弃（no-op 放行）

set -uo pipefail

main() {
  echo "[M4] check-cross-repo-cd-guard.sh — DEPRECATED R231"
  echo "⚠️ 本门禁已文档化废弃：检查对象（~/.zsh_history 中智能体 cd）结构上不可观测，"
  echo "   旧实现在 set -e 下永远假红、history 缺失假绿，给不出正确结论。"
  echo "   已从门禁名册摘除（见 check-m1m5-landed.sh 的 DEPRECATED 登记）。exit 0 = no-op 放行。"
  exit 0
}

main "$@"
