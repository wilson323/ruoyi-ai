# Accessibility Review — tests/a11y 门禁审查与 jsdom 假绿修复

- 日期：2026-09-29（本报告由 Design Review 插件 `accessibility-review` 技能工作流产出）
- 审查对象：`tests/a11y/`（axe-core 扫描器 + fixture 服务器 + Lighthouse CI 配置 + 历史报告）
- 执行方式：自动化重跑（jsdom 模式，错峰端口 4174-4176）+ 新旧报告漂移对比 + fixture HTML 人工源码审查

## Summary

本仓 a11y 门禁（Wave21-A 建立）**从未在 CI 兜底路径上真正扫描过**：jsdom 回退模式下 axe-core 因注入上下文错误全程抛异常，被外层 catch 吞掉后塌缩成「5 页全 clean、exit 0」的假绿。本次审查定位并修复了该缺陷，并补齐「扫描错误 → exit 2」的门禁自证能力（正向验证 5 页真扫描 + 负向验证故意违规页必红）。fixture 本身语义质量良好；但注意 fixture 是无 CSS 演示壳，**color-contrast、焦点可见性、触控目标、动效合规四类问题只有 puppeteer 模式扫真前端（ruoyi-ipd-web / ZK-IPD）才能暴露**，jsdom 模式永远测不到。

## Findings

### [blocker·已修复] jsdom 回退模式 axe 从未运行，假绿 exit 0
- 证据：重跑 jsdom 扫描后逐页 `error="Required \"window\" or \"document\" globals not defined..."`，而摘要显示 `(clean)`、总计 `violations=0`、EXIT=0（报告快照：`tests/a11y/reports/a11y-report-2026-09-29T18-20-29-755Z.json`）。
- 根因：`scanWithJsdom` 用 `dom.window.axe = axe` 把 Node 侧模块挂到 window，`axe.run` 内部解析不到 window/document globals 即抛错；正确做法是 `dom.window.eval(axe.source)` 让 axe 在目标 window 上下文内运行。
- 用户影响：所有无浏览器环境（CI 本意场景）下该门禁形同虚设——前端引入任意 alt/label/landmark 回归都不会报红。
- 修复：`axe-scan.mjs` 注入方式改为 window 内 eval + `axe.run(document.body)`；已提交（见文末 commit）。

### [major·已修复] 扫描错误被摘要伪装成 "(clean)"，且不影响退出码
- 证据：原摘要逻辑仅看 `violations.length===0` 就打印 `(clean)`；`totals` 无 scanErrors 维度；任一页报错仍 exit 0。
- 修复：摘要对带 error 的页打印 `[SCAN-ERROR] ...violations=0 不可信`；`totals.scanErrors>0` 时 stderr 打 `GATE-ERROR` 并 **exit 2**（扫描失效 ≠ 通过）。

### [major] 09-07 报告漂移（16→1→0）疑为扫描目标切换而非缺陷修复，需 owner 复核
- 证据：同日 05-44 puppeteer 报告 16 违规（含 workbench `[critical] aria-roles @ .bar`），08-36 起归零；而 `serve-fixture.mjs` 自 09-06 后无提交流水线改动，内置 fixture HTML 也不含 `.bar`。`port=4173` 注释写明"与 ZK-IPD LIVE URL 一致"——高概率前几次扫的是**真前端**、后几次切回了 fixture。
- 影响：若真前端曾是 16 违规状态，"latest=0" 给人已修复的错觉；`.bar` 的 aria-roles critical 问题在真前端上是否收口**无证据**。
- 建议：对 ruoyi-ipd-web dev server 跑一次 `A11Y_MODE=puppeteer A11Y_TARGET_BASE_URL=<真前端>` 的全量扫描并把报告归档，才能翻「前端 a11y 已达标」的账。

### [minor·已修复] jsdom canvas "Not implemented" 噪声刷屏 stderr，掩盖真错误
- 证据：修复注入后每页输出约 10 行 `HTMLCanvasElement.prototype.getContext` 堆栈（axe color-contrast 匹配器在无渲染树环境下的已知行为）。
- 修复：`VirtualConsole` 过滤该已知噪声并显式转发其余 jsdomError；复验 stderr 仅剩 5 行进度日志。

### [info] 能力边界声明（已写入脚本头注释）
- jsdom 模式无法检出 color-contrast（落入 incomplete，不记 violation）；fixture 无 CSS，对比度/焦点环/触控尺寸/动效均不可测。
- `lighthouserc.json` 阈值 accessibility ≥0.9 error / 其余 warn 属合理基线，但它同样只扫 fixture——真实验收依赖 puppeteer 模式 + 真前端 URL。

## fixture 人工源码审查（5 页演示壳）

良好：表单均用原生 `label+input`、`autocomplete` 齐备；表格有 `caption` + `th scope`；gate-review 用 `fieldset/legend`；workbench 有 skip link 与 `aria-label` 导航；标题层级 h1→h2 无跳级；无 ARIA 滥用。
待观察（fixture 是演示壳，非上线 UI，不立修复账）：仅 /workspace 有 skip link；登录页导航/主区无 landmark 包裹差异；异常态（error/empty/loading）未在 fixture 建模——真前端扫描时按插件清单补查。

## 验证记录（证据链）

| 检查 | 命令 | 结果 |
|---|---|---|
| 修复前复现假绿 | `A11Y_MODE=jsdom node axe-scan.mjs`（端口 4174） | EXIT=0、5 页全 error、摘要却显示 clean → 假绿实锤 |
| 正向（修复后） | 同上（端口 4176） | 5 页 status=200、0 error、0 violations、EXIT=0，stderr 干净 |
| 负向（门禁能红） | 临时 `fixtures/neg-check-20260929.html`（无 alt 图 + placeholder 当标签 + div 按钮）+ `A11Y_MAX_VIOLATIONS=0` | 检出 `image-alt [critical]`、`region [moderate]`，EXIT=1 ✅（临时文件已清理） |
| 扫描错误红闸 | 代码路径：`totals.scanErrors>0 → exit 2` | 新增，随任一页 error 触发 |

## Manual Review Required（移交真前端扫描时执行）

- [ ] Keyboard-only 走通登录 → 工作台 → Gate 评审提交路径
- [ ] 读屏抽查 gate-review 复选框组与错误恢复播报
- [ ] color-contrast 用 puppeteer 模式对真前端复扫（fixture 测不到）
- [ ] 09-07 的 `.bar` aria-roles critical 在 ruoyi-ipd-web 上是否仍存在
- [ ] prefers-reduced-motion 与触控目标尺寸（真机/DevTools）

## 提交状态（2026-09-29 20:4x 登记）

修复闭包（`tests/a11y/axe-scan.mjs` + `reports/a11y-report-latest.json` + 本文档）已 staged 并归属登记本会话。
pre-commit **门禁 5（langchain4j 引用只减不增棘轮）被阻**：+1 增量来自兄弟会话 untracked 在途文件 `ruoyi-modules/.../ai/AiGatewayAccountingTest.java`（非本闭包、非 Node 侧）。
处置：不 --no-verify 绕过、不代改 baseline（ratchet-data-guard）；待兄弟会话自行登记 baseline 或提交其闭包后，本 commit 重执即可通过。
修复前假绿证据快照 `a11y-report-2026-09-29T18-20-29-755Z.json` 按仓内 `.gitignore` 策略仅留磁盘不入库。
