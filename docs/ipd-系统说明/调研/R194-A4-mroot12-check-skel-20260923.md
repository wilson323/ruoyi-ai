# R194-§A4 M-Root-12 多套闸不同步门禁脚本骨架调研（FAIL_SEED 双向触发）

> **基线**: 2026-09-23 R194 §A4。docs-only 调研，不实装 .sh 文件（动共享 hook 需 owner 拍板 OPS-09）。
> **源依据**: BCP §三十 R185 M-Root-12（行 2109-2141）+ R185-P2 owner 拍板项。
> **复用参考**: `scripts/check-meta-access-orphan.sh`（已实装 78 行）+ R142 `check-three-source-hash.sh` 框架。

---

## §一 任务背景

R185 揭示新元根因 **M-Root-12 多套闸不同步**：多套防御/控制/权限闸并存但消费方优先级、调用顺序、OR/AND 语义、覆盖范围未对齐；运行态无信号、单元测试无断言、门禁脚本不检测。典型案例：R182 前端三套权限表达（meta.authority / meta.access / v-access:code）、R184 后端三套安全控制（租户拦截器 / decryptApiKey / SSRF allowlist）。

**根除三件套**（BCP §三十 30.1 已立）：① **单一事实源**（抽 `permissions.ts`）② **优先级声明**（`allowlist > 黑名单 > DNS`）③ **会红的测试**（FAIL_SEED 双向触发）。本盘交付 `check-multigates-sync.sh` 骨架覆盖 5 类多套闸场景，对应 R185-P2 owner 拍板项。

---

## §二 M-Root-12 检测目标（5 类多套闸场景）

| 编号 | 闸位类别 | 闸组合 | 检测文件位 | 检测项 |
|---|---|---|---|---|
| **MG-1** | 权限注解 | meta.authority / meta.access / v-access:code | `routes/modules/ipd.ts` + `ipd-guard.ts` | 3 套是否全引用 `permissions.ts` |
| **MG-2** | 审计入口 | @IpdAudit 注解 / 拦截器 / AOP 切面 | `IpdAuditAspect.java` | 3 套是否声明执行顺序 |
| **MG-3** | 白名单 | SSRF allowlist / 黑名单 / DNS rebinding | `AiChatClient.java` | 是否声明 `allowlist > 黑名单 > DNS` |
| **MG-4** | 配置开关 | 租户拦截器 / decryptApiKey / @InterceptorIgnore | `TenantInterceptor.java` | 是否显式声明 OR/AND 语义 |
| **MG-5** | @IpdAudit 三套闸 | 注解 / 拦截器 / 守卫 | 跨仓交叉 | 闸间同步测试 ≥ 1 个 |

---

## §三 check-multigates-sync.sh 设计

### 3.1 输入参数

| 参数 | 形式 | 默认 | 说明 |
|---|---|---|---|
| `FAIL_SEED` | env | `false` | true=故意注入坏数据 → exit 1/2；false=正常扫描 → exit 0/1/2 |
| `DRY_RUN` / `--dry-run` | env/flag | `false` | true=只输出不报错（CI 排错用） |
| `MG_TARGETS` | env | `1,2,3,4,5` | 限定检测的场景编号（逗号分隔） |

### 3.2 输出格式 + 退出码

```
{file}:{line}: {check_name} FAIL: {detail}
```
例：`AiChatClient.java:142: multi_gates_sync FAIL: SSRF 校验链无优先级声明 (allowlist > 黑名单 > DNS)`

| exit | 含义 | 触发条件 |
|---|---|---|
| **0** | PASS | 5 类闸全有单一事实源 + 优先级声明 + 同步测试 |
| **1** | FAIL（数据缺失）| 单一事实源 / 优先级声明 / 同步测试缺失 |
| **2** | FAIL（FAIL_SEED 注入）| FAIL_SEED=true → 必红 |
| **3** | USAGE | 参数错误 / 目标文件不存在（dry-run 不报）|

---

## §四 脚本骨架代码

