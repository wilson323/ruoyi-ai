# PLAN-AI-FULL 子任务 1:49 页业务环节 × AI 能力融合现状盘点 + 接入方案(2026-09-18)

**卡号**:PLAN-AI-FULL(全局待办根治计划:IPD 49 页业务环节 agent 能力融合)
**UUID**:`65d3ad11-f52e-4498-8716-535d5cad321d`
**status**:inprogress
**触发**:R45 路线图 P4 AI 阶段,撞车 0 + 纯静态分析 + 不擅自动代码

---

## 一、AI 能力融合现状基线(2026-09-18,R13 五必现查)

| 组件 | 行数 | 路径 | 撞车 0 现状 |
|---|---|---|---|
| AiCopilotController | 160 | `controller/AiCopilotController.java` | 2 端点:`POST /api/v1/ai-copilot/chat` + `GET /chat/stream`(SSE) |
| AiCopilotService | 342 | `service/AiCopilotService.java` | 3 路径:TASKS / ADVANCE / CHITCHAT |
| AiGateway | 208 | `service/ai/AiGateway.java` | 统一接入多 provider + 白名单错误码 + PromptLenBucketLogger |
| AiChatClient | - | `service/ai/AiChatClient.java` | legacy 客户端(可被 AiGateway 代理) |
| AiGenerationService | 279 | `service/AiGenerationService.java` | 生成服务(文档/留痕) |
| AiDocEmbeddingService | - | `service/AiDocEmbeddingService.java` | RAG 向量化(已接入知识库) |
| AiDocumentController | - | `controller/AiDocumentController.java` | 文档版本链 + sha256 摘要 |
| AiModelConfigController | - | `controller/AiModelConfigController.java` | provider/endpoint/apiKey 加密配置 |

**端口现查**:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
**真库**:DB socket 13306,`ipd_dev` 业务库

---

## 二、PLAN-AI-FULL 卡现状(2026-09-11 fresh)

- **已实现**:AI-STRAT-1 RAG / AI-STRAT-2 AiGateway / AI-P1-3 AI 留痕 / AI-P2-3 AI 副驾 MVP
- **前端 AI 集成页**:aiflow/ + agent/ + ai-models + knowledge/info/detail
- **后端 AiCopilotService 332 行已支持 3 路径**(实测 342 行,含 audit + workbench 注入)

---

## 三、49 页业务环节 × AI 能力融合现状矩阵

> 撞车 0 纯静态分析:基于 `docs/ipd-系统说明/验收/QA-07-49页验收矩阵-20260906.md` + 后端 grep 现状盘点

