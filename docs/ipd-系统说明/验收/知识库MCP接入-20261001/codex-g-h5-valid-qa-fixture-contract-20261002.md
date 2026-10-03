# G H5 与有效 QA 阶段/动作夹具合同

DRAFT_ONLY，未执行。只读源码和ipd_dev查询，不开工、不审批、不生成、不迁移，不新建台账。

## 当前 H5 实证

实时ai_documents：9140005旧链头2105727025791688706为REJECTED（保留退回意见），2105722878296174594仍GENERATED；不能把旧待审核稿作为新尝试通过。另项目2103659612308828162文档2106061816428781569为REJECTED v2，parent2106051584776511490；它是新运行2106082915170418689配置中的target/base，所属项目不同，不能混称9140005原链返工。

主路径仍ProjectAgentController创建run，previousRunId/targetDocumentId/baseVersionId在原合同校验后新尝试；apply走现产物→AiDocumentService GENERATED，review/reject/archive走既有版本链；当前DRAFT/docnull不等同正式文档。有效质量→关联新尝试→独立审核→动作批准→Gate的先后不改变，禁止替真实业务审批。

## 当前 QA 与最小合法路径

只读name QA范围当前仅2106072674957529089「QA-CT3负责人非成员项目」：PENDING_START、productId=null、stages0/actions0；未找到可直接复用有效图的QA项目。Person900101超管是原创建人/项目member；900102真实GROUP_LEADER/group900001为原非member。原QA线2106072393398095874停用、负责人900101；它不能被当作正在工作的业务线。

ProjectController的POST /api/v1/projects/{id}/approve-start由ProjectStartService校验产线负责人（无负责人仅超管），从PENDING_START→TEAMING；无产品先建INRD项目在研产品，绑定原产品线，再bootstrap建立6个project_stages、ActionCatalog69个stage_actions及原证书同步/审计。这是合法开工路径，不直接INSERT阶段/动作，不伪造已批准动作或Gate。

可执行合同需先批准独立QA夹具范围：仅原QA项目/线/空间成员的可恢复临时设置（同组900001、900102为负责人/在职空间成员但仍非项目member）；900102真实approve-start审批后生成图。接着900102以非member身份对已有CONCEPT stage提交/推进，应403且stage/project/audit/文档/运行零增改。读用项目/stages与ai-documents列表，禁止错误详情路由。开始前保留全部原QA行与关联计数，结束准确恢复原可改字段；合法开工产生的产品、阶段、动作、证书、审计不能靠删除回滚，须保留并停用独立QA范围，不得声称零新增。

## 两个必须先解决的边界

1. bootstrap当前源码会调用AiExecReviewHook.onBootstrapped，自动调度已接线AI动作。故仅approve-start并不能证明无模型外呼；在当前「不发模型」范围下，这条写入实验不得执行。不得私自关配置/隐藏调度或构造已完成状态。A需要单独授权隔离QA调度环境/副作用预算后才执行，或找到经证据确认已经有图的QA项目。
2. 小阶段accept入口在成员守卫之前检查action.status==DONE。新bootstrap动作并未完成，直接accept只会400，不能验成员负例。需QA成员按实际动作合同完成一个小动作并提交（轻动作fields实际完成日→transit DONE，深动作真实交付物→原审核），保留完成证据后才让900102非member调用/api/v1/stage-actions/{actionId}/accept，期待403。不得直接SQL设DONE/确认人、伪造交付物或业务批准。无实际QA完成证据时，小动作非member批准负例保持未验。

表闭包：projects/products/product_lines/product_line_members/project_members/project_stages/stage_actions、原项目证书与audit_logs、可能ai_agent_tasks自动调度；无需DDL。不能把无DDL误等同零副作用。当前QA未具备完整图、无模型限制与开工hook冲突，因此本合同仍DRAFT_ONLY，不将生产源码存在/bootstrap单测当真实夹具验收。
