#!/usr/bin/env bash
# .harness/loop.sh — GEP 自进化循环驱动(8 阶段闭环)
#
# 用法:
#   bash .harness/loop.sh              # 跑一轮完整 8 阶段
#   bash .harness/loop.sh --dry-run    # 只打印不执行
#
# 8 阶段闭环(R25+R40 教训沉淀):
#   1. Review    读现状(五必现查 + git status + 看板 manage.py list)
#   2. Ideate    出方案(PM + Evolver 双视角)
#   3. Modify    改文件(单 mutation,L1 自动 / L2 人工审 / L3 owner)
#   4. Commit    落 commit(自动 commit 不问,R11 红线遵守)
#   5. Verify    自证能红(跑 verify.sh 拿真证据)
#   6. Gate      门禁检查(跑 gate.sh,R30+ 三层哨兵 + 负向验证)
#   7. Log       记录失败(.harness/evolve/failures.jsonl append)
#   8. Loop      回到阶段 1(等 N 秒或外部 trigger)
#
# 纪律:
#   - 任何阶段失败立即停,先记录到 failures.jsonl 再决定是否重试
#   - 单 mutation 原则(改一个文件或一类变更,不要捎带)
#   - 五必现查前置:hash / 端口 / 段号 / 看板回读 / 跨仓 cd
#   - Layer 分层:L1 自动 commit / L2 需人工审 / L3 owner 决策
set -uo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

log() { echo "[$(date '+%F %T')] $*"; }
run() {
    local cmd="$1"
    if $DRY_RUN; then
        echo "  [DRY] $cmd"
    else
        eval "$cmd"
    fi
}

log "════════ GEP LOOP START ════════"

# 1. Review(五必现查 + 现状快照)
log "[1/8] Review — 五必现查 + 现状快照"
run "git log --oneline -3"
run "git status --short | head -10"
run "python3 docs/ipd-系统说明/vibe-kanban/manage.py list 2>/dev/null | python3 -c 'import json,sys; tasks=json.load(sys.stdin); buckets={}; [buckets.setdefault(t.get(\"status\",\"?\"),[]).append(t) for t in tasks]; print({k:len(v) for k,v in buckets.items()})' 2>/dev/null || echo 'manage.py list 失败(跳过)'"

# 2. Ideate(双视角 — 由 sub-agent 并发执行,本脚本只占位)
log "[2/8] Ideate — PM + Evolver 双视角(占位)"
log "  → 本阶段需 sub-agent(pm + evolver)并发执行,本脚本不实现"

# 3. Modify(单 mutation,L1 自动 / L2 人工审 / L3 owner)
log "[3/8] Modify — 单 mutation"
log "  → 由 Modify 阶段决定改哪些文件,本脚本不预设"

# 4. Commit(自动 commit 不问)
log "[4/8] Commit — 自动 commit + 三件套同步"
log "  → git add + git commit + git push(若网络可达)"

# 5. Verify(自证能红)
log "[5/8] Verify — 跑 verify.sh 拿真证据"
if [[ -x .harness/verify.sh ]]; then
    if $DRY_RUN; then
        echo "  [DRY] bash .harness/verify.sh"
    else
        bash .harness/verify.sh 2>&1 | tail -10 || {
            log "✗ verify.sh FAIL — 记录到 failures.jsonl"
            echo "{\"stage\":\"verify\",\"ts\":\"$(date -u +%FT%TZ)\",\"exit\":$?}" >> .harness/evolve/failures.jsonl
            exit 1
        }
    fi
fi

# 6. Gate(门禁检查)
log "[6/8] Gate — 跑 gate.sh(R30+ 三层哨兵)"
if [[ -x .harness/gate.sh ]]; then
    if $DRY_RUN; then
        echo "  [DRY] bash .harness/gate.sh"
    else
        bash .harness/gate.sh 2>&1 | tail -10 || {
            log "✗ gate.sh FAIL — 记录到 failures.jsonl"
            echo "{\"stage\":\"gate\",\"ts\":\"$(date -u +%FT%TZ)\",\"exit\":$?}" >> .harness/evolve/failures.jsonl
            exit 1
        }
    fi
fi

# 7. Log(记录本轮结果)
log "[7/8] Log — 记录本轮结果"
mkdir -p .harness/evolve
echo "{\"stage\":\"loop_complete\",\"ts\":\"$(date -u +%FT%TZ)\",\"ok\":true}" >> .harness/evolve/failures.jsonl 2>/dev/null || true

# 8. Loop(本轮结束,等下一轮触发)
log "[8/8] Loop — 本轮结束,等下一轮 trigger"
log "════════ GEP LOOP COMPLETE ════════"
exit 0