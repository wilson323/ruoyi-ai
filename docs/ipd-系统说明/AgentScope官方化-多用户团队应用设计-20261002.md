# AgentScope 官方化 · 多用户与团队应用设计（2026-10-02）

> 版本：1.1.0。定位：六计划总览 v1.1.0 之 P5，设计文档（多数能力已落地为代码，本文做**现状固化 + 缺口收口设计**，标注每条的落地状态）。DRAFT → 缺口部分待 owner 拍板。
> 依据：owner 2026-10-02 拍板「harness 按官方来、特性基于官方之上优化」+ AgentScope 2.0.3 官方隔离原语（RuntimeContext / StateStore / 四维会话键）+ 本仓已实证代码。
> 编号注意：与《AgentScope官方化迁移矩阵》§五的 P5（Channel 专项）不是同一体系；Channel 与 WebSocket 对照属总矩阵 P5，本文不重复。

## 一、设计基线：官方原语与本项目形态

本专项重点核验的 AgentScope 多用户基础对象有三类：`RuntimeContext(userId/sessionId)`、会话键、StateStore 隔离。本项目在其上收口为**四维隔离键**并落了产品级权限——原则：官方原语管「进程内隔离」，产品语义（谁能看谁的项目/运行/产物）一律在业务层，不塞进 agent 内核。

```
KernelScopeKey.of(projectId, userId, agentId, sessionId)
  → slotId "p{projectId}:u{userId}:a{agentId}:s{sessionId}"
  → StateStore 真实调用链与寻址参数待验（不能由slotId证明隔离；段内含 ':' 或 '..' 拒绝）
```

## 二、当前源码事实及证据边界（本轮修订）

`ProjectAgentConfiguration` 已接 Redis AgentStateStore、ProjectAgentRunOwnership 和 max-concurrent-runs 配置；因此不能再写“并发完全无界”或“当前仍是内存存储”。仍需区分本机上限、用户/项目上限与跨副本原子资源治理。业务权限事实须按 Person 会话逐端点验收。

`KernelScopeKey` 当前允许空用户降级，且复合 userId 与 sessionId 分开；SDK Redis 寻址是否包含全部维度取决于具体调用参数，不能由slotId四段推出已隔离。由P2/P5沿真实store调用回读验证。

`ProjectAgentRunService.cancel` 走 requireOwnRun；当前不得给超管或负责人授予取消他人运行。定档仍本人发起并满足既有权限，审核是独立权限。owner批准技能身份不能自动等同超管。运行态16039本轮无监听，本文不再声称已加载或当前验收。

## 三、目标设计（DRAFT_ONLY）

1. 个人/项目/实例并发：原子占位、终态释放、超时和崩溃回收、跨副本一致；数值由实测和业务决策确定，旧建议2/5不是既定限额，不设月度token数字。P2实施。
2. 团队共享：先利用现有已授权项目产物和动作汇总；拟新增聚合运行读面需冻结可见角色、脱敏字段、撤权与分页合同。没有批准前不新增接口/角色权，不共享私人提问或内部推理。
3. 同一run内专业协作：业务团队不是Service Team或SDK子智能体。若以后启用受限子任务，继承父run权限、模型/技能/工具快照和审计；不代审核、定档、Gate批准，不另建发送口。
4. 记忆：用户偏好按Person隔离；项目事实按业务权限回读；团队经验带来源、版本、适用范围和批准身份，经既有技能发布链晋升。SDK MEMORY.md不是业务事实源。
5. 存储：继续以当前Redis路线为起点，StateStore/BaseStore/运行锁/turn gate/共享文件分别核验，不另立MySQL目标。需要替换后端时P2负责单次迁移、兼容/回滚和真实双副本验证。
6. 前端：沿既有三栏/六阶段/历史/运行预览，负责人查看权不自动获得操作权；断线保持后台运行；1007与1006遵循现有契约。

## 四、实施与验收

本文属于统一六计划P5的专项设计。立即做只读设计；实现须P1基线通过、总画布执行窗口和看板认领、P2/P4后端合同冻结，不承诺2026-10-06固定开工或半天完成。实施步骤、allowedPaths、共享文件交接、失败/回滚及完成标准统一见六计划总览P5，避免另排一套时间线。

验收矩阵：本人/他人、同项目/跨项目、成员/已退出成员、负责人查看/成员操作、未批准/已批准项目、普通用户/超管、刷新/双标签、撤权/迟到工具结果。每个组合核HTTP、响应字段、真实库零越权效果和浏览器。新共享视图失败时关闭该读面，保留既有本人路径，禁止放宽权限补绿。

marker: agentscope-multiuser-team-design-20261002-v1.1.0
