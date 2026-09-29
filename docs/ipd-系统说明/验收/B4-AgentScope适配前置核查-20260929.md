# B4 AgentScope 适配·七消费者迁移前置核查（2026-09-29）

> 结论：**BLOCKED（跨路口 + owner 待拍）**，非本路可独立闭环。本文固化前置事实与阻塞证据，B4 行「先让副驾经 ai_model_configs 使用真实模型与可信 AgentScope 端口」的两个前置当前均不满足。

## 1. 七消费者清单与合同面现状（磁盘实证）

| # | 消费者 | 文件 | 现依赖 | owner 裁决（f8afc463，C0 §6.3） |
|---|---|---|---|---|
| 1 | AiCopilotService | `ruoyi-modules/ruoyi-ipd/.../service/AiCopilotService.java` | AiGateway | **进内核**（C2 统一模型权威后执行包装） |
| 2 | CopilotKit/AG-UI（AgUiCopilotRun/AgUiFrameTranslator/CopilotKitRuntimeController） | `.../copilotkit/` | 自研 AG-UI 翻译层 | **进内核**（与官方 `agentscope-extensions-agui` 对照收口） |
| 3 | AiGenerationService | 存在 | AiGateway | 保留 AiGateway 直调面 |
| 4 | AiSuggestionService | 存在 | AiGateway | 同上 |
| 5 | GatePrecheckService | 存在 | AiGateway | 同上 |
| 6 | BidAiCompareService | 存在 | AiGateway | 同上 |
| 7 | BidResponseCheckService | 存在 | AiGateway | 同上 |
| 8* | AiDocEmbeddingService | 存在（c9bbc5fd 卷入兄弟 REVIEWED 过滤改进，定向测试 14/14 绿） | AiGateway.embed | 保留直调面 |

原合同面在 AiGateway 的实现状态：**同步** chat ✓ / **流式** stream（SSE 回调）✓ / **embedding** embed 批量 ✓ / **审核** REVIEWED 状态链（AiDocEmbeddingService 检索过滤）✓ / **审计** audit_logs ✓ / **取消 ✗**：`CopilotKitRuntimeController`/`AgUiCopilotRun`/`AiGateway` 三处 grep cancel/abort 零命中——AG-UI 要求 RUN_ERROR/RUN_FINISHED 互斥终态 + 客户端 abort 语义，B 侧未接。

## 2. 前置缺口（阻塞项，均现查实证）

| # | 缺口 | 证据 | 解除条件 |
|---|---|---|---|
| P1 | **可信 AgentScope 端口不存在**：PoC 树已 tag 归档退役（`archive/poc-agentscope-kernel-20260929` → d8231596），工作树已 remove；主树 `ruoyi-ipd` 零 `io.agentscope` 引用（自研 harness 双轨禁令仍在） | f8afc463 C0 §6.1；AGENTS.md「本仓已有自研 harness，禁双轨」 | C1 下一切片 / C4 接线证据落地后重开内核接入点，须 ADR 三选一 |
| P2 | **ai_model_configs 无真实激活模型**：10 条中唯一 `is_active=1` 是 `TEST/test-rag` → `http://127.0.0.1:8765`（mock）；真实模型仅 1 条 MiniMax-M3（`https://api.minimax.cn/v1`，key 密文 172B，`is_active=0`）；另存 `ssrf-probe`（169.254 安全负例产物，应保持禁用） | 真库 `ai_model_configs` 现查（不打印密文） | owner 拍板：验证 MiniMax key 有效性并激活（有费用副作用，B 不擅动）；或提供正式模型凭证配置 |
| P3 | **B 侧 run 取消/终态互斥未实现**（=B0 §3 缺口 G1） | grep 零命中 | AI 任务取消端点 + AG-UI 终态合同测试（负控会红）先于项目执行智能体开放 |
| P4 | C2 统一模型权威未落地 | C0 裁决文义「C2 后执行包装」 | C 路交付 |

## 3. B4 行其余要求的状态

- 「不能把 CopilotKit 包装算作独立完成入口」：确认——本轮未将 copilotkit 包的任何改动申报为 B4 完成；包装仅按裁决作为进内核前的对照收口对象。
- 「保留同步、流式、embedding、取消、审核和审计原合同」：除取消（P3）外五面在 AiGateway/状态链有实现，迁移时须逐面带契约测试；当前无任何迁移动作发生（0/2 进内核消费者已迁移）。

## 状态判定

**B4 = BLOCKED_ENVIRONMENT/CROSS-TRACK**：P1/P2/P4 分别依赖 C 路内核接入点、owner 模型凭证拍板、C2 统一模型权威；P3 是 B 侧可先行实施的独立切片（建议单独立卡：取消端点 + 终态互斥契约测试），不依赖外部。本轮如实标阻塞，不伪完成。

## 证据索引

- git：f8afc463（C0 拍板回写）、c9bbc5fd（EmbeddingService 接手入库，14/14 测试 /tmp/b2-embed-fixup-test-20260929.log）
- DB：`ai_model_configs` 10 行现查（active=TEST mock 8765；MiniMax is_active=0 key_len=172）
- 代码：grep cancel/abort @ CopilotKitRuntimeController / AgUiCopilotRun / AiGateway = 0 命中；`find` 七消费者文件全部存在
