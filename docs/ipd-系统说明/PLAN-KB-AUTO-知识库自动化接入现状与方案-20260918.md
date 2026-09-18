# PLAN-KB-AUTO 子任务 1:知识库自动化接入现状盘点 + 4 组件方案(2026-09-18)

**卡号**:PLAN-KB-AUTO(关键事件自动沉淀知识库 + EvoMap 教训入库)
**UUID**:`762f65c9-5dfa-4915-9cc9-c1e35c5cba41`
**status**:inprogress
**触发**:R45 路线图统筹治理段,撞车 0 + 纯静态分析 + 不擅自动代码

---

## 一、知识库现状基线(2026-09-18 真活,R13 五必现查)

| 表/组件 | 行数/文件 | 真活现状 |
|---|---|---|
| ai_documents 表 | 4 行 | 4 篇 AI 文档,但都是手工创建 |
| ai_doc_embeddings 表 | **0 行** | ⚠️ RAG 知识库**真活未触发**,向量化为 0 |
| audit_logs 表 | 1538 行 | 关键事件流完整 |
| AiDocEmbeddingService | 274 行 | `embedAsync(doc)` + `retrieveContext(projectId, query)` 已就位 |
| AiDocumentService | 424 行 | 4 status:GENERATED/REVIEWED/REJECTED/ARCHIVED,关键门槛"AI 文档须人工审核确认才可归档" |
| AiDocumentController | - | POST + /{id}/revise + /{id}/versions/{versionId}/review + /{id}/versions |
| AiDocumentMapper + AiDocEmbeddingMapper | - | MyBatis-Plus Mapper 已就位 |
| AiDocEmbedding domain | 74 行 | doc_id + project_id + chunk_seq + chunk_text + vector_json |

**关键洞察**:
- RAG 知识库**代码已就位但真活未触发**(embeddings 0 行)
- 关键事件已全部在 audit_logs 留痕(无需新建埋点)
- 现有门槛"AI 文档须人工审核"是**有意设计**(防止 AI 直写库),自动归档需要**双轨设计**

---

## 二、PLAN-KB-AUTO 卡现状(2026-09-18 fresh)

- **已实现**:人工审核触发 ai_doc_embeddings 向量化(AI-STRAT-1 RAG)
- **EvoMap genes/capsules** 已有 R 轮次教训沉淀(手动)
- **audit_logs 事件流完整**(49 业务表)

---

## 三、audit_logs 关键事件分类(撞车 0 真活现查,R13 五必现查)

| action | 行数 | 业务环节 | AI 归档 ROI |
|---|---|---|---|
| LOGIN | 747 | 登录 | 低(高频低价值)|
| LOGIN_FAIL | 305 | 登录失败 | 中(异常模式识别)|
| KPI_SHARED_DEADLINE_REMIND | 40 | KPI 共担考核 | ★★★(KPI 趋势学习)|
| EXPORT | 34 | 数据导出 | 低(审计已完整)|
| GATE_ELEMENT_JUDGE | 33 | Gate 评审要素判定 | ★★★★(评审模式学习)|
| GATE_SIGN | 32 | Gate 签署 | ★★★★(决策模式学习)|
| SYSTEM_CONFIG_UPDATE | 27 | 参数变更 | ★★(配置影响面)|
| WECOM_MOCK_LOGIN_FAIL | 26 | Mock 登录失败 | 低(测试污染)|
| DELETE_REQUEST_SUBMIT | 24 | 删除申请提交 | ★★(异常模式)|
| DELETE_LEADER_APPROVE | 24 | 组长审批 | ★★(审批模式)|
| **BONUS_POOL_COMPUTE** | 22 | **奖金池核算** | ★★★★★(P3 核心算法模式)|
| WECOM_MOCK_LOGIN | 16 | Mock 登录 | 低(测试污染)|
| PASSWORD_CHANGE | 13 | 改密 | 低 |
| CERT_TPL_CREATE | 12 | 认证模板创建 | ★(低频)|
| DELETE_EXECUTE | 12 | 删除执行 | ★★ |
| FAILURE | 11 | 通用失败 | ★(异常统计)|
| GATE_REJECT | 10 | Gate 拒绝 | ★★★★(失败模式学习)|
| AI_COPILOT_CHAT | 8 | AI 副驾对话 | ★★★(用户意图学习)|
| KPI_SHARED_CONFIRM | 8 | KPI 共担确认 | ★★ |
| GATE_APPROVE | 8 | Gate 通过 | ★★★★(成功模式学习)|

**撞车 0 真活洞察**:
- 1538 行 audit_logs,**有归档价值的约 280 行**(GATE_* / BONUS_POOL / KPI_* / AI_COPILOT)
- 其余 1258 行(75%)为登录/失败/导出/测试污染,**低归档价值**

---

## 四、4 组件 × AI 知识库自动化接入方案(撞车 0 待 owner 拍板)

### 组件 1:AuditLogEventListener(撞车 0 ★★★★)

**目标**:监听 audit_logs INSERT,自动提取关键事件 → 写入 ai_doc_embeddings

**触发事件清单**(撞车 0 真活已分类):
- 高价值自动归档:GATE_ELEMENT_JUDGE / GATE_SIGN / GATE_APPROVE / GATE_REJECT / BONUS_POOL_COMPUTE / KPI_SHARED_*
- 中价值异步归档:AI_COPILOT_CHAT / SYSTEM_CONFIG_UPDATE / DELETE_*
- 低价值不归档:LOGIN / LOGIN_FAIL / WECOM_MOCK_* / EXPORT / PASSWORD_CHANGE

**反压控制**(撞车 0 + 性能):
- 单线程 executor(沿用 AiDocEmbeddingService 现有模式)
- 批量合并:每 5 秒合并一批(降写库压力)
- 失败重试:3 次指数退避,失败入 DLQ

**撞车 0 + 双轨设计**(沿用现有门槛):
- 路径 A:审计事件 → ai_doc_embeddings(向量检索,无须人工审核)
- 路径 B:审计事件 → ai_documents(留痕归档,须人工审核)
- 路径 A 不影响现有"AI 文档须人工审核"门槛

**撞车 0 让路 owner 派单**:EventListener 写代码涉及 AOP + 异步,撞车 0 不擅自开工

### 组件 2:KnowledgeAutoArchiver(撞车 0 ★★★)

**目标**:项目结项 / KPI 考核完成后自动归档项目摘要 + 决策链到 ai_documents

**触发事件**:
- Project 状态变更为 CLOSED → 自动归档项目摘要
- KpiRecord 创建 → 自动归档考核摘要
- Gate APPROVE(最后一轮)→ 自动归档 Gate 决策链

**Prompt 模板骨架**:
```
基于以下上下文生成项目摘要:
- 项目:{projectName}
- 阶段流转:{stageTransitions}
- Gate 决策:{gateDecisions}
- KPI 考核:{kpiRecords}
- 关键变更:{keyChanges}

输出:Markdown 摘要(500 字内,包含成功经验与失败教训)
```

**撞车 0 + 双轨设计**:
- 自动生成的 ai_doc 状态=GENERATED(未审核,不可用于决策)
- 须人工走 AiDocumentController /{id}/versions/{versionId}/review 才升 REVIEWED
- 保留现有"AI 文档须人工审核"门槛,撞车 0 不擅自降低

**撞车 0 让路 owner 派单**:Archiver 写代码涉及定时扫描 + 摘要生成,撞车 0 不擅自开工

### 组件 3:EvoMap 自动接入(撞车 0 ★★★★)

**目标**:每张卡翻 done 后自动提取教训 → 调 EvoMap SDK 入库(genome + capsule + provenance)

**EvoMap 概念**:
- **gene**:可复用最小教训单元
- **capsule**:一组相关 gene 的聚合,带场景标签
- **provenance**:基因来源(本卡 commit / 兄弟会话 commit / 历史事件)

**触发事件**:
- 看板卡 status 从 inprogress → done → 自动提取 lessons_learned
- 卡 desc 末尾追加 ## Lessons Learned 段 → 自动抽取关键句入库

**Schema 草稿**:
```json
{
  "gene_id": "R45-AI-001",
  "content": "撞车 0 守则:不擅自扩展 AiCopilotService 路径",
  "tags": ["撞车0", "AI融合", "守则"],
  "provenance": {
    "task_id": "65d3ad11-...",
    "commit_sha": "c2f271a2",
    "loop_round": 5
  },
  "capsule_id": "CAP-LOOP-001"
}
```

**撞车 0 + 接入门槛**:
- 撞车 0 暂不接 EvoMap SDK(R 轮次已有手动沉淀)
- 撞车 0 现状:每张卡 desc 末尾 ## Lessons Learned 段是关键事件手动沉淀

**撞车 0 让路 owner 派单**:SDK 接入涉及外部依赖 + 授权,撞车 0 不擅自开工

### 组件 4:RAG 增量同步(撞车 0 ★★)

**目标**:夜间 cron 扫描新增审计行 → 增量向量化(不重复已处理)

**撞车 0 + 真活洞察**:
- ai_doc_embeddings **0 行**,RAG 真活未触发
- 现有 AiDocEmbeddingService.embedAsync 已支持单文档增量
- 缺的是**批量增量 + 幂等保证**

**幂等保证方案**:
- ai_doc_embeddings 加 unique key `(doc_id, chunk_seq, embed_model)` — 撞车 0 待 owner 拍板加索引
- 夜间 cron:扫描 audit_logs create_time > 上次扫描时间 → 批量调 embedAsync
- 失败行入 ai_doc_embeddings_failed 表(可选)

**撞车 0 + 单线程反压**:
- 沿用现有 embedExecutor 单线程模式
- 单次批量 ≤ 100 行(防止 embedding API 限流)
- 撞车 0 不擅自改成多线程(撞车 0 + IPD 业务影响大)

**撞车 0 让路 owner 派单**:Cron 写代码涉及定时任务 + 增量扫描,撞车 0 不擅自开工

---

## 五、撞车 0 + 单会话能力边界 + 行动建议

| 子任务 | 影响面 | 推荐度 | 撞车 0 治理 |
|---|---|---|---|
| 子任务 1:4 组件 × 现状盘点 + 接入方案(本轮)| 低 | ★★★★★(已完成)| 纯 markdown + 真活 SELECT |
| 子任务 2:EvoMap SDK 接入现状(本轮)| 低 | ★★★★(已完成)| 撞车 0 暂不接 SDK,R 轮次手动沉淀 |
| 子任务 3:AuditLogEventListener(后续)| 中 | ★★★★| 等 owner 拍板 + 撞车 0 让路 worktree |
| 子任务 4:KnowledgeAutoArchiver(后续)| 中 | ★★★| 等 owner 拍板 + 撞车 0 让路 worktree |
| 子任务 5:RAG 增量同步 cron(后续)| 中 | ★★| 等 owner 拍板 + 撞车 0 让路 worktree |
| 子任务 6:EvoMap SDK 接入(后续)| 高 | ★★★| 撞车 0 不擅自接外部 SDK |

---

## 六、撞车 0 守则严守

- **本子任务纯静态分析**(grep + Read + 真活 SELECT),零代码改动
- **不擅自写 EventListener**(撞车 0 + 单会话能力边界)
- **不擅自降"AI 文档须人工审核"门槛**(撞车 0 + 现有设计有意)
- **不擅自接 EvoMap SDK**(撞车 0 + 外部依赖授权)
- **不擅自加 unique 索引**(撞车 0 + 待 owner 拍板)
- **不擅自改成多线程**(撞车 0 + IPD 业务影响大)

---

## 七、五必现查(R13)证据时间戳

- HEAD:`c2f271a2`(Loop 第 5 轮 PLAN-AI-FULL 子任务 1+2 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - audit_logs 总量 1538 行 / ai_documents 4 行 / ai_doc_embeddings **0 行** ⚠️
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-KB-AUTO status=inprogress(本子任务 1 已就绪)
- 主仓 working tree:1 个新文件(本轮 markdown)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 八、相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocEmbeddingService.java`(274 行)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocumentService.java`(424 行)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AiDocEmbedding.java`(74 行)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/AiDocEmbeddingMapper.java`
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/AiDocumentMapper.java`
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AiDocumentController.java`
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(路线图统筹治理段)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮)
- `docs/ipd-系统说明/PLAN-AI-FULL-49页AI融合现状与接入方案-20260918.md`(Loop 第 5 轮)