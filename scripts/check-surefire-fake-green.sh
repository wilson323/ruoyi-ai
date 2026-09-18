#!/usr/bin/env bash
# scripts/check-surefire-fake-green.sh
# ----------------------------------------------------------------------
# R25 病根 ① 门禁:根除 Surefire @Tag dev 假绿陷阱。
#
# 背景(实测):
#   - pom.xml line 479: <groups>${profiles.active}</groups> + <excludedGroups>exclude</excludedGroups>
#   - 默认 profile=local → groups=local → 跑 0 个测试类 → BUILD SUCCESS(假绿)
#   - ruoyi-ipd 模块 238 个测试类 100% 全部打 @Tag("dev") → 默认行为下全部被静默跳过
#   - 兄弟会话"全绿"毫无意义,本地只跑 0 个测试,无法验证真实业务行为
#
# 检测三件事(全部报警):
#   (a) pom.xml <groups> 是否含 ${profiles.active}(动态污染源)
#   (b) ruoyi-ipd 模块 0 测试类有 @Tag("dev"|"unit"|"integration") 之外的 tag
#       → 说明默认 profile 下 100% 都被过滤
#   (c) mvn test 默认 profile=local 实际跑出来测试数 < N(默认 N=10)
#
# 用法(在 ruoyi-ai 仓根目录):
#   ./scripts/check-surefire-fake-green.sh                  # 全量(默认 a+b+c,慢)
#   ./scripts/check-surefire-fake-green.sh --quick          # 只跑 a+b 静态分析(快,推荐 CI)
#   ./scripts/check-surefire-fake-green.sh --runtime        # 跑 mvn test 真实验证(慢)
#   ./scripts/check-surefire-fake-green.sh --threshold N    # 自定义测试数阈值(默认 10)
#   ./scripts/check-surefire-fake-green.sh --module NAME    # 自定义模块(默认 ruoyi-ipd)
#   ./scripts/check-surefire-fake-green.sh --json           # 只输出 JSON,不打 info 日志
#   ./scripts/check-surefire-fake-green.sh --no-self-test   # 关闭自证红说明
#
# 退出码:
#   0 = 无假绿陷阱
#   1 = 检测到假绿陷阱
#   2 = 脚本/参数错误
#
# 背景与根因: .claude/skills/ipd-guard-surefire-fake-green/SKILL.md
# ----------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MODE="full"           # full | quick | runtime
THRESHOLD=10
MODULE="ruoyi-ipd"
JSON_ONLY=0
SELF_TEST_RED=1

while [ $# -gt 0 ]; do
  case "$1" in
    --quick)        MODE="quick"; shift ;;
    --runtime)      MODE="runtime"; shift ;;
    --threshold)    THRESHOLD="$2"; shift 2 ;;
    --module)       MODULE="$2"; shift 2 ;;
    --json)         JSON_ONLY=1; shift ;;
    --no-self-test) SELF_TEST_RED=0; shift ;;
    -h|--help)
      sed -n '2,38p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: 未知参数 $1" >&2
      exit 2
      ;;
  esac
done

cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

# ---- 初始化检测结果字段 ----
DET_STATIC_GROUPS="false"      # (a) pom.xml <groups> 含 ${profiles.active}
DET_ALL_DEV_TAG="false"        # (b) 所有测试类都是同一 tag(被默认 profile 过滤)
DET_DEFAULT_COUNT=0            # (c) 默认行为测试数
DET_TEST_FILES=0               # 测试类总数(参考)
DET_PASS="true"
DET_ACTION=""
DET_ERRORS=0

log() {
  if [ "$JSON_ONLY" = "0" ]; then
    echo "$@"
  fi
}

log "▶ check-surefire-fake-green: mode=$MODE module=$MODULE threshold=$THRESHOLD"
log "▶ 仓根: $REPO_ROOT"
log ""

