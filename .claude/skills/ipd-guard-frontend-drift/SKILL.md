---
name: ipd-guard-frontend-drift
description: IPD 前端（apps/web-antd/）改 api/ipd/*.ts、新增错误码文件、改 product-group.ts 的漂移防御。封装 4 项 drift-guard 检查、同名导出冲突修法、诚实声明文案误伤区分、CI 接入状态。
---

# ipd-guard-frontend-drift

IPD 前端漂移防御技能。配套 `.claude/helpers/ipd-frontend-drift-guard.cjs` hook。

## 何时使用

- 在 `apps/web-antd/src/api/ipd/` 新增 / 改名 / 删函数 export
- 新增 `apps/web-antd/src/views/ipd/<domain>/<domain>-error.ts`
- 改 `apps/web-antd/src/api/ipd/product-group.ts`（兼容层）
- 改前端 API 调用处（admin / review / 项目管理等视图）
- 评估任何"前端工程变更"

## 四项检查

`scripts/check-ipd-frontend-drift.sh` 或 hook `ipd-frontend-drift-guard.cjs` 跑 4 项：

| # | 检查 | 触发 | 严重度 |
|---|---|---|---|
| 1 | `api/ipd/*.ts` 同名导出冲突 | 在已有同名 export 的文件新增 export | exit 2 阻断 |
| 2 | 错误码文件遗漏 | 改 API 但没新建 `<domain>-error.ts` | exit 2 阻断 |
| 3 | `product-group.ts` 兼容层缺位 | 新增 endpoint 但 product-group 未兼容 | exit 2 阻断 |
| 4 | 手写 BackendPending 残留 | 视图里手写"待后端交付"占位符 | warning（非阻断，可配置 strict） |

## 强制做法

### 改动前自检

```bash
# 跑全套检查看 baseline
node .claude/helpers/ipd-frontend-drift-guard.cjs \
  --root apps/web-antd/src
```

期望：exit 0。如果非 0，先看是 baseline 已有问题还是你引入的。

### 改动后自检

每次改完前端 api/ 目录，commit 前必跑：

```bash
# 1. drift-guard 自检
bash scripts/check-ipd-frontend-drift.sh

# 2. 前端工程自身门禁
cd /Users/mac/Documents/ruoyi-ipd-web
pnpm exec vitest run --config vitest.ipd.config.mts
pnpm run check:type
pnpm run build:antd
```

build 退出 0 仍要检查日志内 TS 诊断（曾有 TS4058 把 `scrollbarRef` 声明降为 any）。

## 同名导出冲突的修法

**反例**：两个文件 `gate-element.ts` 与 `gate-element-result.ts` 都有 `listGateElements(gate)` 但参数 / 返回类型不同。两边都有活消费方（`admin/gate-elements/index.vue` 与 `review/gate-panel.vue`）。

**修法**：将后加的重命名与返回类型对齐（如 `listGateElementViews` 对应 `IpdGateElementView`），同步更新所有 import 和调用处。**不要**试图删已有 export——会断消费方。

drift-guard 已知会拦的情况：
- `listXxx` 在 A.ts 已有，B.ts 又导出同名函数（即使参数不同也拦）
- `listYyy` 在 A.ts 已存在，B.ts 重新声明 `function listYyy`（即使实现不同也拦）

**通用规约**：不要在 `api/ipd/*.ts` 新增与既有同名的 export，要重命名消歧。

## 错误码文件的强制规则

每个 `api/ipd/<domain>.ts` 必须配套 `<domain>-error.ts` 错误码定义文件。改 domain API 时若没新增对应错误码，drift-guard 拦。

```typescript
// 例：gate-element-error.ts
export const GATE_ELEMENT_ERROR_CODES = {
  NOT_FOUND: 'IPD-GATE-ELEMENT-001',
  INVALID_STATUS: 'IPD-GATE-ELEMENT-002',
  DUPLICATE_NAME: 'IPD-GATE-ELEMENT-003',
} as const;

export type GateElementErrorCode =
  (typeof GATE_ELEMENT_ERROR_CODES)[keyof typeof GATE_ELEMENT_ERROR_CODES];
```

## 诚实声明文案 vs 真实占位符的区分

drift-guard 第 4 项会扫"待后端交付"类文案。**但**：

- **诚实声明**（如"后端未交付不做假数据"——规约要求的做法）：保留，CI 不开 strict
- **真实占位符**（如"待补"、"TODO"）：必须替换为真实数据或 `<domain>-error.ts` 错误码

CI workflow 不开 `--strict`（警告不阻断），保留 strict 作为治理轮目标。抽查确认是诚实声明后不动代码。

## CI 接入状态

- `.github/workflows/ipd-frontend-drift.yml`：**待补**（hook 仅本地跑，CI 缺失）
- 本地 hook：`.claude/helpers/ipd-frontend-drift-guard.cjs`（已在 `.claude/settings.json` 注册）
- 手动跑：`bash scripts/check-ipd-frontend-drift.sh`

补 CI workflow 的最小骨架：

```yaml
name: ipd-frontend-drift
on:
  push:
    paths:
      - 'apps/web-antd/src/api/ipd/**'
      - 'apps/web-antd/src/views/ipd/**'
  pull_request:
    paths:
      - 'apps/web-antd/src/api/ipd/**'
      - 'apps/web-antd/src/views/ipd/**'
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - run: npm i -g pnpm@10.14.0
      - run: pnpm install --frozen-lockfile
      - run: bash scripts/check-ipd-frontend-drift.sh
```

## 必做检查清单

前端 API 改动交付前自检：

- [ ] drift-guard 自检 exit 0（4 项检查全过）
- [ ] 新增 / 改名的 export 没有重名冲突（重命名消歧而非删除）
- [ ] 错误码文件已新建或更新
- [ ] `product-group.ts` 兼容层已对接（如有兼容需要）
- [ ] `pnpm exec vitest run --config vitest.ipd.config.mts` 全过
- [ ] `pnpm run check:type` 全过（注意 TS 警告会被 build 退出 0 掩盖）
- [ ] `pnpm run build:antd` 退出 0 且日志无 TS4058 等诊断
- [ ] 视图里没手写 BackendPending 占位符（除规约要求的诚实声明）

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| drift-guard exit 2 第 1 项 | 同名 export 冲突 | 知识错（违反同名导出规约） | 重命名消歧 |
| drift-guard exit 2 第 2 项 | 改 API 但未建错误码文件 | 知识错 | 新建 `<domain>-error.ts` |
| drift-guard exit 2 第 3 项 | product-group 兼容层缺 | 知识错 | 加兼容层映射 |
| drift-guard warning 第 4 项 | 视图文案命中"待后端交付"模式 | 检查错（诚实声明误伤） | CI 保持非 strict，抽查确认 |
| TS4058 把声明降为 any | build 退出 0 但日志有警告 | 检查错 | 看 build 日志，不用退出码判定 |
| vitest 红但 dev 跑没事 | 测试用了过期 mock 路径 | 知识错 | 同步 main 现态重写 mock |

## 禁止清单

- ❌ 在 `api/ipd/*.ts` 新增与既有同名的 export
- ❌ 改 API 不建 / 不更新对应 `<domain>-error.ts`
- ❌ 删已有 export（即便"看起来没人用"——先 grep 消费方）
- ❌ 跳过 drift-guard 直接 commit（hook 会拦，绕过会污染主分支）
- ❌ build 退出 0 就当 TS 通过——必须看日志
- ❌ 跳过 vitest / check:type / build:antd 三件套的任意一件
- ❌ 凭 dev 跑通就上线——测试与构建是不同视角

## 版本指纹

- 验证时前端工程：`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/`
- 验证时 pnpm：10.14.0（不要升级）
- 验证时 vite 启动方式：`node node_modules/vite/bin/vite.js`（直起，不走 pnpm 包装，macOS 下卡 read syscall）
- 验证时前端端口：127.0.0.1:15666
- 验证时 drift-guard 脚本：`scripts/check-ipd-frontend-drift.sh` + `.claude/helpers/ipd-frontend-drift-guard.cjs`
- 验证时规约文档：`docs/ipd-系统说明/前端架构规约-20260906.md`
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：pnpm 升级 / vite 升级 / drift-guard 检查项扩展 / 前端工程结构变更