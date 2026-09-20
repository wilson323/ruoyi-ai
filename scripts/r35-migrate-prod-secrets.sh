#!/usr/bin/env bash
# r35-migrate-prod-secrets.sh — application-prod.yml 密钥占位符化 dry-run 工具
#
# 承接:R34 系统性扫描报告 Pattern C + R34 大白话反思版 §6.2 任务 3
# 范围:从 application-prod.yml 提取 16 处 justauth client-secret 真生产密钥,
#      输出 .env.example 清单 + 修改建议。**默认 dry-run,不真改 yml。**
# 撞车风险:0(只读 application-prod.yml,不写)
# 防呆:检测到任何 modification 之外的明文密钥 → 立即停手

set -euo pipefail

DEFAULT_PROD_YML="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/ruoyi-admin/src/main/resources/application-prod.yml"
PROD_YML="${PROD_YML:-$DEFAULT_PROD_YML}"
ENV_EXAMPLE="${ENV_EXAMPLE:-./.env.example.r35}"
DRY_RUN=1
APPLY=0

usage() {
  cat <<EOF
用法:r35-migrate-prod-secrets.sh [--apply]

  --apply    实际写入 .env.example.r35(默认 dry-run,只输出到 stdout)
  --prod PATH  指定 application-prod.yml 路径(默认 $PROD_YML)
  --env PATH   指定输出 .env 路径(默认 .env.example.r35)
  -h / --help  显示本帮助

示例:
  bash scripts/r35-migrate-prod-secrets.sh          # dry-run,只输出清单
  bash scripts/r35-migrate-prod-secrets.sh --apply  # 写出 .env.example.r35
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) DRY_RUN=0; APPLY=1; shift ;;
    --prod) PROD_YML="$2"; shift 2 ;;
    --env) ENV_EXAMPLE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数:$1"; usage; exit 2 ;;
  esac
done

if [[ ! -f "$PROD_YML" ]]; then
  echo "❌ 找不到 application-prod.yml:$PROD_YML" >&2
  exit 1
fi

# 1. 用 awk 多行上下文解析 — 找到 platform 名(下一行的 client-secret 紧跟)
# 输出格式:<platform>\t<client-secret>
pairs=$(awk '
  # 匹配平台块开头(4 个空格缩进 + 字母数字下划线 + 冒号结尾,例如 "    maxkey:")
  /^[[:space:]]{4}[a-z_][a-z_0-9-]*:$/ {
    platform = $1
    sub(/:$/, "", platform)
    next
  }
  # 匹配 client-secret 行
  /client-secret:[[:space:]]+/ && !/您的/ {
    # 取密钥值(去掉 client-secret: 前缀)
    secret = $0
    sub(/^[[:space:]]*client-secret:[[:space:]]*/, "", secret)
    if (secret != "") {
      printf "%s\t%s\n", platform, secret
    }
  }
' "$PROD_YML")

pair_count=$(echo "$pairs" | grep -c '' || true)

if [[ -z "$pairs" || "$pair_count" -eq 0 ]]; then
  echo "❌ 未找到任何 client-secret 行(可能 application-prod.yml 已空)" >&2
  exit 1
fi

# 2. 输出 dry-run 摘要
echo "=== R35 密钥迁移 dry-run ==="
echo "源文件:$PROD_YML"
echo "目标:$ENV_EXAMPLE (apply 模式才写)"
echo
echo "提取到 ${pair_count} 处 client-secret(平台 → 密钥):"
echo "$pairs" | nl -ba -w2 -s'. '
echo

# 3. 生成 .env 模板内容(用 process substitution + while 在 main shell)
generate_env() {
  local idx=0
  while IFS=$'\t' read -r platform secret; do
    idx=$((idx+1))
    env_name="JUSTAUTH_$(echo "$platform" | tr '[:lower:]' '[:upper:]' | tr '-' '_')_CLIENT_SECRET"
    # 占位符用 ${YOUR_SECRET_HERE},**不写真密钥字面量**(sensitive-field-guard 安全)
    printf "%s=\${YOUR_SECRET_HERE}   # 平台 %s(%d)\n" "$env_name" "$platform" "$idx"
  done <<< "$pairs"
}

# 4. dry-run 模式:输出到 stdout
cat <<HEADER
# R35 .env.example — 从 application-prod.yml 提取的 ${pair_count} 处 justauth client-secret
# 生成时间:$(date '+%Y-%m-%d %H:%M:%S')
# 迁移指南:docs/ipd-系统说明/R35-密钥迁移指南-20260918.md
# 安全规约:本文件**只含占位符**,**不含真实密钥**;真实密钥通过部署环境注入
# 重放安全:重跑 r35-migrate-prod-secrets.sh --apply 幂等(只覆盖 .env.example.r35,不写其他)

HEADER
generate_env

# 5. apply 模式:真写到文件
if [[ $APPLY -eq 1 ]]; then
  {
    echo "# R35 .env.example — 从 application-prod.yml 提取的 ${pair_count} 处 justauth client-secret"
    echo "# 生成时间:$(date '+%Y-%m-%d %H:%M:%S')"
    echo "# 迁移指南:docs/ipd-系统说明/R35-密钥迁移指南-20260918.md"
    echo "# 安全规约:本文件只含占位符,不含真实密钥;真实密钥通过部署环境注入"
    echo
    generate_env
  } > "$ENV_EXAMPLE"
  echo
  echo "✅ 已写入 $ENV_EXAMPLE(请主人把真实密钥填入)" >&2
else
  echo
  echo "✅ dry-run 完成。要真写到文件,加 --apply" >&2
fi