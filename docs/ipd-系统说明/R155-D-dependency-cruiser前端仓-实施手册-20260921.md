# R155-D dependency-cruiser 挂前端仓 — 实施手册

**日期**：2026-09-21
**commit**：`待 owner 拍板后`
**状态**：config 落档（`apps/web-antd/.dependency-cruiser.cjs`），未装包，CI 未激活

---

## 1. 背景

R153 §11 P1 我能做 5 项最后 1 项「dependency-cruiser 挂前端仓」未做。前端仓目前**无**任何「深模块边界」或「依赖规则」静态检查，组件 / API / views 互相 import 自由度大，drift 风险高。

## 2. 已落地（commit R155-D）

- `.dependency-cruiser.cjs`（前端适配版，5 条规则）
- 适配后端仓 `.agents/skills/setup-ts-deep-modules/dependency-cruiser.config.cjs` 的 `packages/<name>/` 平铺模式 → 前端的 `apps/web-antd/src/{api,views,_shared}/ipd/<module>` 结构

### 5 条规则
| # | 规则 | severity | 意图 |
|---|---|---|---|
| R1 | views-import-api-entrypoint-only | error | views 只能 import api 入口 |
| R2 | api-cross-module-via-entry | error | api 模块互引走 entry（防未来加子目录漂移）|
| R3 | no-api-to-views-back | error | api 不能 import views（防层级反转）|
| R4 | no-shared-self-import | warn | shared 工具不互相 import（软提醒）|
| R5 | no-ipd-circular | error | ipd 命名空间无循环依赖 |

## 3. 待 owner 拍板（owner-only）

装包 + 接入 CI = 改 package.json + pnpm-lock.yaml + turbo.json + 新建 GitHub workflow。

### 3.1 装包
```bash
pnpm add -D dependency-cruiser -w
```
风险：pnpm-lock.yaml 已 11 天未动，加 dep 会改 lock 触发 CI cache miss。

### 3.2 turbo.json 加 task
```json
"depcruise": {
  "outputs": ["dependency-cruiser-report.json"]
}
```
关联到 root package.json：
```json
"depcruise": "depcruise apps/web-antd/src --config .dependency-cruiser.cjs"
```

### 3.3 CI 接入
新建 `.github/workflows/dependency-cruiser.yml`：
```yaml
name: dependency-cruiser
on: [push, pull_request]
jobs:
  depcruise:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - run: pnpm install --frozen-lockfile
      - run: pnpm exec depcruise apps/web-antd/src --config .dependency-cruiser.cjs
```

### 3.4 baseline 期望输出
当前 flat 模式（无子目录）下 R1/R2 不会触发；R3/R4/R5 可能首次跑出少量违例 → 拍板是否豁免/整改。

## 4. 边界严守

- ✅ 不装包（不增 dep / 不改 lock）
- ✅ 不接 turbo.json（避免 CI 跑 command-not-found 失败）
- ✅ 不接 GitHub workflow（避免 PR 后红）
- ✅ 仅写 .dependency-cruiser.cjs + 本 docs
- ✅ 不动兄弟会话 wt-p2trace

## 5. R156 启动建议

owner 拍板装包后：
1. pnpm install --frozen-lockfile 验证 lock 一致
2. 跑 baseline depcruise 看真实违例数
3. 写整改 PR（如有 R3/R4/R5 违例）
4. 接 turbo.json + workflow
5. 翻卡 + R156 收口报告