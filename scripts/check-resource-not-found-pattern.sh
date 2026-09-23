#!/usr/bin/env bash
# R179-P1 治理轮门禁（2026-09-23）：资源不存在 → NOT_FOUND 契约静态扫描。
#
# 病根：controller 直接 `return ApiV1Response.ok(service.getById(id))`，
# 当 id 不存在时 entity 为 null —— 包 VO 即 NPE→500 INTERNAL_ERROR；
# 裸返回则 200 + null data，前端拿到 null 字段后 NPE。
# 两种形态均违反「资源不存在 = 404 NOT_FOUND」契约。
#
# 治理：所有 `@GetMapping("/{id}")` 详情端点必须用 `IpdResources.requireOrNotFound`
# 显式拒绝 null，不允许把 null 透传给 VO.from() 或 ApiV1Response.ok()。
#
# 用法：bash scripts/check-resource-not-found-pattern.sh
# 退出码：0=PASS（无违规），1=FAIL（有违规，输出位置）
#
# 自证能红：故意在某 controller 写 `return ApiV1Response.ok(xxxService.getById(id));`
# → 本脚本必须 FAIL（exit 1）→ 证明脚本真能抓到违规，不是假绿。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CTRL_DIR="$REPO_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller"

if [[ ! -d "$CTRL_DIR" ]]; then
    echo "ERROR: controller dir not found: $CTRL_DIR" >&2
    exit 2
fi

echo "=== R179-P1 门禁：资源不存在 → NOT_FOUND 契约扫描 ==="
echo "扫描目录: $CTRL_DIR"
echo ""

# 模式 1：裸 getById 直接返回（无 requireOrNotFound 包装）
# 匹配：return ApiV1Response.ok(xxxService.getById(id));
# 排除：return ApiV1Response.ok(IpdResources.requireOrNotFound(xxxService.getById(id), ...));
VIOLATIONS=$(grep -rnE 'return\s+ApiV1Response\.ok\([^)]*\.getById\([^)]*\)\s*\)\s*;' "$CTRL_DIR"/*.java 2>/dev/null \
    | grep -v 'IpdResources\.requireOrNotFound' \
    | grep -v '//.*return' \
    || true)

# 模式 2：VO.from(getById) 无包装（NPE 高风险）
# 匹配：VO.from(xxxService.getById(id))
# 排除：VO.from(IpdResources.requireOrNotFound(...))
VO_VIOLATIONS=$(grep -rnE 'VO\.from\([^)]*\.getById\([^)]*\)\s*\)' "$CTRL_DIR"/*.java 2>/dev/null \
    | grep -v 'IpdResources\.requireOrNotFound' \
    | grep -v '//.*VO\.from' \
    || true)

FAIL_COUNT=0

if [[ -n "$VIOLATIONS" ]]; then
    echo "❌ FAIL：发现裸 getById 直接返回（资源不存在会变 200+null data，前端 NPE）："
    echo "$VIOLATIONS" | sed 's/^/  /'
    echo ""
    FAIL_COUNT=$((FAIL_COUNT + $(echo "$VIOLATIONS" | wc -l | tr -d ' ')))
fi

if [[ -n "$VO_VIOLATIONS" ]]; then
    echo "❌ FAIL：发现 VO.from(getById) 无包装（资源不存在会 NPE→500 INTERNAL_ERROR）："
    echo "$VO_VIOLATIONS" | sed 's/^/  /'
    echo ""
    FAIL_COUNT=$((FAIL_COUNT + $(echo "$VO_VIOLATIONS" | wc -l | tr -d ' ')))
fi

if [[ $FAIL_COUNT -eq 0 ]]; then
    echo "✅ PASS：所有 getById 详情端点均已用 IpdResources.requireOrNotFound 显式拒绝 null。"
    echo ""
    echo "契约：资源不存在 → IpdBusinessException(NOT_FOUND) → HTTP 404 + code:50001"
    echo "（ApiV1ErrorCode.NOT_FOUND.getHttpStatus() = 404 映射正确，本门禁确保 controller 端显式抛出）"
    exit 0
else
    echo "=== 修复指南 ==="
    echo "把违规行改为："
    echo "  return ApiV1Response.ok(IpdResources.requireOrNotFound(xxxService.getById(id), id, \"资源名\"));"
    echo ""
    echo "或在 VO.from 场景："
    echo "  return ApiV1Response.ok(XxxVO.from(IpdResources.requireOrNotFound(xxxService.getById(id), id, \"资源名\")));"
    echo ""
    echo "违规总数: $FAIL_COUNT"
    exit 1
fi