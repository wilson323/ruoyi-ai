# 恢复、幂等与故障闭环独立核验

状态：PARTIAL。2026-10-02；第七专业只读取源码、原始测试报告、现有独立 HTTP 验收，不执行 Maven、库写、DDL 或进程操作。

## 创建幂等

证据等级：SOURCE+UNIT。ProjectAgentRunService.create/replay；ProjectAgentRunServiceCreateTest 12/12；同人同键同摘要回原 run，不同摘要 STATE_CONFLICT，插入竞争释放额度后回读。

未验证：真实 HTTP 重发和双 JVM 同键竞争未由本审计执行。

## 终态与所有权

证据等级：SOURCE+UNIT。RunHandle.finishOnce、MybatisAgentRunStore.claimEpoch/lockEpoch；OwnershipTest 7/7、RedissonOwnershipTest 1/1、MiddlewareTest 1/1；终态有事件才回读，epoch CAS+租约防旧执行写入。

未验证：真实 JVM 崩溃后 DB/Redis/工具副作用联合恢复未验证。

## 原子产物收口

证据等级：SOURCE+UNIT。RunHandle L414-458：事务内 draft insert→状态 CAS→ARTIFACT→终态；失败抛异常；ProjectAgentRunService.applyArtifact READ_COMMITTED 锁版本并 markApplied；重复已应用回读原 documentId。

未验证：本审计未执行真实数据库事务故障注入或并发 apply。

## 恢复

证据等级：SOURCE+UNIT_ONLY。RunRecovery 不重放输入，仅成熟锁空闲且 EXECUTION_OWNER 的 PENDING/RUNNING/CANCEL_REQUESTED 收口；WAITING_APPROVAL 保留；旧 epoch fence；Redis integration 两条在本轮全量日志均 skipped。

未验证：双客户端测试源码即使启用也只是同 JVM 两客户端，不能算跨 JVM 恢复。

## SOURCE故障

证据等级：SOURCE+UNIT+PRIOR_HTTP。CompletionGate L48-74 unknown/FAILED/NO_HIT 不计命中，PARTIAL 只引用 citationStatus SUCCESS 的干净正文；独立业务验收 run3 保存本地8hit/MCP1hit均 SUCCESS metadata。

未验证：全故障矩阵（超时/断连/空响应/未知状态）真实 HTTP 尚未齐全。

## 跨运行返工文档链

证据等级：SOURCE+HTTP_CONFIRMED_GAP。独立 HTTP 回读两文档 2106050740358258690 REJECTED v1 与 2106051584776511490 GENERATED v1 各独立链；RunService.applyDraft 固定 createGeneratedAuthorized；AiDocumentService L223 固定 parent null/version1。

未验证：缺显式关联前尝试及目标文档基准的 AI 改版流程；不可宣告全局闭环。

## 必须修复的单链缺口

Explicitly bind rework to prior run/artifact/document and declared current base version; verify same project/action/authorized Person; lock existing chain head and append GENERATED AI revision with parent=head.id/version+1 and actual model/tokens; retain historical run/artifact. Never auto-merge unrelated documents merely by docType/title.

现有 AiDocumentService.revise 已有锁链头、基准版本 CAS 与 parent/version 写法，但它是人工改版并写 model=null，不能直接复用后丢失本次 AI 模型用量。无须新文档表或新执行轨；在现有 apply 和 ai_documents 链上补显式返工关联及授权改版。当前不能声称终审、跨 JVM 故障恢复或全项目闭环。

## 已实施的根因修复（待真实运行验收）

原请求新增显式 previousRunId/targetDocumentId/baseVersionId，并冻结在既有 config_snapshot JSON；无字段时保留旧摘要及构造调用兼容。服务端核本人、项目、动作、原运行终态、已定档产物目标关联；应用用原文档链锁与基准 CAS 新增 parent=head.id、version+1 的 GENERATED 版本，保留本次模型、token 和旧证据。仍保持 NOT_INDEXED，不开新表、不改旧运行、不自动按文档类型合链。

既有本人 run 详情返回 artifactArchives 权威关联。三栏产物添加「根据退回意见再做」，刷新后读取原 ai-documents versions 的已退回 HEAD 与意见，原 submitText→submit→create 路径携带动作与关联；普通消息不自动推断返工，不调用 apply 猜档案状态。

定向后端 39 tests、0 failure/error/skip；前端 57 tests 通过，包含只读回查、非退回拒绝与原发送路径动作/关联。日志见 JSON。当前源码修复不能替代 root 全量构建重载、真实原链 v2 回读；此前两条独立 v1 保留，不修改历史记录来制造单链。