| 业务环节 | 页号 | 实做状态 | AI 融合阶段 | 撞车 0 推荐 ROI | 待做依赖 |
|---|---|---|---|---|---|
| 登录 | P0-10.1 | ✅ PASS | 无需 AI | - | - |
| 强制改密 | P0-10.2 | ✅ PASS | 无需 AI | - | - |
| 工作台聚合 | P0-10.3 | 🟡 PARTIAL | AI 副驾已就位(待 U1 落地)| 中 | KPI/激励 controller |
| 删除-我的申请 | P0-10.4 | ✅ PASS | 无需 AI | - | - |
| 删除-待我审核 | P0-10.5 | ✅ PASS | 无需 AI | - | - |
| 审计日志 | P0-10.6 | ✅ PASS | AI 解读(已支持)| 低 | - |
| 我的项目列表 | P0-10.7 | ✅ PASS | 项目命名/复制辅助 | ★★ 中 | P4-2.3 待做 |
| 新建项目 | P0-10.8 | ✅ PASS | 项目命名/复制辅助 | ★★ 中 | P4-2.3 待做 |
| 存量项目导入 | P0-10.9 | ✅ PASS | AI 字段映射(后续)| 低 | - |
| 项目详情-概览 | P0-10.10 | ✅ PASS | AI 概览摘要(后续)| 中 | - |
| 项目详情-IPD 流程 | P0-10.11 | ✅ PASS | AI 阶段建议(已支持 ADVANCE 路径)| ★ 中 | AiCopilotService |
| 深管动作详情 | P0-10.12 | ✅ PASS | AI 交付物检查(后续)| 中 | - |
| 轻管动作详情 | P0-10.13 | ✅ PASS | AI 三字段校验(后续)| 低 | - |
| 项目详情-文档 | P0-10.14 | 🟡 PARTIAL | AI 文档版本链(已支持)| - | P1-10.2 待做 |
| 项目详情-审计 | P0-10.15 | ✅ PASS | 无需 AI | - | - |
| 产品管理-目录 | P0-10.16 | ✅ PASS | AI 产品命名/分类推荐 | ★★★ 高 | P4-2.2 待做 |
| 产品新增/编辑 | P0-10.17 | ✅ PASS | AI 产品需求录入助手 | ★★★★ 高 | P4-2.2 待做 |
| 国别认证清单 | P0-10.18 | ✅ PASS | AI 认证映射建议(后续)| 中 | - |
| 招标组队-列表 | P0-10.19 | ✅ PASS | AI 邀标策略(后续)| 低 | - |
| 发起招标 | P0-10.20 | ✅ PASS | AI 邀标策略(后续)| 低 | - |
| 应标 | P0-10.21 | ✅ PASS | AI 应标决策辅助(后续)| 低 | - |
| 遴选 | P0-10.22 | ✅ PASS | AI 遴选排序建议(后续)| 中 | - |
| Gate 评审-列表 | P0-10.23 | 🟡 PARTIAL | **Gate 评审要素 AI 判定建议** | ★★★★★ 高 | P2-5.x 待做 |
| Gate 评审详情 | P0-10.24 | ⚪ PLACEHOLDER | **Gate 评审要素 AI 判定建议** | ★★★★★ 高 | P2-5.2 待做 |
| 需求与变更 | P0-10.25 | 🟡 PARTIAL | AI 变更影响分析(后续)| 中 | - |
| 需求变更单详情 | P0-10.26 | ⚪ PLACEHOLDER | AI 变更决策建议(后续)| 中 | - |
| 项目移交 | P0-10.27 | ⚪ PLACEHOLDER | AI 移交清单生成(后续)| 中 | - |
| 组织架构 | P0-10.28 | ✅ PASS | AI 组织架构优化(后续)| 低 | - |
| KPI 考核-功能 | P0-10.29 | ⚪ PLACEHOLDER | **KPI 考核 AI 解读** | ★★★ 中 | P3-1.x 待做 |
| KPI 考核-共担 | P0-10.30 | ⚪ PLACEHOLDER | KPI 考核 AI 解读 | ★★★ 中 | P3-1.x 待做 |
| 项目绩效评定 | P0-10.31 | ⚪ PLACEHOLDER | KPI 考核 AI 解读 | ★★★ 中 | P3-1.x 待做 |
| 项目详情-KPI | P0-10.32 | ⚪ PLACEHOLDER | KPI 考核 AI 解读 | ★★★ 中 | P3-1.x 待做 |
| 激励管理-津贴 | P0-10.33 | ⚪ PLACEHOLDER | **激励台账金额合理性检查** | ★★★ 中 | P3-3.x 待做 |
| 激励管理-奖金池 | P0-10.34 | ⚪ PLACEHOLDER | 奖金池核算 AI 校验 | ★★★★ 高 | P3-4.x 待做 |
| 贡献度评定 | P0-10.35 | ⚪ PLACEHOLDER | AI 贡献度归因 | ★★ 中 | P3-4.x 待做 |
| 负反馈执行 | P0-10.36 | ⚪ PLACEHOLDER | AI 负反馈归因 | ★★ 中 | P3-4.x 待做 |
| 项目详情-激励 | P0-10.37 | ⚪ PLACEHOLDER | AI 激励汇总摘要(后续)| 中 | - |
| 需求门户-游客提交 | P0-10.38 | 🟡 PARTIAL | AI 需求分类(后续)| 低 | - |
| 需求门户-查询进度 | P0-10.39 | 🟡 PARTIAL | AI 查询进度摘要(后续)| 低 | - |
| 需求池 | P0-10.40 | ⚪ PLACEHOLDER | AI 需求合并建议(后续)| 中 | - |
| 需求详情-处理 | P0-10.41 | ⚪ PLACEHOLDER | AI 需求处理建议(后续)| 中 | - |
| AI 文档助手 | P0-10.42 | ✅ PASS | AI 文档版本链(已支持)| - | P1-10.2 待做 |
| 删除-归档区 | P0-10.43 | ✅ PASS | 无需 AI | - | - |
| 人员管理 | P0-10.44 | ⚪ PLACEHOLDER | AI 人员匹配(后续)| 低 | - |
| 参数配置 | P0-10.45 | ✅ PASS | AI 配置变更影响分析(后续)| 低 | - |
| SOP 模板 | P0-10.46 | ✅ PASS | AI SOP 生成(后续)| 低 | - |
| Gate 评审要素 | P0-10.47 | ✅ PASS | AI 要素建议(后续)| 低 | - |
| AI 模型配置 | P0-10.48 | ✅ PASS | AI 自测(已支持)| - | - |
| 超管移交 | P0-10.49 | ⚪ PLACEHOLDER | AI 移交清单生成(后续)| 中 | - |