# ============================================================
# 检测 (a): pom.xml <groups> 是否含 ${profiles.active}
# ============================================================
if [ "$MODE" = "full" ] || [ "$MODE" = "quick" ]; then
  log "[a] 扫描 pom.xml <groups> 是否含 \${profiles.active} ..."

  POM_FILE="$REPO_ROOT/pom.xml"
  if [ ! -f "$POM_FILE" ]; then
    log "  ⚠️ pom.xml 不存在: $POM_FILE(跳过检测 a)"
  else
    # 匹配 <groups>...${profiles.active}...</groups>
    if grep -nE '<groups>.*\$\{profiles\.active\}.*</groups>' "$POM_FILE" >/dev/null 2>&1; then
      MATCHED_LINE=$(grep -nE '<groups>.*\$\{profiles\.active\}.*</groups>' "$POM_FILE" | head -1 || true)
      DET_STATIC_GROUPS="true"
      log "  ⛔ pom.xml <groups> 含动态污染源 \${profiles.active}:"
      log "    $MATCHED_LINE"
      log "    默认 profile=local → groups=local → 跑 0 个 @Tag(\"dev\") 测试 → BUILD SUCCESS(假绿)"
      DET_ERRORS=$((DET_ERRORS + 1))
    else
      log "  ✅ pom.xml <groups> 无 \${profiles.active} 污染"
    fi
  fi
  log ""
fi

# ============================================================
# 检测 (b): 模块所有测试类是否都被同一 tag 过滤
# ============================================================
if [ "$MODE" = "full" ] || [ "$MODE" = "quick" ]; then
  log "[b] 扫描 $MODULE 模块 @Tag 分布..."

  TEST_DIR="$REPO_ROOT/ruoyi-modules/$MODULE/src/test"
  if [ ! -d "$TEST_DIR" ]; then
    log "  ⚠️ 测试目录不存在: $TEST_DIR(跳过检测 b)"
  else
    # 统计所有 *Test.java
    TEST_FILES=$(find "$TEST_DIR" -name "*Test.java" -not -path "*/target/*" -type f 2>/dev/null || true)
    if [ -n "$TEST_FILES" ]; then
      TEST_FILE_COUNT=$(echo "$TEST_FILES" | wc -l | tr -d ' ')
    else
      TEST_FILE_COUNT=0
    fi
    DET_TEST_FILES="$TEST_FILE_COUNT"

    # 提取所有出现的 @Tag("xxx") 字符串(去重,统计分布)
    ALL_TAGS=$(echo "$TEST_FILES" | xargs grep -hoE '@Tag\("[^"]+"\)' 2>/dev/null | sort -u || true)
    if [ -n "$ALL_TAGS" ]; then
      TAG_COUNT=$(echo "$ALL_TAGS" | wc -l | tr -d ' ')
    else
      TAG_COUNT=0
    fi

    log "  测试类总数: $TEST_FILE_COUNT"
    log "  @Tag 唯一值分布: $TAG_COUNT 种"
    if [ -n "$ALL_TAGS" ] && [ "$JSON_ONLY" = "0" ]; then
      echo "$ALL_TAGS" | sed 's/^/    /' || true
    fi

    # 判定:如果只有 1 种 tag 且不在白名单 → 假绿
    if [ "$TAG_COUNT" = "1" ]; then
      ONLY_TAG=$(echo "$ALL_TAGS" | head -1 || true)
      TAG_NAME=$(echo "$ONLY_TAG" | sed -E 's/@Tag\("([^"]+)"\)/\1/')

      case "$TAG_NAME" in
        dev|unit|integration|exclude)
          log "  ⚠️ 唯一 tag=$TAG_NAME: 若默认 profile != $TAG_NAME 则 100% 被过滤"
          # 只有当 <groups> 也是 ${profiles.active} 时才算假绿陷阱(组合判定)
          if [ "$DET_STATIC_GROUPS" = "true" ]; then
            DET_ALL_DEV_TAG="true"
            log "  ⛔ 与 (a) 组合 = R25 病根 ①:默认 profile=local 下 0 个测试运行"
            DET_ERRORS=$((DET_ERRORS + 1))
          else
            log "  ℹ️ 但 <groups> 非动态污染,视为静态选择,可接受"
          fi
          ;;
        *)
          DET_ALL_DEV_TAG="true"
          log "  ⛔ 唯一 tag=$TAG_NAME 不在白名单(dev/unit/integration/exclude),疑似 R25 病根 ①"
          DET_ERRORS=$((DET_ERRORS + 1))
          ;;
      esac
    elif [ "$TAG_COUNT" = "0" ]; then
      log "  ℹ️ 测试类无任何 @Tag(完全靠 Surefire 默认全跑,无假绿风险)"
    else
      log "  ✅ @Tag 多样化(>=2 种),无单一过滤风险"
    fi
  fi
  log ""
fi

