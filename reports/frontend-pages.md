# 前端 Vue 页面清单（R139 配套，2026-09-20）

> 抓取时间：2026-09-20（周日）
> 抓取命令：`find /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views -name "*.vue" -o -name "*.tsx"`
> 总文件数：242 个（含 ipd + 系统 + workflow + knowledge + chat + tool + agent + mcp + graph + dashboard + monitor + nodeManage + aiflow + _core）
> IPD 域页面：60 个（含 _shared 3 个 + 路由注册 49 个）
> 路由配置：apps/web-antd/src/router/routes/modules/ipd.ts（482 行）
> 路由注册：49 个 name + 49 个 path + 1 个 IpdPortal

## IPD 域页面（60 个 .vue 文件，按业务域）

| 域 | 页面数 | 关键文件 |
|---|---|---|
| ipd/_shared | 3 | backend-pending.vue（37 行）/ no-access.vue（21 行）/ three-state.vue（133 行） |
| ipd/auth | 3 | login.vue / account.vue / change-password.vue |
| ipd/workbench | 1 | workbench/index.vue |
| ipd/audit/logs | 1 | index.vue |
| ipd/project/list | 1 | list/index.vue |
| ipd/project/create | 1 | create/index.vue |
| ipd/project/legacy-import | 1 | legacy-import/index.vue |
| ipd/project/detail | 9 | index.vue + overview.vue + flow.vue + gates.vue + changes.vue + kpi.vue + incentive.vue + documents.vue + audit.vue + circle.vue |
| ipd/project/action-detail | 1 | index.vue |
| ipd/project/change-detail | 1 | index.vue |
| ipd/demand | 1 | index.vue |
| ipd/product/list | 1 | list/index.vue |
| ipd/product/edit | 1 | edit/index.vue |
| ipd/product/workspace | 1 | workspace/index.vue |
| ipd/product/catalog | 1 | catalog/index.vue |
| ipd/bid/list | 1 | list/index.vue |
| ipd/bid/create | 1 | create/index.vue |
| ipd/bid/respond | 1 | respond/index.vue |
| ipd/bid/select | 1 | select/index.vue |
| ipd/change | 1 | index.vue |
| ipd/review | 2 | index.vue + gate-panel.vue |
| ipd/timeline | 1 | index.vue |
| ipd/report | 1 | index.vue |
| ipd/handover | 1 | index.vue |
| ipd/kpi | 4 | index.vue + functional/index.vue + shared/index.vue + project-score/index.vue |
| ipd/incentive | 4 | allowance/index.vue + bonus-pool/index.vue + contribution/index.vue + negative-feedback/index.vue |
| ipd/ai-docs | 1 | index.vue |
| ipd/deletion | 3 | my-requests/index.vue + review/index.vue + archive/index.vue |
| ipd/admin | 7 | org/index.vue + config/index.vue + sop-template/index.vue + gate-elements/index.vue + identity-sync/index.vue + ai-models/index.vue + handover/index.vue |
| ipd/cert/templates | 1 | index.vue |
| ipd/portal | 3 | portal-shell.vue + submit/index.vue + status/index.vue |

## 路由注册清单（49 个 name + 49 个 path）

> 来源：apps/web-antd/src/router/routes/modules/ipd.ts 提取