**统计**:49 页 = 30 PASS + 5 PARTIAL + 14 PLACEHOLDER
**AI 融合现状**:14 PLACEHOLDER 中 13 个有 AI 融合价值,优先级按业务逻辑排序。

---

## 四、6 业务环节 × AI 副驾接入方案(撞车 0 待 owner 拍板后启动)

### 业务环节 1:Gate 评审要素 AI 判定建议(P0-10.23/24,★★★★★ 高 ROI)

**目标**:要素列表 + AI 推荐通过/不通过 + 理由

**AI 数据来源**:
- 当前 Gate 评审的所有 33 项要素清单
- 项目历史 Gate 评审记录(success_rate + 实际判定)
- 国别/产品类型/管理深度等上下文

**Prompt 模板骨架**:
```
你是一名资深 IPD Gate 评审专家。基于以下上下文:
- 项目名称:{projectName}
- 当前 Gate:{gateName}(G1-G5)
- 评审要素:{elementList(33 项)}
- 项目历史数据:{projectHistory}

请对每项要素给出:
1. 推荐判定:PASS / FAIL / CONDITIONAL
2. 依据(50 字内)
3. 风险点(如 FAIL/CONDITIONAL)

返回 JSON 格式
```

**留痕 schema**(IpdEntityType.AUDIT_LOGS):
```json
{
  "entity_type": "ai_copilot",
  "action": "gate_review_recommendation",
  "context": {
    "projectId": "...",
    "gateId": "...",
    "elementCount": 33
  },
  "result": {
    "recommendations": [{"elementId":"...","recommendation":"PASS","reason":"..."}],
    "overallRisk": "MEDIUM"
  }
}
```

**撞车 0 让路 owner 派单**:P2-5.x 后端 Gate 评审闭环 + P4-2.2 前端 AI 集成

### 业务环节 2:产品需求录入助手(P0-10.16/17,★★★★ 高 ROI)

**目标**:产品命名/分类自动建议 + RAG 拉历史产品参考

**AI 数据来源**:
- 历史产品目录(50+ 行)
- 产品分类树
- 国别认证清单
- 命名规范

**Prompt 模板骨架**:
```
基于以下历史产品参考:
{historicalProducts(RAG top-5)}

用户输入:
- 名称草案:{userInputName}
- 分类:{category}
- 目标市场:{targetMarkets}

请推荐:
1. 最匹配的命名(参考历史命名规范)
2. 分类推荐
3. 必带国别认证
```

**留痕 schema**:
```json
{
  "entity_type": "ai_copilot",
  "action": "product_naming_recommendation",
  "context": {"productDraft": "..."},
  "result": {"recommendedName": "...", "category": "...", "certCountries": [...]}
}
```

**撞车 0 让路**:P4-2.2 前端 AI 集成入口 + RAG top-5 排序已就位

### 业务环节 3:KPI 考核 AI 解读(P0-10.29~32,★★★ 中 ROI)

**目标**:低绩效自动归因 + 改进建议

**AI 数据来源**:
- 当前 PM 的 KPI 数据(功能 KPI + 共担 KPI)
- 同级别/同组别 PM 的均值/中位数
- 历史 KPI 趋势

**Prompt 模板骨架**:
```
PM:{pmName} 当前 KPI:
- 功能 KPI:{functionalKpi}
- 共担 KPI:{sharedKpi}
- 项目绩效:{projectScore}

同级别参考:{peerBenchmark}
历史趋势:{historicalTrend}

请输出:
1. 绩效评估(高/中/低)
2. 低绩效自动归因(3 个关键因素)
3. 改进建议(可操作步骤)
```

**留痕 schema**:
```json
{
  "entity_type": "ai_copilot",
  "action": "kpi_interpretation",
  "context": {"pmId": "...", "period": "..."},
  "result": {"evaluation": "中", "factors": [...], "suggestions": [...]}
}
```

