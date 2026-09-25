# WP3.1 批次3（AI-FUSION-B3）：全局 AI 副驾前端接线

- 执行：R215 增量收口轮主会话（owner 指令「确保所有功能充分与 AI 能力融合」）
- 日期：2026-09-24 深夜
- 看板卡：B3 `e552051d`（新建）；盘点依据：`ai-fusion-gap-survey.md`（同目录）

## 接线内容（前端 2 新建 + 2 修改）

| 文件 | 改动 | 说明 |
|---|---|---|
| `src/api/ipd/ai-copilot.ts` | +197 新建 | chatCopilot（POST /chat）+ streamCopilot（GET /chat/stream，fetch+ReadableStream 自解析 SSE：后端 sa-token 鉴权走 Authorization header，EventSource 不可用）+ createSseFrameParser 纯函数解析器（CRLF 归一化/半包粘包/冒号两形态） |
| `src/views/ipd/_shared/ai-assistant.vue` | +306 新建 | 全局浮钮 + 抽屉：流式渲染（delta 逐段）、sources 来源行、BR-AI-04 常驻风险提示、项目上下文 chip（ipd:current-project）、AbortController 关闭断流 |
| `src/layouts/ipd.vue` | +6 | 挂 `<AiAssistant />`（全 IPD 页面共享）+ 默认项目持久化修复（首访也写 localStorage，AI 上下文不再缺失） |
| `src/store/ipd-auth.test.ts` | +11 | 顺手修 HEAD 既有 5 红：@vben/stores mock 缺 setAccessMenus/setIsAccessChecked（7d3ed1a 加固后测试没跟上，五病根①存量） |

## 三证（2026-09-25 07:00 实测）

- vitest 全量：**1037 绿 / 0 失败**（新 7 + 顺手修 5；ai-copilot 7 测覆盖 URL/body/Bearer 头/SSE 四帧/半包/CRLF/非 event-stream 错误通道）
- vue-tsc：exit 0
- 契约门禁（contract-verify-batch3.json）：orphan_endpoints **78 → 77**，pass=True；
  剩余 AI 孤儿 2 条定性：`GET /api/v1/ai-copilot` = 扫描器误报（Controller 仅 @PostMapping("/chat") + @GetMapping("/chat/stream") 两个方法级映射，无类级 GET 端点）；`GET /ai-documents/{VAR}/history` = B4 批次范围

## HTTP 真活（ti-b3-copilot-live.txt，16039 直连）

- 登录 ipd-admin → chat 同步：code 0 / intent TASKS / **9 项真实待办** / sources 1 / 69ms——三档上下文（workbench summary）真活
- SSE 流式：200 text/event-stream；**meta（TASKS+9 项待办）→ delta（增量文本）→ done（ok/23ms）** 三帧与前端解析器完全对齐
- 负例：无 token → HTTP 500（NotLoginException 落 500，P0-13 同族既有问题）；前端 streamCopilot 已兜底为 onError「AI 副驾暂不可用」，不崩 UI

## 浏览器三证（batch3-*.png）

1. `batch3-ai-fab.png`：右下蓝色浮钮（data-testid=ipd-ai-fab，工作台「待我处理 9」同框）
2. `batch3-ai-drawer.png`：抽屉含 BR-AI-04 警告 Alert + 项目上下文 chip + 空态欢迎语 + 输入区
3. `batch3-ai-stream.png`：真实流式对话——用户气泡「我的待办有哪些」→ assistant「你有 9 项待办，下方按类型排序展示。」+ 来源行 workbench.tasks；SSE 200 无中断；console 0 新增错误

## 发现与登记

- 后端 SSE 契约不收 history（AiCopilotController.stream 构造 req 时 history 固定空列表）→ 前端组件多轮 UI 就绪、传参注释登记（后端扩展后前端一行接通）
- 首访项目上下文缺失（ipd.vue 仅显式切换才持久化）→ 本批已修（默认写 localStorage）
- 无 token SSE 落 500（应 401）→ 既有 P0-13 异常 advice 问题族，不新增卡

## 留库登记

- ai_audit 无新增落行要求（chat 走既有审计面）；本批纯前端 + 1 处 mock 修复，无业务表写入
