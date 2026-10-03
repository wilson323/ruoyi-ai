#!/usr/bin/env bash
# R119 病根 #1 根除: 测试覆盖率量化
# 扫 src/main/java/org/ruoyi/ipd/**/*.java vs src/test/java/org/ruoyi/ipd/**/*Test.java
# 按业务域拆解覆盖度, 输出 SSOT 镜像
# 阈值 < 60% 阻断提交 (exit 1)
#
# 2026-10-03 退役域摘除: `coefficient`（系数变更，随「业绩窗口」退役）已从域清单移除。
#   它是**永久假红**：该域零 Java 文件 → java_count=0 → cov=0 → 必然 < 60% 判 ❌ 且 exit 1。
#   与它同形的还有 launch_date（同样 0 文件）——那不属于本次退役范围，未动，另登记。
#   注: 本脚本未被 .claude/hooks/check-pre-commit.sh 调用，当前处于休眠态
#   （安装入口是 scripts/install-coverage-pre-commit.sh），故此前无人发现它常年为红。

set -e
DOMAINS=(projects sop kpi audit handover launch_date)
TOTAL_JAVA=0
TOTAL_TEST=0
REPORT="docs/ipd-系统说明/测试覆盖率-$(date +%Y%m%d).md"

mkdir -p "$(dirname "$REPORT")"
echo "# 测试覆盖率-$(date +%Y%m%d)" > "$REPORT"
echo "" >> "$REPORT"
echo "| 业务域 | Java 类 | Test 类 | 覆盖率 | 阈值 | 状态 |" >> "$REPORT"
echo "|---|---|---|---|---|---|" >> "$REPORT"

OVERALL_JAVA=0
OVERALL_TEST=0
OVERALL_FAIL=0
for d in "${DOMAINS[@]}"; do
  java_count=$(find ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd -name "*.java" 2>/dev/null | grep -iE "$d" | wc -l | tr -d ' ')
  test_count=$(find ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd -name "*Test.java" 2>/dev/null | grep -iE "$d" | wc -l | tr -d ' ')
  if [ "$java_count" -gt 0 ]; then
    cov=$((test_count * 100 / java_count))
  else
    cov=0
  fi
  if [ "$cov" -lt 60 ]; then
    status="❌"
    OVERALL_FAIL=1
  else
    status="✓"
  fi
  OVERALL_JAVA=$((OVERALL_JAVA + java_count))
  OVERALL_TEST=$((OVERALL_TEST + test_count))
  echo "| $d | $java_count | $test_count | ${cov}% | 60% | $status |" >> "$REPORT"
done

if [ "$OVERALL_JAVA" -gt 0 ]; then
  overall_cov=$((OVERALL_TEST * 100 / OVERALL_JAVA))
else
  overall_cov=0
fi
echo "" >> "$REPORT"
echo "**总计**: Java $OVERALL_JAVA 类 / Test $OVERALL_TEST 类 / 整体覆盖 ${overall_cov}%" >> "$REPORT"

if [ "${OVERALL_FAIL:-0}" = "1" ]; then
  echo "❌ 测试覆盖率不达标 (某些业务域 < 60%)"
  exit 1
fi
echo "✓ 测试覆盖率全部 ≥ 60%"
exit 0