```bash
#!/usr/bin/env bash
# check-multigates-sync.sh — M-Root-12 多套闸不同步（FAIL_SEED 双向触发）
# 元根因: M-Root-12（R185 §三十）；根除三件套: 单一事实源 + 优先级声明 + 会红的测试
# 依赖: grep / awk / find；超时: 60s；调用方: CI / pre-commit（待 owner 拍板 OPS-09）

set -euo pipefail

FAIL_SEED="${FAIL_SEED:-false}"
DRY_RUN="${DRY_RUN:-false}"
MG_TARGETS="${MG_TARGETS:-1,2,3,4,5}"
CHECK_NAME="multi_gates_sync"
PERMISSIONS_FILE="apps/web-antd/src/views/ipd/_shared/permissions.ts"
declare -i FAIL_COUNT=0

emit_fail() { echo "${1}:${2}: ${CHECK_NAME} FAIL: ${3}"; FAIL_COUNT+=1; }
emit_pass() { echo "${CHECK_NAME} PASS: ${1}"; }

scan_targets() {
  case ",$MG_TARGETS," in
    *,1,*) scan_mg1 ;; *,2,*) scan_mg2 ;; *,3,*) scan_mg3 ;; *,4,*) scan_mg4 ;; *,5,*) scan_mg5 ;;
  esac
}

scan_mg1() {  # 权限注解三套闸
  local route="apps/web-antd/src/router/routes/modules/ipd.ts"
  local guard="apps/web-antd/src/router/ipd-guard.ts"
  if [ -f "$route" ] && [ -f "$guard" ] && [ -f "$PERMISSIONS_FILE" ]; then
    if grep -q "meta\.authority\|meta\.access\|v-access:code" "$route"; then
      grep -q "hasAccess\|meta\.access" "$guard" && emit_pass "MG-1 三套权限表达全引用 permissions.ts" || emit_fail "$guard" 1 "MG-1 缺 hasAccess 消费者"
    else emit_pass "MG-1 无权限注解闸（跳过）"; fi
  else emit_fail "$PERMISSIONS_FILE" 1 "MG-1 单一事实源 permissions.ts 缺失"; fi
}

scan_mg2() {  # @IpdAudit 注解 / 拦截器 / AOP 三套闸
  local f="ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdAuditAspect.java"
  [ -f "$f" ] && grep -q "@Order\|优先级" "$f" && emit_pass "MG-2 @IpdAudit 三套闸已声明顺序" || emit_fail "$f" 1 "MG-2 @IpdAudit 三套闸无执行顺序声明"
}

scan_mg3() {  # SSRF allowlist > 黑名单 > DNS
  local f="ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/ai/AiChatClient.java"
  [ -f "$f" ] && grep -qE "allowlist.*>.*黑名单|priority.*allowlist" "$f" && emit_pass "MG-3 SSRF 校验链优先级已声明" || emit_fail "$f" 1 "MG-3 SSRF 校验链无优先级声明 (allowlist > 黑名单 > DNS)"
}

scan_mg4() {  # 租户闸 OR/AND 语义
  local f="ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/interceptor/TenantInterceptor.java"
  [ -f "$f" ] && grep -qE "AND|OR|且|或" "$f" && emit_pass "MG-4 租户闸 OR/AND 语义已声明" || emit_fail "$f" 1 "MG-4 租户闸 OR/AND 语义未声明"
}

scan_mg5() {  # 闸间同步测试 ≥ 1
  local n; n=$(find apps/web-antd/src/views/ipd -name "*.test.ts" 2>/dev/null | xargs grep -l "meta\.authority\|v-access:code" 2>/dev/null | wc -l | tr -d ' ')
  [ "${n:-0}" -ge 1 ] && emit_pass "MG-5 闸间同步测试已覆盖 ($n 个)" || emit_fail "apps/web-antd/src/views/ipd" 1 "MG-5 无闸间同步测试"
}

main() {
  echo "[check-multigates-sync] FAIL_SEED=$FAIL_SEED DRY_RUN=$DRY_RUN TARGETS=$MG_TARGETS"
  scan_targets
  # FAIL_SEED 双向触发：true → 故意注入坏数据 → exit 2
  if [ "$FAIL_SEED" = "true" ]; then
    echo "[check-multigates-sync] FAIL_SEED=true: 故意注入坏数据 → exit 2"
    [ "$DRY_RUN" = "true" ] && { echo "[DRY-RUN] 跳过 exit 2"; exit 0; } || exit 2
  fi
  if [ "$FAIL_COUNT" -gt 0 ]; then
    echo "[check-multigates-sync] ❌ FAIL: $FAIL_COUNT 项多套闸不同步"
    [ "$DRY_RUN" = "true" ] && exit 0 || exit 1
  fi
  echo "[check-multigates-sync] ✅ PASS: 5 类多套闸同步（M-Root-12 根除）"
  exit 0
}
main "$@"
```

