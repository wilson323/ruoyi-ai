#!/bin/bash
# =============================================================================
# IPD 产品经理管理系统 — 生产环境启动脚本
# =============================================================================
# 选型说明（JVM GC 与堆参数）：
#   - 垃圾回收器：G1GC（Garbage-First）
#     * 选择原因：业务节奏以 200ms 为最大停顿目标（MaxGCPauseMillis=200），
#       适配 ruoyi-admin 的 IPD 工作流 / KPI / 奖金池核算等中延迟场景；
#       G1 在 2-8GB 堆区间综合吞吐与延迟最优，且支持 region 化预测模型。
#   - 备选方案（owner 决策触发）：
#       1) ZGC：当堆 > 32GB 或对停顿 < 10ms 有强需求时切换，命令：
#            -XX:+UseZGC -XX:+ZGenerational
#       2) ParallelGC：批处理 / 离线计算场景，吞吐优先，停顿可放宽至秒级。
#   - 堆大小：Xms=Xmx 固定 2GB-4GB 区间（与 application-prod.yml hikariCP
#              maxPoolSize=80 + 平均连接持有 < 1s 的工作负载对齐）。
#   - OOM 兜底：HeapDumpOnOutOfMemoryError + HeapDumpPath=./logs/，
#              OOM 时直接落盘 hprof，便于事后 MAT 分析内存泄漏根因。
#              ⚠️ 仅 dev profile 启用 OOM dump；prod profile 依赖监控告警（参见 OPS-06），
#              避免 hprof 泄露应用内存快照（含潜在敏感数据）。
# =============================================================================
# 使用方式：
#   1) cd <ruoyi-ai 部署根目录>  （必须含 ruoyi-admin.jar）
#   2) SPRING_PROFILES_ACTIVE=prod bash scripts/start.sh
#   3) SIGTERM 优雅退出；OOM 会自动写 ./logs/java_*.hprof 后退出非零
# =============================================================================

set -euo pipefail

# 颜色输出（容器环境无 tty 时退化为纯文本）
if [ -t 1 ]; then
  C_RED='\033[0;31m'; C_YEL='\033[1;33m'; C_GRN='\033[0;32m'; C_RST='\033[0m'
else
  C_RED=''; C_YEL=''; C_GRN=''; C_RST=''
fi

# 部署前轻量校验
if [ ! -f "./ruoyi-admin.jar" ]; then
  echo -e "${C_RED}[FATAL] 未发现 ruoyi-admin.jar，请先 mvn package${C_RST}" >&2
  exit 1
fi

# === 安全加固（fix security review） ===
# 1. umask 077 —— mkdir 出来的 dir 默认权限 0700，防止 dump / log 被他账号读
umask 077

# 2. 防御 symlink 攻击：若 ./logs 是符号链接，拒绝启动（防止 log/dump 写到攻击者控制路径）
if [ -L ./logs ]; then
  echo -e "${C_RED}[FATAL] ./logs 是符号链接，存在 path-traversal 风险，拒绝启动${C_RST}" >&2
  exit 70
fi

# 3. install -d -m 0700 等价于 mkdir -p 且强制权限 0700（覆盖已存在 dir 的权限）
install -d -m 0700 ./logs

# 4. 磁盘空间守卫 —— OOM dump 单文件最大可至 4GB（Xmx=4g），需预留 ≥ 8GB 避免填满磁盘
REQUIRED_FREE_MB=8192
AVAIL_FREE_MB=$(df -m ./logs 2>/dev/null | awk 'NR==2 {print $4}')
if [ -z "${AVAIL_FREE_MB:-}" ] || [ "${AVAIL_FREE_MB:-0}" -lt "${REQUIRED_FREE_MB}" ]; then
  echo -e "${C_RED}[FATAL] ./logs 可用空间不足 ${REQUIRED_FREE_MB}MB（当前 ${AVAIL_FREE_MB:-未知}MB），OOM dump 可能填满磁盘${C_RST}" >&2
  exit 71
fi

# 5. hprof 清理策略 —— 仅保留最近 3 个 dump（最大 12GB 上限），避免累积
#    prod 环境应配套外部 logrotate / 监控告警（参见 OPS-06）
find ./logs -maxdepth 1 -name 'java_*.hprof' -type f -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn \
  | tail -n +4 \
  | awk '{print $2}' \
  | while read -r OLD_DUMP; do
      echo -e "${C_YEL}[WARN] 删除过期 OOM dump: ${OLD_DUMP}${C_RST}" >&2
      rm -f "${OLD_DUMP}"
    done

# 6. profile 判定 —— prod profile 不写 hprof（避免泄露内存快照）
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
OOM_FLAGS=""
if [ "${SPRING_PROFILES_ACTIVE}" != "prod" ]; then
  OOM_FLAGS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/"
