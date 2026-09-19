# P0-2 KPI 月份下拉 disabled Fresh 复核（非 bug 收口）

**作者**:R80 派单续做(2026-09-19)
**触发**:owner 选项 A = 「PARTIAL 收口,留 P0-2 为非 bug」
**前置**:R33 接管验收报告(2026-09-17,`docs/ipd-系统说明/R33-接管验收报告-20260917.md`) 异常 2

---

## 结论(1 行)

**R33「异常 2 / P0-2 / KPI 月份下拉全 disabled」= 报告失真,非代码 bug。**

不修代码,拆 PARTIAL 收口;同时把 `<select :disabled="loading">` 整体灰态归类为「loading 态 UI 易误判」,留给 UX 优化(非本卡范围)。

---

## Fresh 复核三证(2026-09-19)

### 证 1:前端代码(磁盘现状,line 127-134)
文件:`apps/web-antd/src/views/ipd/kpi/index.vue`(前端仓 `ruoyi-ipd-web`)

```vue
<label class="filter-label">
  <span>回看月数</span>
  <select v-model.number="periods" class="filter-input" :disabled="loading">
    <option :value="6">6 个月</option>
    <option :value="12">12 个月</option>
    <option :value="24">24 个月</option>
    <option :value="36">36 个月</option>
  </select>
</label>
```

事实:
- 4 个 `<option>` **无 `:disabled` 属性**(R33「前端假 disabled」= **不存在**)
- 只有外层 `<select :disabled="loading">`,加载结束 `loading=false` 自动恢复
- 无任何把 option 单独锁死的代码路径

### 证 2:后端 Controller(磁盘现状)
文件:`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/KpiRecordController.java`

```
@GetMapping("/functional")    line 51
@GetMapping("/performance")   line 66
@GetMapping("/trend")         line 85
```

事实:
- **没有 `/months` 端点**(R33「后端 /api/v1/kpi/months 未真活」= **端点从未被设计**)
- 月份枚举是前端 hardcode 在 `<option>` 里,与后端无契约依赖

### 证 3:前端 API(磁盘现状)
文件:`apps/web-antd/src/api/ipd/kpi.ts`

```ts
export function getKpiTrend(periods?: number): Promise<KpiTrendPoint[]> {
  return ipdGet<KpiTrendPoint[]>('/kpi/trend', periods === undefined ? undefined : { periods });
}
```

事实:
- 前端**完全不调用 `/kpi/months`**(该端点不存在)
- 实际端点 `/kpi/trend?periods=N` 由 `periods.value` (select v-model) 直传,R33 后端建议实属无的放矢

### 证 4:真库 `kpi_records` 行数
```
mysql 查 persons/kpi 表行数: kpi_records=2 行
```
事实:数据稀少,页面空白可能是「loading 完成后 select 解禁但表格区显示空态」,与 disabled 无关。

---

## R33 报告 vs 现实的失真点

| R33 说法 | 现实 | 失真 |
|---|---|---|
| 「前端 KpiFunctional.vue 假 disabled」 | functional/index.vue 根本没有「回看月数」控件(只有 period 输入框);Kpi index.vue 的 4 个 option 无 :disabled | 文件路径错 + disabled 属性错 |
| 「后端 /api/v1/kpi/months 未真活」 | 后端从未实现 /months 端点,前端也不调 | 端点需求无源 |
| 「修复建议:前端移除假 disabled + 后端实现月份枚举」 | 无 disabled 可移除,后端无需实现枚举 | 双重失真 |

---

## R33 异常 2 的真实成因(推断)

R33 截图大概率是在 **loading=true 状态下**抓的:chrome-devtools 快照显示 `<select>` 整体灰态,误读为「4 个 option 全 disabled」。**这是 DOM 整体禁用,不是 option 元素被锁**。

- loading=true 触发条件:`getPerformanceKpi` / `getFunctionalKpi` / `getKpiTrend` 三联调某条失败/慢响应
- loading=false 解除:`finally { loading.value = false }`(line 108)
- 用户视觉:看到灰态 select,以为 4 个 option 被锁;其实只是「点不动 select」,但页面其实可以再点「查询」按钮触发新一轮加载

---

## 边界与不修理由

1. **不修代码**:三个证据闭环证明非 bug,改任何文件都是无效变更
2. **不删 R33 报告**:报告本身是历史快照,保留为「兄弟会话判断失误」证据
3. **不翻卡 done**:P0-2 不在「需要 owner 拍板」的卡清单里——它本身就是误判
4. **建议 UX 优化(非本卡)**:把 `:disabled="loading"` 拆到只禁「查询」按钮,select 整个不禁用,提升体感

---

## 收口动作

- 看板镜像 `docs/ipd-系统说明/开发计划-看板镜像.md` line 437 + 449:
  - line 437 `⚠️ KPI 六卡 0.00 + 回看月数 4 选项 disabled(**异常 2**)` → PARTIAL 注记
  - line 449 真实业务异常清单 P0 行「异常 2」→ 标注 R33 失真,已 fresh 复核
- log.md append 一段「R81-P0-2-fresh-verify 非 bug 收口」
- 镜像不再翻 done,留 PARTIAL 作为「报告失真案例」参考

---

## Fresh 复核时间线

- 2026-09-17:R33 接管验收报告(line 437 + 449 标注 P0 异常 2)
- 2026-09-19 03:00:本会话 fresh 读前端/后端/API 三处磁盘 + 真库查询,确认非 bug
- 2026-09-19 03:05:落档本报告 + 镜像注记 + log.md append + commit
