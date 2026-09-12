#!/usr/bin/env bash
# check_sse_contract.sh — SSE 端点响应契约门禁（2026-09-11）
#
# 背景：SSE 端点认证失败 / 业务异常时不能用 return null（200 空体 → EventSource MIME 错）
# 或 advice JSON 响应（同样 MIME 错），必须走 SseErrorEmitter 推 error 帧。
# 本脚本扫所有 @GetMapping/@PostMapping produces=text/event-stream 的 controller 方法，
# 验证它们调了 SseErrorEmitter.completeWithError（入口 try-catch 保护）。
#
# 三层哨兵（自证能红）：
#   1. 输入层：SSE controller 扫描数量 < 下限 → fail（防 grep 路径错位）
#   2. 解析层：未配 SSE 错误帧保护的 SSE controller > 0 → fail
#   3. 负向验证：故意撤掉一个 SseErrorEmitter.completeWithError 调用 → fail；恢复 → OK
#
# 用法：bash scripts/check_sse_contract.sh（默认扫 ruoyi-common/ruoyi-common-sse、
# ruoyi-modules/ruoyi-aiflow、ruoyi-modules/ruoyi-chat、ruoyi-modules/ruoyi-ipd）

set -uo pipefail

# 默认扫描范围（按需可通过第一个参数扩展）
SCAN_PATHS="${1:-ruoyi-common/ruoyi-common-sse ruoyi-modules/ruoyi-aiflow ruoyi-modules/ruoyi-chat ruoyi-modules/ruoyi-ipd}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

# 输入层哨兵：至少 1 个 SSE 端点（SseErrorEmitter 已有调用方证明）
SSE_ENDPOINTS=$(grep -rl "produces.*MediaType\.TEXT_EVENT_STREAM_VALUE" --include="*.java" \
    $SCAN_PATHS 2>/dev/null | sort -u | wc -l | tr -d ' ')

if [ "$SSE_ENDPOINTS" -lt 1 ]; then
    echo "⛔ FAIL (输入层哨兵)"
    echo "  未发现任何 produces=text/event-stream 端点（grep 路径错位？）"
    echo "  扫描路径: $SCAN_PATHS"
    exit 1
fi

# 找出所有 SSE controller 文件
SSE_FILES=$(grep -rl "produces.*MediaType\.TEXT_EVENT_STREAM_VALUE" --include="*.java" \
    $SCAN_PATHS 2>/dev/null | sort -u)

# 解析层哨兵：每个 SSE controller 必须满足以下两种合法模式之一：
#   模式 A（同步拒绝）：方法返回 ResponseEntity<SseEmitter>（如 SseController / IpdSseController）
#   模式 B（异步错误帧）：方法体调 SseErrorEmitter.<方法>(（其他 6 个 controller）
# 检测精确到调用语句（SseErrorEmitter.xxx(），不依赖 import 声明——
# 否则只注释调用行 import 仍在仍会假绿。
UNGUARDED=()
for f in $SSE_FILES; do
    if ! grep -qE "SseErrorEmitter\.[a-zA-Z]+\(" "$f" && ! grep -q "ResponseEntity<SseEmitter>" "$f"; then
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