fi

# 7. env -i 最小化环境变量 —— 仅透传 PATH / JAVA_HOME / LANG / TZ / Spring profile
#    避免 JVM process env 泄露生产凭证（如数据库密码 / API Key / OAuth Secret）
#    Spring profile 仍可通过命令行或 SPRING_PROFILES_ACTIVE 注入
#    [2026-10-03 修复] 三处赋值之间必须用「空格」分隔：env -i 把每个 NAME=value 当作独立参数，
#    原先用冒号拼接会被整体解析成「单一」赋值——PATH 被污染成
#    ".../bin:JAVA_HOME=/x:LANG=...:SPRING_PROFILES_ACTIVE=prod"，而 JAVA_HOME / LANG / TZ /
#    SPRING_PROFILES_ACTIVE 四个变量一个都没进 JVM。实测后果：application.yml 的
#    ${SPRING_PROFILES_ACTIVE:dev} 回落到 dev，prod 档的 fail-fast 守卫
#    （ProdConfigFailFastRunner，@Profile("prod")）永远不会被触发，等于关掉了生产配置校验。
PRESERVED_ENV="PATH=${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
[ -n "${JAVA_HOME:-}" ] && PRESERVED_ENV="${PRESERVED_ENV} JAVA_HOME=${JAVA_HOME}"
PRESERVED_ENV="${PRESERVED_ENV} LANG=${LANG:-C.UTF-8} TZ=${TZ:-UTC} SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}"

# 8. prod 凭证守门（2026-10-03 补，P0）——「静默降级」提前变成「明确报错退出」
#    现场：env -i 不白名单任何凭证，而 application-prod.yml 的 4 个凭证占位符都写成 ${VAR:}
#    （空默认值，形似必填实际从不失败）。缺失时的表现不是启动报错，而是：服务照常启动、
#    照常连上本机 3306 的兜底库，只有 JWT 签名密钥为空——零报错。
#    注意分寸：这里只做「存在性检查」，绝不把凭证加进 PRESERVED_ENV —— env -i 的本意正是
#    不让生产凭证出现在 JVM 的进程环境里；凭证应由编排层（docker compose environment /
#    systemd EnvironmentFile）在调用本脚本之前注入。
#    权威判定仍在应用侧 ProdConfigFailFastRunner：它读 Spring 已解析属性，能覆盖
#    env / JVM -D / 外部配置文件三条注入路径，本段只为把失败提前到 exec 之前并给出可照做的提示。
if [ "${IPD_SKIP_PROD_CREDENTIAL_CHECK:-0}" != "1" ]; then
  IS_PROD_PROFILE=0
  # 大小写敏感、逗号分隔精确匹配：prod / prod,dev 命中；production 不命中
  case ",${SPRING_PROFILES_ACTIVE}," in
    *",prod,"*) IS_PROD_PROFILE=1 ;;
  esac
  if [ "${IS_PROD_PROFILE}" = "1" ]; then
    MISSING_PROD_VARS=""
    for CRED_VAR in SA_TOKEN_JWT_SECRET_KEY \
                    SPRING_DATASOURCE_USERNAME \
                    SPRING_DATASOURCE_PASSWORD \
                    SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL; do
      if [ -z "${!CRED_VAR:-}" ]; then
        MISSING_PROD_VARS="${MISSING_PROD_VARS} ${CRED_VAR}"
      fi
    done
    if [ -n "${MISSING_PROD_VARS}" ]; then
      echo -e "${C_RED}[FATAL] prod profile 缺少必需环境变量（缺任一都会静默降级，故直接拒绝启动）:${MISSING_PROD_VARS}${C_RST}" >&2
      echo -e "${C_RED}        变量清单与部署通道见 docs/ipd-系统说明/治理/生产部署Runbook-20260909.md 第三节「应用发布」${C_RST}" >&2
      echo -e "${C_RED}        若凭证改由外部 Spring 配置传入（--spring.config.additional-location=...），设 IPD_SKIP_PROD_CREDENTIAL_CHECK=1 跳过本段${C_RST}" >&2
      exit 78
    fi
  fi
fi

echo -e "${C_GRN}[INFO]$(date '+%F %T') 启动 ruoyi-admin.jar（G1GC / 2-4GB / profile=${SPRING_PROFILES_ACTIVE} / OOM=${OOM_FLAGS:+ENABLED}${OOM_FLAGS:-DISABLED}）${C_RST}"

# exec 替换当前进程，让 SIGTERM 直接转发到 JVM（不再 fork 子 shell）
# env -i 注入最小环境变量集，避免父进程 env 泄露给 JVM
exec env -i ${PRESERVED_ENV} \
  java \
    -Xms2g -Xmx4g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    ${OOM_FLAGS} \
    -jar ruoyi-admin.jar \
    "$@"