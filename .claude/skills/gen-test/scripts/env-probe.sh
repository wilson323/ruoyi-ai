#!/usr/bin/env bash
# env-probe.sh - 跑前环境探测
# 检查 Maven / JDK / Profile 是否就位，避免在环境不对的情况下跑测试

set -euo pipefail

EXIT_CODE=0

# 检查 Maven
if command -v mvn >/dev/null 2>&1; then
  MVN_PATH=$(command -v mvn)
  MVN_VERSION=$("$MVN_PATH" --version 2>/dev/null | head -1 || echo "unknown")
  echo "[OK] mvn found: $MVN_PATH ($MVN_VERSION)"
else
  echo "[FAIL] mvn not in PATH; 提示: export PATH=\"\$HOME/tools/maven/bin:\$PATH\""
  EXIT_CODE=1
fi

# 检查 JDK 17
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 || echo "unknown")
  echo "[OK] JAVA_HOME=$JAVA_HOME ($JAVA_VERSION)"
else
  echo "[FAIL] JAVA_HOME unset or invalid; 提示: export JAVA_HOME=\"\$HOME/tools/jdk-17/Contents/Home\""
  EXIT_CODE=1
fi

# 检查 Surefire groups 配置（用 grep 内容关键字而非键名，避免行号漂移）
if [ -f "pom.xml" ] && grep -qF '<groups>${profiles.active}</groups>' pom.xml; then
  echo "[OK] pom.xml 含 <groups>\${profiles.active}</groups> 配置"
else
  echo "[FAIL] pom.xml 未找到 <groups>\${profiles.active}</groups> 配置"
  EXIT_CODE=1
fi

# 检查默认 active profile：从最近一个含 <activeByDefault>true</activeByDefault> 的 profile 段里取 <id>
ACTIVE_PROFILE=$(awk '
  /<profile>/ { flag=1; id=""; act=0; next }
  flag && match($0, /<id>[^<]+<\/id>/) { id=substr($0, RSTART+4, RLENGTH-9); next }
  flag && /<activeByDefault>true<\/activeByDefault>/ { act=1 }
  /<\/profile>/ { if (act && id != "") { print id; exit } flag=0; act=0; id="" }
' pom.xml 2>/dev/null || echo "")
if [ "$ACTIVE_PROFILE" = "dev" ]; then
  echo "[OK] 默认 active profile = dev"
elif [ -n "$ACTIVE_PROFILE" ]; then
  echo "[WARN] 默认 active profile = '$ACTIVE_PROFILE'（期望 dev）"
else
  echo "[WARN] 未识别出默认 active profile（期望 dev）"
fi

exit $EXIT_CODE