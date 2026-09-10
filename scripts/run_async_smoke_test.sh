#!/usr/bin/env bash
# scripts/run_async_smoke_test.sh
# ----------------------------------------------------------------------
# R28.5 防线 4 配套脚本：用 javac 编译 + java 跑 ApplicationConfigSmokeTest
# 不依赖 JUnit/spring-boot-starter-test，避开改已跟踪 pom.xml 与兄弟会话联测冲突。
#
# 用法：
#   bash scripts/run_async_smoke_test.sh
# 返回：0 全绿；非 0 有失败
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

MODULE=ruoyi-common/ruoyi-common-core
TEST_CLASS=org.ruoyi.common.core.config.ApplicationConfigSmokeTest
TEST_FILE="$MODULE/src/test/java/org/ruoyi/common/core/config/ApplicationConfigSmokeTest.java"

export PATH="$HOME/tools/maven/bin:$PATH"
export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"

echo "▶ R28.5 防线 4: ApplicationConfigSmokeTest（静态反射）"

# 1) 取 main classpath（不走 maven test-compile，避免引入 spring-boot-starter-test 依赖）
CP_FILE=$(mktemp)
mvn -o -pl "$MODULE" -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime 2>&1 | tail -5 || {
    echo "ERROR: maven dependency:build-classpath 失败" >&2; rm -f "$CP_FILE"; exit 2;
}
MAIN_CP="$MODULE/target/classes:$(cat "$CP_FILE")"
rm -f "$CP_FILE"

# 2) 编译测试类到临时目录
TEST_OUT=$(mktemp -d)
javac -cp "$MAIN_CP" -d "$TEST_OUT" "$TEST_FILE" 2>&1 | {
    grep -v '警告' | grep -v '注:' || true
}
if [ ! -f "$TEST_OUT/org/ruoyi/common/core/config/ApplicationConfigSmokeTest.class" ]; then
    echo "ERROR: javac 编译失败" >&2
    rm -rf "$TEST_OUT"
    exit 2
fi

# 3) 运行测试
java -cp "$MAIN_CP:$TEST_OUT" "$TEST_CLASS"
RC=$?
rm -rf "$TEST_OUT"

echo "----------------------------------------"
if [ $RC -eq 0 ]; then
    echo "✅ ApplicationConfigSmokeTest 全绿（防线 4 通过）"
else
    echo "❌ ApplicationConfigSmokeTest 失败（exit $RC）"
fi
exit $RC