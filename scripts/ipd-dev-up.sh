#!/bin/bash
# IPD 本地开发栈固化启动（2026-10-03 立）
#
# 为什么需要本脚本：
#   本机 IPD 开发栈（MySQL 13306 / Redis 16379 / 后端 16039）原先只有
#   .codex/ipd-dev/ 下的手工脚本，而 .codex/ 被 .gitignore:83 忽略 —— 不受版本
#   控制、新机器没有、也没有任何一处「先查再起」的统一入口。
#   后果不是「服务挂了」，而是**服务从来没被启动，而现象长得像故障**：
#   2026-10-03 一次会话就据此误判过一次（端口无监听 → 当成后端启动失败 → 去翻日志，
#   实际是当轮根本没人起过 redis）。
#
# 本脚本的契约（四条，缺一不可）：
#   1. 先探测再动作。每个组件先 lsof 实测，绝不由「上一步成功了」推断下一步可用。
#   2. 四种状态严格分开报：就绪 / 已启动 / 端口被别的进程占 / 启动失败。
#      「没启动」和「起不来」是两回事，混在一起就等于没报。
#   3. 幂等。全部就绪时第二次执行不 spawn 任何进程、不改动任何文件、EXIT=0。
#   4. 不杀进程。本脚本不执行任何 kill；端口被他物占用时只报告并停手。
#
# 用法:
#   bash scripts/ipd-dev-up.sh           # 拉起（缺什么起什么）
#   bash scripts/ipd-dev-up.sh status    # 只报状态，不做任何动作
#
# 退出码: 0=三项全部就绪  1=有组件未就绪（脚本已尽力，看输出里的下一步）  2=用法错误

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_DIR="$REPO_ROOT/.codex/ipd-dev"
BASE_SERVICES="$DEV_DIR/base-services.sh"
BACKEND_SCRIPT="$DEV_DIR/start-16039.sh"
BACKEND_JAR="$REPO_ROOT/ruoyi-admin/target/ruoyi-admin.jar"
BACKEND_LOG="/tmp/ipd-16039-ws-c1.log"

MYSQL_PORT=13306
REDIS_PORT=16379
BACKEND_PORT=16039

MODE="${1:-up}"
case "$MODE" in
  up|status) ;;
  *) echo "用法: $0 [up|status]" >&2; exit 2 ;;
esac

# 角色变量：给 final_report 记录每个组件是怎么变成「就绪/不就绪」的
declare -a ROWS=()

port_pid() { # port -> pid or empty
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1
}

# 进程归属：只有命令行里带 ipd-dev 的才算「本栈的」，避免把别人的 13306 当自己的
port_owner_is_ours() { # port
  local pid; pid=$(port_pid "$1")
  [ -n "$pid" ] || return 1
  ps -p "$pid" -o command= 2>/dev/null | grep -q "ipd-dev"
}

row() { ROWS+=("$1|$2|$3"); }

