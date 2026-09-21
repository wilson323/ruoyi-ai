#!/usr/bin/env bash
# check-sse-contract.sh — SSE 端点响应契约门禁（2026-09-11）
#
# 背景：SSE 端点认证失败 / 业务异常时不能用 return null（200 空体 → EventSource MIME 错）
# 或 advice JSON 响应（同样 MIME 错），必须走 SseErrorEmitter 推 error 帧。
#
# 扫描条件（双通道并集，2026-09-11 修正覆盖缺口）：
#   通道 1：produces = MediaType.TEXT_EVENT_STREAM_VALUE（显式声明 SSE）
#   通道 2：*Controller.java 中引用 SseEmitter（隐式 SSE——无 produces，靠方法返回类型
#           产出，实测漏网：ChatController / ShortDramaController 两个 stream 端点）
# 每个命中的 controller 必须满足「模式 A（返回 ResponseEntity<SseEmitter>）」或
# 「模式 B（调 SseErrorEmitter.<方法>( ）」二者其一。
#
# 三层哨兵（自证能红）：
#   1. 输入层：SSE controller 扫描数量 < 下限 → fail（防 grep 路径错位）
#   2. 解析层：未配 SSE 错误帧保护的 SSE controller > 0 → fail
#   3. 负向验证：故意撤掉一个 SseErrorEmitter.completeWithError 调用 → fail；恢复 → OK
#
# 用法：bash scripts/check-sse-contract.sh（默认扫 ruoyi-common/ruoyi-common-sse、
# ruoyi-modules/ruoyi-aiflow、ruoyi-modules/ruoyi-chat、ruoyi-modules/ruoyi-ipd）

set -uo pipefail

# 默认扫描范围（按需可通过第一个参数扩展）
SCAN_PATHS="${1:-ruoyi-common/ruoyi-common-sse ruoyi-modules/ruoyi-aiflow ruoyi-modules/ruoyi-chat ruoyi-modules/ruoyi-ipd}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

# 扫描模式：显式 produces 声明 ∪ Controller 文件中的 SseEmitter 引用
SSE_SCAN_PATTERN='(produces.*MediaType\.TEXT_EVENT_STREAM_VALUE|SseEmitter)'

# 输入层哨兵：SSE controller 数量不得低于基线 8（2026-09-11 实测值）。
# 数量下降 = 扫描路径错位或条件退化 → fail；端点合法增减须显式更新本基线。
SSE_ENDPOINTS=$(grep -rlE "$SSE_SCAN_PATTERN" --include="*Controller.java" \
    $SCAN_PATHS 2>/dev/null | grep -v "/target/" | grep -v "/test/" | sort -u | wc -l | tr -d ' ')

if [ "$SSE_ENDPOINTS" -lt 8 ]; then
    echo "⛔ FAIL (输入层哨兵)"
    echo "  SSE controller 数量 $SSE_ENDPOINTS < 基线 8（grep 路径错位或扫描条件退化？）"
    echo "  扫描路径: $SCAN_PATHS"
    exit 1
fi

# 找出所有 SSE controller 文件
SSE_FILES=$(grep -rlE "$SSE_SCAN_PATTERN" --include="*Controller.java" \
    $SCAN_PATHS 2>/dev/null | grep -v "/target/" | grep -v "/test/" | sort -u)

# 解析层哨兵：每个 SSE controller 必须满足以下两种合法模式之一：
#   模式 A（同步拒绝）：方法返回 ResponseEntity<SseEmitter>（如 SseController / IpdSseController）
#   模式 B（异步错误帧）：方法体调 SseErrorEmitter.<方法>(（其他 6 个 controller）
# 检测精确到调用语句（SseErrorEmitter.xxx(），不依赖 import 声明——
# 且先过滤注释行（行首 // / * / /*）再匹配，防「注释掉调用」/「import 声明」两种假绿
# （2026-09-11 负向验证实测：注释行内调用字面量曾被误认为有效保护）。
COMMENT='^[[:space:]]*(//|\*|/\*)'
UNGUARDED=()
for f in $SSE_FILES; do
    # 先剥离注释行再匹配。用命令替换独占读取（不用管道）——直接
    # grep -v | grep -q 会因 grep -q 提前退出触发上游 SIGPIPE，在 set -o pipefail
    # 下变成偶发退出码 141 → 误报文件未保护（2026-09-11 负向验证实测 20 次竞态）。
    NON_COMMENT=$(grep -vE "$COMMENT" "$f")
    if ! grep -qE "SseErrorEmitter\.[a-zA-Z]+\(" <<<"$NON_COMMENT" \
        && ! grep -q "ResponseEntity<SseEmitter>" <<<"$NON_COMMENT"; then
        UNGUARDED+=("$f")
    fi
done

if [ ${#UNGUARDED[@]} -gt 0 ]; then
    echo "⛔ FAIL (解析层哨兵)"
    echo "  发现 ${#UNGUARDED[@]} 个 SSE controller 缺少错误帧/同步拒绝保护："
    for f in "${UNGUARDED[@]}"; do
        echo "    - $f"
    done
    echo ""
    echo "Fix template（两种合法模式任选其一）："
    echo "  模式 A（同步拒绝，认证失败直接返回 401）："
    echo "    public ResponseEntity<SseEmitter> connect(...) {"
    echo "        if (未鉴权) return ResponseEntity.status(401).build();"
    echo "        ..."
    echo "    }"
    echo ""
    echo "  模式 B（异步错误帧，推 error 事件）："
    echo "    SseEmitter emitter = new SseEmitter(timeout);"
    echo "    try { ... } catch (Exception e) {"
    echo "        SseErrorEmitter.completeWithError(emitter, \"AUTH_REQUIRED\", e.getMessage(), log);"
    echo "        return emitter;"
    echo "    }"
    exit 1
fi

echo "✅ OK"
echo "  扫描路径: $SCAN_PATHS"
echo "  SSE controller 文件数: $SSE_ENDPOINTS"
echo "  全部已配 SseErrorEmitter 错误帧保护"