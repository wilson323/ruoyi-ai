#!/bin/bash
# R174-P1.5 OPS-06 监控凭证脚本
# 用途：从 application-ipd-local.yml 真实读出 spring.boot.admin.client.username/password，
#   输出 Prometheus / Spring Boot Admin / Actuator 抓取用的 basic auth 凭证。
# 避免明文把账号密码固化到 Prom scrape config / 监控部署 / 文档中。
#
# 用法：
#   eval "$(./scripts/ops/actuator-credentials.sh)"   # 导出 MON_USER / MON_PASS 环境变量
#   curl -u "$MON_USER:$MON_PASS" http://127.0.0.1:16039/actuator/prometheus
#
# 默认按以下顺序查找 ipd-local 配置文件（第一份存在者为准）：
#   1. $1 传入路径（推荐用于自定义配置）
#   2. /private/tmp/<worktree>/.codex/ipd-dev/config/application-ipd-local.yml
#   3. <git-root>/.codex/ipd-dev/config/application-ipd-local.yml
#   4. $PWD/../.codex/ipd-dev/config/application-ipd-local.yml

set -e

if [[ -n "$1" ]]; then
    CONFIG_FILE="$1"
else
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    GIT_ROOT="$(cd "$SCRIPT_DIR/../.." && git rev-parse --show-toplevel 2>/dev/null || echo "")"
    for cand in \
        "/private/tmp/r172-takeover/.codex/ipd-dev/config/application-ipd-local.yml" \
        "/private/tmp/r174-p03-platform-token-20260922/.codex/ipd-dev/config/application-ipd-local.yml" \
        "${GIT_ROOT}/.codex/ipd-dev/config/application-ipd-local.yml" \
        "$PWD/.codex/ipd-dev/config/application-ipd-local.yml" \
        "$PWD/../.codex/ipd-dev/config/application-ipd-local.yml"; do
        if [[ -f "$cand" ]]; then
            CONFIG_FILE="$cand"
            break
        fi
    done
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "ERROR: config file not found: $CONFIG_FILE" >&2
    exit 1
fi

# 用 Python YAML 解析（避免 awk 解析 YAML 嵌套/注释的脆弱性）
# 支持 multi-document YAML（Spring profile 文件以 --- 分隔多段）
# 支持嵌套 key（spring.boot.admin.client）→ 用点号路径递归查找
read MON_USER MON_PASS < <(python3 -c "
import sys, yaml
def get(d, path):
    for k in path.split('.'):
        if not isinstance(d, dict) or k not in d:
            return None
        d = d[k]
    return d
with open('$CONFIG_FILE') as f:
    docs = list(yaml.safe_load_all(f))
cfg = {}
for d in docs:
    if isinstance(d, dict):
        cfg.update(d)
admin = get(cfg, 'spring.boot.admin.client') or {}
print(admin.get('username', ''), admin.get('password', ''))
")

if [[ -z "$MON_USER" || -z "$MON_PASS" ]]; then
    echo "ERROR: spring.boot.admin.client.username/password not found in $CONFIG_FILE" >&2
    exit 2
fi

# 输出可被 eval 的 shell 变量赋值（不打印真实密码）
if [[ "${ACTUATOR_CREDENTIALS_DEBUG:-0}" == "1" ]]; then
    echo "MON_USER='$MON_USER'"
    echo "MON_PASS='$MON_PASS'"
else
    echo "# actuator basic-auth credentials (from $CONFIG_FILE)"
    echo "# MON_USER=$MON_USER  MON_PASS=<hidden>  set ACTUATOR_CREDENTIALS_DEBUG=1 to reveal"
    echo "export MON_USER='$MON_USER'"
    echo "export MON_PASS='$MON_PASS'"
fi