# ── 阶段 0：前置条件（缺什么就直说缺什么，不伪装成「启动失败」） ──────────
MISSING=()
[ -f "$BASE_SERVICES" ]  || MISSING+=("$BASE_SERVICES")
[ -f "$BACKEND_SCRIPT" ] || MISSING+=("$BACKEND_SCRIPT")
if [ ${#MISSING[@]} -gt 0 ]; then
  echo "❌ 缺少本机 IPD 开发环境脚本（不在版本控制内，新机器需自行准备）："
  for m in "${MISSING[@]}"; do echo "   - $m"; done
  echo
  echo "   这些文件在 .gitignore:83 下（.codex/ 整目录被忽略），fresh clone 不会有。"
  echo "   这不是服务故障，是环境未就绪 —— 请先从可用机器复制 .codex/ipd-dev/ 整目录。"
  exit 1
fi

echo "IPD 本地开发栈 —— 模式: $MODE   仓库: $REPO_ROOT"
echo "──────────────────────────────────────────────────────────"

# ── 阶段 1：基础服务 MySQL/Redis（委托 base-services.sh，其 start 本身幂等）──
if [ "$MODE" = "up" ]; then
  # 先探一次，把「本来就是 UP」和「被本脚本拉起」在报告里分开
  mysql_pre=$(port_pid $MYSQL_PORT)
  redis_pre=$(port_pid $REDIS_PORT)

  if [ -n "$mysql_pre" ] && [ -n "$redis_pre" ]; then
    echo "基础服务: 已在运行，跳过启动"
  else
    echo "基础服务: 有缺失，调用 base-services.sh start"
    # base-services.sh 失败时 exit 1；此处不 set -e，改为捕获后继续把状态报全
    bash "$BASE_SERVICES" start || echo "⚠️  base-services.sh 返回非零，继续探测实际状态"
  fi
fi

# ── 阶段 1 复探：不信「启动命令跑过了」，只信端口 ────────────────────────
# 归属校验对 mysql/redis 同样必须做：若别的进程占了 13306，只判「端口在听」会报「就绪」，
# 而实际连的不是 IPD 的库 —— 这是本脚本最不该产生的一种假绿（本项目 2026-09-24 的隔离轮
# 就是为「别项目的 3306/6379 被带走」立的规矩）。
for spec in "mysql:$MYSQL_PORT:mysqld-base.log" "redis:$REDIS_PORT:redis-base.log"; do
  IFS=':' read -r name port logf <<< "$spec"
  pid=$(port_pid "$port")
  if [ -z "$pid" ]; then
    row "$name ($port)" "❌ 未就绪" "端口无监听 —— 基础服务未起。看 $DEV_DIR/logs/$logf"
  elif ! port_owner_is_ours "$port"; then
    row "$name ($port)" "⚠️ 端口被占" "pid ${pid} 命令行不含 ipd-dev，非本栈进程；本脚本不动它，也别把它的库当 IPD 的用"
  elif [ "$MODE" = "up" ] && [ "$pid" != "${mysql_pre:-}" ] && [ "$name" = "mysql" ]; then
    row "$name ($port)" "✅ 已启动" "pid ${pid}（本次拉起）"
  elif [ "$MODE" = "up" ] && [ "$pid" != "${redis_pre:-}" ] && [ "$name" = "redis" ]; then
    row "$name ($port)" "✅ 已启动" "pid ${pid}（本次拉起）"
  else
    row "$name ($port)" "✅ 就绪" "pid ${pid}（此前已在运行）"
  fi
done

# ── 阶段 2：后端 16039 ────────────────────────────────────────────────────
# start-16039.sh 本身不幂等（无端口判断，直接 nohup），重复调用会起第二个 JVM。
# 幂等性由本脚本承担：端口空闲才调它。
be_pid=$(port_pid $BACKEND_PORT)
if [ -n "$be_pid" ] && ! port_owner_is_ours $BACKEND_PORT; then
  row "后端 ($BACKEND_PORT)" "⚠️ 端口被占" "pid $be_pid 的命令行不含 ipd-dev，非本栈进程；本脚本不动它"
elif [ -n "$be_pid" ]; then
  row "后端 ($BACKEND_PORT)" "✅ 就绪" "pid ${be_pid}（此前已在运行）"
elif [ ! -f "$BACKEND_JAR" ]; then
  row "后端 ($BACKEND_PORT)" "❌ 未就绪" "jar 不存在：先跑 mvn clean package -DskipTests 生成 $BACKEND_JAR"
elif [ "$MODE" = "status" ]; then
  row "后端 ($BACKEND_PORT)" "❌ 未运行" "端口空闲（status 模式不启动）"
else
  echo "后端: 端口空闲，调用 start-16039.sh"
  bash "$BACKEND_SCRIPT" >/dev/null 2>&1
  # Spring 启动需数十秒；轮询端口，最多 90s。不用 `timeout`（macOS 无此命令）
  ready=""
  for _ in $(seq 1 90); do
    if port_pid $BACKEND_PORT >/dev/null 2>&1 && [ -n "$(port_pid $BACKEND_PORT)" ]; then ready=1; break; fi
    sleep 1
  done
  if [ -n "$ready" ]; then
    new_pid=$(port_pid $BACKEND_PORT)
    # 端口监听 ≠ 应用可用（actuator 探活才算）；探活失败时如实降级描述
    if curl -fsS --max-time 5 "http://127.0.0.1:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
      row "后端 ($BACKEND_PORT)" "✅ 已启动" "pid ${new_pid}，actuator/health 探活通过"
    else
      row "后端 ($BACKEND_PORT)" "⚠️ 半就绪" "pid $new_pid 已监听端口，但 /actuator/health 未通过；看 $BACKEND_LOG"
    fi
  else
    row "后端 ($BACKEND_PORT)" "❌ 启动失败" "90s 内未见端口监听；看 $BACKEND_LOG 尾部"
  fi
fi

# ── 阶段 3：报告 ─────────────────────────────────────────────────────────
# printf 的 %-Ns 按**字节**补位，中文标签会少排（「后端」6 字节但只占 4 列）。
# 按显示宽度补位：ASCII=1 列，UTF-8 首字节=2 列，续字节=0 列。
disp_width() {
  LC_ALL=C printf '%s' "$1" | od -An -tu1 -v | awk '{
    for (i = 1; i <= NF; i++) { b = $i; if (b < 128) w += 1; else if (b < 192) w += 0; else w += 2 }
  } END { print w + 0 }'
}
pad() { # label width
  local l="$1" t="$2" n
  n=$(( t - $(disp_width "$l") )); [ "$n" -lt 1 ] && n=1
  printf '%s%*s' "$l" "$n" ''
}

echo
echo "$(pad 组件 20)$(pad 状态 12)依据"
echo "──────────────────────────────────────────────────────────"
FAIL=0
for r in "${ROWS[@]}"; do
  IFS='|' read -r c s d <<< "$r"
  echo "$(pad "$c" 20)$(pad "$s" 12)$d"
  case "$s" in *"❌"*|*"⚠️"*) FAIL=1 ;; esac
done
echo "──────────────────────────────────────────────────────────"

if [ "$FAIL" = "0" ]; then
  echo "全部就绪。后端: http://127.0.0.1:$BACKEND_PORT"
  exit 0
fi

echo "有组件未就绪（上面每行的「依据」列写了下一步看哪里）。"
echo "提醒：MySQL/Redis 任一未起，后端即使端口在监听也会在首次读写时报错 ——"
echo "那种报错长得像应用 bug，实际是基础服务没起。先解决基础服务再看后端。"
exit 1
