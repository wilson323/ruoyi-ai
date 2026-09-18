#!/usr/bin/env bash
# R41 T4: 扫 application-prod*.yml 的 justauth 段 client-secret 内联硬编码
# ====================================================================
# 动机：R34 §2.2 报告实测 16 处 client-secret 直接写在 application-prod.yml,
#       包括微信/钉钉/支付宝/百度/CSDN 等 11 渠道明文密钥.gitleaks 通用规则
#       可能漏 justauth 专属字段,本门禁精准针对该问题.
#
# 模式：
#   default(warning):报告违规,exit 0(不阻断 PR,留 owner 决策迁移)
#   --strict         :报告违规,exit 1(阻断 PR 合入)
#   --self-test      :自证能红(检查脚本本身能 fail,门禁失效自检)
#
# 与既有 CI 的关系：仅新增,不修改 gitleaks.yml / ipd-drift-check.yml /
# r25-root-cause-lint.yml / r30-done-gate.yml.

set -euo pipefail

MODE="warning"
case "${1:-}" in
  --strict)    MODE="strict" ;;
  --self-test) MODE="self-test" ;;
  "")          MODE="warning" ;;
  *)
    echo "用法: $0 [--strict|--self-test]"
    echo "  default:  warning 模式,exit 0(报告不阻断)"
    echo "  --strict: 阻断模式,违规 exit 1"
    echo "  --self-test: 自证能红"
    exit 2
    ;;
esac

WORKSPACE="${WORKSPACE:-$(pwd)}"

# 哨兵 1:必须找到至少 1 个 application-prod*.yml(扫描路径错位自检)
PROD_FILES=$(find "$WORKSPACE" \
  -name "application-prod*.yml" \
  -not -path "*/node_modules/*" \
  -not -path "*/target/*" \
  -not -path "*/.git/*" \
  2>/dev/null || true)

if [ -z "$PROD_FILES" ]; then
  echo "::error::未找到 application-prod*.yml,扫描路径错位或文件被 .gitignore 排除——门禁失效"
  exit 1
fi

# 哨兵 2:PROD_FILES 必须 ≥ 1 个文件(R38 门禁自检模式)
PROD_COUNT=$(echo "$PROD_FILES" | wc -l | tr -d ' ')
if [ "$PROD_COUNT" -lt 1 ]; then
  echo "::error::扫描文件数 < 1,门禁失效自检——直接 fail"
  exit 1
fi

VIOLATIONS=0
DETAILS=""
for f in $PROD_FILES; do
  # 只扫 justauth 段(以 justauth: 开头),其他段不影响
  if ! grep -q "^justauth:" "$f"; then
    continue
  fi
  # 进入 justauth 段后,逐行扫 client-secret:
  while IFS=: read -r line_num line_content; do
    # 跳过注释行
    if echo "$line_content" | grep -qE '^\s*#'; then
      continue
    fi
    # 提取值
    value=$(echo "$line_content" | sed -E 's/^[[:space:]]*client-secret:[[:space:]]*//' | sed 's/[[:space:]]*$//')
    # 空值放行(可能配置模板)
    if [ -z "$value" ]; then
      continue
    fi
    # ${ENV_VAR} 占位符放行
    if echo "$value" | grep -qE '^\$\{[A-Z_][A-Z0-9_]*\}$'; then
      continue
    fi
    # 占位符文字放行(已脱敏的 /* **/ 形式)
    if echo "$value" | grep -qE '\*+|x{4,}|X{4,}'; then
      DETAILS="${DETAILS}  - $f:$line_num (placeholder) '$value'\n"
      VIOLATIONS=$((VIOLATIONS + 1))
      continue
    fi
    # 视为真硬编码
    DETAILS="${DETAILS}  - $f:$line_num (HARDCODED) '$value'\n"
    VIOLATIONS=$((VIOLATIONS + 1))
  done < <(grep -n "client-secret:" "$f" 2>/dev/null || true)
done

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "⚠️  发现 $VIOLATIONS 处 justauth client-secret 内联(详见下表):"
  echo -e "$DETAILS"
  echo ""
  echo "建议：改用 \${JUSTAUTH_<渠道>_SECRET} 环境变量引用 + KMS / secret manager."
  echo "      R35-密钥迁移指南-20260918.md + scripts/r35-migrate-prod-secrets.sh 已就绪."
fi

case "$MODE" in
  strict)
    if [ "$VIOLATIONS" -gt 0 ]; then
      echo "❌ strict mode: 阻断(违规未迁移前禁止合入 prod 配置)"
      exit 1
    fi
    echo "✅ strict mode: 无违规"
    exit 0
    ;;
  self-test)
    # 自证能红:如果没扫到任何硬编码,门禁无法 fail,反而是失效
    if [ "$VIOLATIONS" -eq 0 ]; then
      echo "❌ self-test FAIL: 0 处违规,strict 模式无 fail 触发点——门禁失效自检"
      exit 1
    fi
    echo "✅ self-test PASS: 发现 $VIOLATIONS 处硬编码,strict 模式可 fail"
    echo "   验证触发: bash $0 --strict  → 应 exit 1"
    exit 0
    ;;
  warning)
    if [ "$VIOLATIONS" -gt 0 ]; then
      echo "✅ warning mode: exit 0(已报告 $VIOLATIONS 处违规,等 owner 决策迁移后再切 strict)"
    else
      echo "✅ warning mode: 无违规"
    fi
    exit 0
    ;;
esac