# R25 P0-3 动态加载依赖清单（20260909-102750）

> 自动门禁：`scripts/check_dynamic_loadable.sh`（RC-5 动态依赖清单）
> 维护：每周日 02:00 自动重生成 + 每次菜单 schema 变更手动重跑

## 汇总

| 类别 | 数量 |
|---|---|
| 前端 `import.meta.glob` 模式数 | 0 |
| 前端动态可加载视图数 | 0 |
| 后端菜单 component 字段数 | 0 |
| 🔴 **真正 dynamic-loadable 视图数（交集）** | **0** |

## 静态扫描说明

R25-B 前端死代码扫描共发现 156 个"未路由"视图，其中：
- 147 个 = 真死（路由表 + 静态 import 都未引用）→ 可静态判定
- **0 个 = 动态可达（被后端菜单 component 字段动态加载）→ 必须 owner 复核**

静态扫描工具无法区分这两类，必须依赖本清单才能准确判别。

## 🔴 Dynamic-Loadable 视图清单

> 这些视图由 `router/access.ts` 的 `import.meta.glob` + 后端菜单 component 字段联动加载
> 删除前必须确认后端菜单不再下发对应 component，否则**菜单点开白屏**

| # | 后端 component 字段 | 前端 vue 路径 |
|---|---|---|

## 重跑命令

```bash
cd /Users/mac/Documents/ruoyi-ai
./scripts/check_dynamic_loadable.sh
./scripts/check_dynamic_loadable.sh --output /path/to/manifest.json
```

## CI 接入

```yaml
# .github/workflows/dynamic-loadable.yml
- name: 重生成动态依赖清单
  run: ./scripts/check_dynamic_loadable.sh --output docs/ipd-系统说明/lint-reports/dynamic-loadable-manifest.json
```

清单变化 > 0 时自动开 PR，owner 审核后合入。