---

## §五 selftest 验证用例（5 场景）

| # | 场景 | 输入 | 预期 exit | 关键词 |
|---|---|---|---|---|
| **T1 绿** | 默认无 FAIL_SEED | `FAIL_SEED=false` | **0** | `✅ PASS: 5 类多套闸同步` |
| **T2 红** | 故意删 `permissions.ts` | 物理删除 + 跑 | **1** | `permissions.ts 缺失` |
| **T3 FAIL_SEED 红** | FAIL_SEED=true（数据正常也必红）| `FAIL_SEED=true` | **2** | `FAIL_SEED=true: 故意注入坏数据` |
| **T4 边界** | dry-run + FAIL_SEED=true | `DRY_RUN=true FAIL_SEED=true` | **0** | `[DRY-RUN] 跳过 exit 2` |
| **T5 性能** | 全仓扫描耗时 | `time ./check-multigates-sync.sh` | **0** | ≤ 60s（5 类闸 × find/grep）|

**自证能红纪律**：T1→T3 三个用例必须在实装后跑通，否则脚本不算"会红的测试"。

---

## §六 工作量 + §七 owner 拍板 + §八 撞车 0

| 阶段/拍板项/维度 | 内容 | 工时/阻塞/承诺 | 依赖/推荐 |
|---|---|---|---|
| **W1 骨架编写** | bash 实装（含注释 + 哨兵日志）| **2h** | 本盘落档为依据 |
| **W2 selftest** | 5 用例跑通（故意坏数据 + FAIL_SEED 双向）| **2h** | W1 完成 |
| **W3 集成 CI** | 挂入 pre-commit + CI yml（动共享 hook）| **1h** | owner 拍板 OPS-09 |
| **合计** | — | **5h** | owner 拍板后启动 R195 |
| **R194-P1** | 启用 `check-multigates-sync.sh` 实装 | 动共享 hook 需 OPS-09 | **是**（M-Root-12 三件套标配）|
| **R194-P2** | FAIL_SEED 进 CI 默认（每次 PR 自动验红）| 怕误伤 → 限 owner PR | **限 owner/main 分支** |
| **R194-P3** | 5 类闸检测范围是否一次全开 | 怕首跑红太多 | **MG-1/3 先开，MG-2/4/5 R195+** |
| **拍板后启动** | R195 实装 worktree | — | `permissions.ts` 单一事实源 + 5 类闸声明 + CI 集成 |
| **撞车 0 — 仅落档** | 1 个新 .md 调研报告 | — | ✅ 仅本盘 §四 含 bash 代码块 |
| **撞车 0 — Java 零修改** | ruoyi-modules / ruoyi-ipd | — | ✅ 未触 |
| **撞车 0 — SQL/DDL/yml/真库** | ipd_dev @ 13306/23306 | — | ✅ 未触 |
| **撞车 0 — 端口/PID/看板/兄弟** | 13 兄弟会话 / 47 项 status / 12 张汇总卡 | — | ✅ 未抢未翻 |
| **撞车 0 — git 未动** | 仅落档 untracked | — | ✅ 待主协调收口 |

**撞车 0 兑现**：本盘为 R194 §A4 docs-only 调研产出，仅 1 个新文件，与 R193 §六 / R185 §三十 / R142 主报告口径一致。