# ============================================================
# 检测 (c): mvn -o -pl $MODULE test 默认 profile=local 实测
# ============================================================
if [ "$MODE" = "full" ] || [ "$MODE" = "runtime" ]; then
  log "[c] 实测 mvn test 默认 profile=local 跑出测试数(慢)..."

  MODULE_PATH="ruoyi-modules/$MODULE"
  if [ ! -d "$REPO_ROOT/$MODULE_PATH" ]; then
    log "  ⚠️ 模块目录不存在: $MODULE_PATH(跳过检测 c)"
  else
    log "  执行: mvn -o -pl $MODULE_PATH test(无 -P profile,默认 local)"

    # 抓取汇总行 "Tests run: N, Failures: ..."
    MVN_OUT=$(mvn -o -pl "$MODULE_PATH" test -Dtest='*Test' \
      2>&1 | tee /tmp/check-surefire-fake-green.mvn.log | tail -200 || true)

    # 解析 Tests run 总数(只取 surefire 汇总)
    TEST_RUN_LINE=$(echo "$MVN_OUT" | grep -E "Tests run:" | tail -1 || true)

    if [ -n "$TEST_RUN_LINE" ]; then
      DET_DEFAULT_COUNT=$(echo "$TEST_RUN_LINE" | sed -E 's/.*Tests run:[[:space:]]+([0-9]+).*/\1/' || echo 0)
      log "  实测结果: $TEST_RUN_LINE"
      log "  测试数: $DET_DEFAULT_COUNT"
    else
      DET_DEFAULT_COUNT=0
      log "  ⚠️ 未解析到 Tests run 行,视为 0"
    fi

    # 判定:测试数 < THRESHOLD 报警
    if [ "$DET_DEFAULT_COUNT" -lt "$THRESHOLD" ]; then
      log "  ⛔ 默认 profile=local 实跑测试数 $DET_DEFAULT_COUNT < 阈值 $THRESHOLD = 假绿陷阱确认"
      log "    修法: 显式 -P dev 或加 @Tag(\"unit\") / @Tag(\"integration\") 作为本地可跑子集"
      DET_ERRORS=$((DET_ERRORS + 1))
    else
      log "  ✅ 默认行为跑出 $DET_DEFAULT_COUNT 个测试 ≥ 阈值 $THRESHOLD"
    fi
  fi
  log ""
fi

# ============================================================
# 汇总
# ============================================================
if [ "$DET_ERRORS" -gt 0 ]; then
  DET_PASS="false"
  DET_ACTION="需要显式 -P dev 或加基础 tag(如 @Tag(\"unit\") 让默认 profile 也能跑)"
else
  DET_PASS="true"
  DET_ACTION="无需修复"
fi

# ---- 输出 JSON ----
JSON_OUTPUT="{\"check\":\"surefire-fake-green\",\"mode\":\"$MODE\",\"module\":\"$MODULE\",\"threshold\":$THRESHOLD,\"detections\":{\"static_groups\":$DET_STATIC_GROUPS,\"all_dev_tag\":$DET_ALL_DEV_TAG,\"default_test_count\":$DET_DEFAULT_COUNT,\"test_file_count\":$DET_TEST_FILES},\"errors\":$DET_ERRORS,\"pass\":$DET_PASS,\"action\":\"$DET_ACTION\"}"

if [ "$JSON_ONLY" = "1" ]; then
  echo "$JSON_OUTPUT"
else
  log "==== 总结 ===="
  log "  (a) <groups> \${profiles.active} 污染: $DET_STATIC_GROUPS"
  log "  (b) 所有测试类同一 tag:           $DET_ALL_DEV_TAG"
  log "  (c) 默认 profile 实测测试数:       $DET_DEFAULT_COUNT(阈值 $THRESHOLD)"
  log "  错误计数:                          $DET_ERRORS"
  log "  通过:                              $DET_PASS"
  log "  处置建议:                          $DET_ACTION"
  log ""
  log "==== JSON 报告 ===="
  echo "$JSON_OUTPUT"
fi

# ---- 自证能红说明 ----
if [ "$SELF_TEST_RED" = "1" ] && [ "$DET_PASS" = "true" ] && [ "$MODE" = "full" ]; then
  log ""
  log "ℹ️ 自证说明: 默认状态应为 red,本次跑出 green 表明当前无 R25 病根 ①"
fi

if [ "$DET_PASS" = "true" ]; then
  exit 0
else
  exit 1
fi
