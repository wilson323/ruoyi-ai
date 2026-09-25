# AI 融合现状盘点（R215 · 2026-09-24，owner 指令「确保所有功能充分与 AI 能力融合」）

对照基线方案：`docs/ipd-系统说明/IPD全量AI协助功能完整方案-20260923.md`（Layer 0-3）

## 一、结论

**最大断点是 Copilot 对话入口未接**：后端 Layer 0 已建成（2026-09-23 AI-STRAT-1 Phase 2），
前端无 ai-copilot 任何引用（三个 AI 端点孤儿）→ 已由批次3（B3）接线。

> **修正（2026-09-25，本文档初版曾错报）**：初版写「ai-document.ts 封装零调用（假接线）」系误报——
> 当时 grep 的 shell cwd 漂到了后端仓（五必现查规约重踩）。实际 ai-document.ts 被 5 个视图使用
> （ai-docs/index.vue 第 42 页 + 项目详情 documents/changes/circle/index），10 端点中 9 个已接，
> 唯一未接的是 GET /{id}/history。本文档以下表格已按磁盘现态重核。

## 二、事实基线（全部 fresh 实测）

### 后端 AI 能力面（已建成）

| 能力 | 位置 | 状态 |
|---|---|---|
| Copilot 同步对话 | `POST /api/v1/ai-copilot/chat` | ✅ 建成，**前端孤儿** |
| Copilot SSE 流式 | `GET /api/v1/ai-copilot/chat/stream` | ✅ 建成，**前端孤儿** |
| Copilot 会话列表 | `GET /api/v1/ai-copilot` | ✅ 建成，**前端孤儿** |
| 三档上下文（项目/个人/RAG） | `AiCopilotService`（L39-42，AI-STRAT-1 Phase 2） | ✅ workbench summary + AiDocEmbedding.retrieveContext |
| 越权拦截 | `AiCopilotService.assertProjectVisible`（L92） | ✅ BR-AI-05 内部角色对等≠跨项目越权 |
| AI 文档全生命周期 | `AiDocumentController` 10 端点（generate/revise/review/versions/history/archive/reject/diff/list/create） | ✅ 后端建成；**前端 9/10 已接**（第 42 页 ai-docs + 项目详情 4 视图在用），仅 history 未封装 |
| 统一调用层 | `AiGateway`（Langchain4j chat/streaming/embedding） | ✅ AI-STRAT-2 |
| 模型配置 | `ai_model_configs` is_active=1（R183 commit 21844113） | ✅ |
| 审计规约 | `AI-审计三件套规约-20260923.md`（commit 69e2da11） | ✅ |

### 前端引用面（B3 前现状，已修正重核）

- `api/ipd/ai-document.ts`：✅ 已被 5 个视图使用（非初版误报的零调用）
- `api/ipd/ai-model-config.ts`：调用方待查（不在 B3 范围）
- `ai-copilot`：B3 前视图层零引用（该条成立，B3 已新建接线）
- 无页面级「AI 帮写/AI 建议」入口（Layer 2，待拍板）

### 产品圣经 BR-AI-01~06 合规现状

| 规则 | 后端 | 前端 |
|---|---|---|
| BR-AI-01 模型配置 | ✅ | ⚠️ 有 api 封装，视图待查 |
| BR-AI-02 PM 审核后归档 | ✅ review 端点 | ✅ ai-docs 页 review/reject/archive 在用 |
| BR-AI-03 版本链 | ✅ versions/diff | ✅ ai-docs 页 listVersions + getDiff 在用 |
| BR-AI-04 不做内容过滤+UI风险提示 | ✅ | ✅ ai-docs 页在用（具体程度待逐页核） |
| BR-AI-05 权限对等+审计 | ✅ | — |
| BR-AI-06 关联项目/阶段 | ✅ | ✅ listByProject 在用；❌ history 端点未接（B4 补齐） |

## 三、Layer 0-3 推进对照

| Layer | 方案估算 | 现状 | 差距 |
|---|---|---|---|
| L0 基础夯实 | 3-5 人天 | 后端 ✅ 全完成；**前端 0%** | Copilot 前端接线（批次3 范围） |
| L1 docType 模板 | 5 人天 | 后端 docType 字段已有；8 类模板齐备度待查 | 低优先 |
| L2 每页 AI 入口 | 19.5 人天/19 页 | `AiSuggestionController/Service` 不存在；前端组件不存在 | **待 owner 拍板**（方案 §5：核心 5 页 or 19 页全做） |
| L3 Agent 网关 | 15-20 人天 | 未启动 | 待 owner 拍板 + 单独 worktree |

## 四、推进路径（本盘点建议）

1. **批次3（已完，commit bca8517）**：`ai-copilot.ts` 封装 + `AiAssistFab/Drawer` 全局组件 → 全产品页面获得统一 AI 副驾入口，消 2 个真孤儿端点。
2. **批次4（范围修正后缩小）**：唯余 `GET /ai-documents/{id}/history` 一条孤儿端点的封装+视图接入（ai-docs 页操作历史区），
   非初版规划的「文档工作台全接线」（那部分已在用）。
3. **Layer 2/3**：维持方案「待 owner 拍板项」，不在本轮擅动（涉及新后端 Service、DDL、权限面）。

## 五、验证

- 孤儿清单：`contract-verify-batch2.json` summary.orphan_endpoints_count=78（AI 3 条在列：copilot×2 + history×1）
- 文档接线重核：`grep -rln api/ipd/ai-document views/`（在前端仓执行）→ 5 视图在用；ai-docs/index.vue 调用 8 封装函数+getDiff+registerAiDocument