| 路由 name | 路径 | 标题 |
|---|---|---|
| Ipd | /ipd | IPD 工作台 |
| IpdWorkbench | workbench | 我的工作台 |
| IpdAuditLogs | audit-logs | 审计日志（hideInMenu） |
| IpdProjects | projects | 项目空间 |
| IpdProjectCreate | projects/create | 新建项目 |
| IpdProjectLegacyImport | projects/legacy-import | 存量项目导入 |
| IpdProjectDetail | projects/:projectId | 项目详情（9 子页签） |
| IpdProjectOverview | :projectId/overview | 项目概览 |
| IpdProjectFlow | :projectId/flow | IPD 流程 |
| IpdProjectGates | :projectId/gates | Gate 评审（卡号 P0-10.23）|
| IpdProjectChanges | :projectId/changes | 需求与变更 |
| IpdChangeDetail | :projectId/change/:changeId | 变更单详情（卡号 P0-10.25） |
| IpdProjectKpi | :projectId/kpi | KPI 考核（卡号 P0-10.32） |
| IpdProjectIncentive | :projectId/incentive | 激励台账（卡号 P0-10.37） |
| IpdProjectDocuments | :projectId/documents | 文档与交付物 |
| IpdProjectAudit | :projectId/audit | 项目日志 |
| IpdProjectCircle | :projectId/circle | 协作圈 |
| IpdActionDetail | :projectId/actions/:actionId | 动作详情 |
| IpdRequirements | requirements | 需求管理 |
| IpdProducts | products | 产品空间 |
| IpdProductManage | products/manage | 产品管理 |
| IpdProductCreate | products/create | 新增产品 |
| IpdProductEdit | products/:productId/edit | 编辑产品 |
| IpdBids | bids | 研发招募 |
| IpdBidCreate | bids/create | 发起招标 |
| IpdBidRespond | bids/:bidId/respond | 应标 |
| IpdBidSelect | bids/:bidId/select | 遴选 |
| IpdChanges | changes | 变更管理 |
| IpdDocuments | documents | 资料库 |
| IpdReviews | reviews | 阶段确认 |
| IpdPerformance | performance | 协同绩效 |
| IpdTimeline | timeline | 全流程轨迹（卡号 ZK-D2） |
| IpdReports | reports | 报表分析 |
| IpdHandover | handover | 项目移交 |
| IpdKpi | kpi | KPI 考核（hideInMenu） |
| IpdKpiFunctional | kpi/functional | 功能 KPI |
| IpdKpiShared | kpi/shared | 共担 KPI 归集（卡号 P0-10.30） |
| IpdKpiScore | kpi/project-score | 项目绩效评定（卡号 P0-10.31） |
| IpdIncentive | incentive | 激励管理 |
| IpdAllowance | incentive/allowance | 津贴台账（卡号 P0-10.33） |
| IpdBonusPool | incentive/bonus-pool | 奖金池核算（卡号 P0-10.34） |
| IpdContribution | incentive/contribution | 贡献度评定（卡号 P0-10.35） |
| IpdNegativeFeedback | incentive/negative-feedback | 负反馈执行（卡号 P0-10.36） |
| IpdAiAssistant | ai-assistant | AI 文档助手 |
| IpdDeletion | deletion | 删除审核 |
| IpdDeletionMy | deletion/my-requests | 我的申请 |
| IpdDeletionReview | deletion/review | 待我审核 |
| IpdDeletionArchive | deletion/archive | 归档区 |
| IpdProductCatalog | product-catalog | 产品目录（仅超管） |
| IpdIdentitySync | identity-sync | 人员同步（卡号 P0-10.44，仅超管） |
| IpdAdmin | admin | 超级管理 |
| IpdAdminOrg | admin/org | 组织架构 |
| IpdAdminConfig | admin/config | 参数配置 |
| IpdAdminSop | admin/sop | SOP 模板 |
| IpdAdminGateElements | admin/gate-elements | Gate 评审要素 |
| IpdAdminCertTemplates | admin/cert-templates | 国别认证清单模板库 |
| IpdAdminAiConfig | admin/ai-config | AI 模型配置 |
| IpdAdminAuditLogs | admin/audit-logs-old | 审计日志（兼容路径） |
| IpdAdminHandover | admin/handover | 超级管理员移交 |
| IpdNoAccess | no-access | 无权访问 |
| IpdPortalSubmit | /portal/submit | 需求门户-提交需求 |
| IpdPortalTrack | /portal/track | 需求门户-查询进度 |

## 路由 ↔ 页面文件覆盖度
- 路由 49 个 → 页面文件 60 个（含 detail 子页签 9 个 + portal 2 个）
- 路由 :projectId 路由对应 9 个子页签（overview/flow/gates/changes/kpi/incentive/documents/audit/circle）
- 部分路由 hideInMenu（按 ZK-IPD 原型对齐裁决 D2）