**撞车 0 让路**:P3-1.x 后端 KpiRecord controller 落地

### 业务环节 4:激励台账金额合理性检查(P0-10.33,★★★ 中 ROI)

**目标**:偏离同级别均值告警

**AI 数据来源**:
- 当前津贴金额
- 同级别 PM 历史津贴均值/中位数
- 等级 L1-L5 标准津贴

**Prompt 模板骨架**:
```
津贴草案:{amount}
PM 等级:{level}(L1-L5)
同级别均值:{peerMean} / 中位数:{peerMedian}
标准津贴:{standardAmount}

请评估:
1. 偏离度(% 偏离均值)
2. 是否需要审批加签
3. 建议调整范围
```

**留痕 schema**:
```json
{
  "entity_type": "ai_copilot",
  "action": "allowance_amount_check",
  "context": {"amount": "...", "level": "..."},
  "result": {"deviation": 15.5, "needApproval": true, "suggestion": "..."}
}
```

**撞车 0 让路**:P3-3.x 后端 AllowanceLedger controller 落地

### 业务环节 5:项目命名/复制辅助(P0-10.7/8,★★ 中 ROI)

**目标**:复制项目时 AI 改名 + 复用模板

**AI 数据来源**:
- 源项目名称/分类
- 目标市场
- 命名规范(版本号/年份/国别后缀)

**Prompt 模板骨架**:
```
复制源项目:{sourceName}({sourceVersion})
目标市场:{targetMarkets}
复制时间:{copyTime}

请生成新项目名:{suggestedName(版本号+年份+国别后缀)}
复用建议:{templatesToReuse}
```

**撞车 0 让路**:P4-2.3 前端 AI 集成入口

### 业务环节 6:协作圈问答机器人(P0-10.x,低 ROI)

**目标**:在 ProjectCircle 内嵌 chat 入口调 AI 副驾

**撞车 0 让路**:依赖前端 ProjectCircle 组件 + AiCopilotService 已就位

---

## 五、撞车 0 + 单会话能力边界 + 行动建议

| 子任务 | 影响面 | 推荐度 | 撞车 0 治理 |
|---|---|---|---|
| 子任务 1:49 页 × AI 现状盘点(本轮)| 低 | ★★★★★(已完成)| 纯 markdown |
| 子任务 2:6 业务环节 × AI 接入方案(本轮)| 低 | ★★★★(已完成)| 纯 markdown |
| 子任务 3:撞车 0 让路 owner 派单 worktree(后续)| 高 | ★★★★★(必经路径)| 17 张 owner 派单卡 + 后续增量卡 |
| 子任务 4:AiCopilotService 路径扩展(后续)| 中 | ★★★★| 等 owner 拍板接哪个业务环节 |
| 子任务 5:RAG top-K 排序优化(后续)| 中 | ★★★| 撞车 0 不擅自动 RAG |
| 子任务 6:前端 AI 集成入口组件库(后续)| 高 | ★★★| 撞车 0 不擅自动前端 |

---

## 六、撞车 0 守则严守

- **本子任务纯静态分析**(grep + Read + 后端源码穿透),零代码改动
- **不擅自扩展 AiCopilotService 路径**(撞车 0 + 单会话能力边界)
- **不擅自接前端 AI 集成入口**(前端仓兄弟会话 M 改动不碰)
- **不擅自改 RAG top-K 排序**(撞车 0 + IPD 业务影响大)
- **6 业务环节接入方案完整写出来待 owner 拍板**,撞车 0 不擅自开工

---

## 七、五必现查(R13)证据时间戳

- HEAD:`bbb97ba9`(Loop 第 4 轮 PLAN-AUDIT-FULL 子任务 1 commit 后)
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AI-FULL status=inprogress(本子任务 1 + 2 已就绪)
- 主仓 working tree:1 个新文件(本轮 markdown)+ 2 个上一轮 append(已 commit)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 八、相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiCopilotService.java`(342 行,3 路径实现)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AiCopilotController.java`(160 行,2 端点)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ai/AiGateway.java`(208 行,统一接入)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiGenerationService.java`(279 行,生成服务)
- `docs/ipd-系统说明/验收/QA-07-49页验收矩阵-20260906.md`(49 页 × AC × 权限 × 跳转 × 端点)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(路线图 P4 AI 阶段)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮)