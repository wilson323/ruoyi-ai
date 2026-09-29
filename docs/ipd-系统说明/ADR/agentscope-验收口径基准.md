# AgentScope 波次验收口径基准（M4 落地，2026-09-28）

- 状态：`living`（口径基准；数字按定义来源冻结，不随测试增长自动改写）
- 日期：2026-09-28 · 关联：[ADR-0075](./ADR-0075-agentscope内核替换三选一-20260928.md)（M4 增补）、W1/W2 波次报告
- 用途：波次验收（W1–W8、G 系）引用本文件口径编号与用例数，**报告不写裸数字**；口径修订须经本文件并注明来源。

## 0. 引用与修订规则

1. 收口记录写法：「口径 K-0x 全绿（见《agentscope-验收口径基准》）」，禁止裸数字。
2. 口径数 = 定义来源（W1/W2 波次报告 surefire 实测）收口时刻的用例数，**冻结不自动增长**；测试类后续新增用例不改基准数。
3. 验收判据：口径内套件对应测试类全绿，且现树同类 @Test 数 ≥ 口径数（漂移只增不减；减少须修订本文件并说明原因）。
4. 新增口径按 K-xx 续编，注明定义来源与日期。
5. 回证路径：`<模块>/target/surefire-reports/*.txt` 的 `Tests run` 行（W2 收口实测留存于 poc-agentscope-kernel 工作树）。

## K-01 SSE/WS 契约零回退 62/62

- 定义来源：W1 基线 + W2 波次收口复测（surefire 实测，2026-09-28）。语义：既有 SSE/WS 对外帧契约在内核委托改造后零回退。
- 验收动作：下表四套件全绿 + `bash scripts/check-sse-contract.sh` OK。

| 子套件 | 用例数 | 所在测试类（模块；括号内为用例数） | 现树核对 |
|---|---|---|---|
| common-sse | 13 | `SseEmitterHelperTest`(12) + `SseControllerTest`(1)（ruoyi-common/ruoyi-common-sse） | ✅ 12+1=13 |
| aiflow | 11 | `WorkflowSseLifecycleContractTest`(11)（ruoyi-modules/ruoyi-aiflow） | ✅ 11 |
| ipd | 27 | `NotificationDispatcherTest`(7) + `WebSocketChannelHandlerTest`(6) + `CopilotKitRuntimeControllerTest`(5) + `WebSocketOriginGuardTest`(4) + `IpdHandshakeInterceptorTest`(4) + `IpdSseControllerTest`(1)（ruoyi-modules/ruoyi-ipd） | ✅ 7+6+5+4+4+1=27 |
| chat | 11 | `MpChatWebSocketHandlerKernelDelegateTest`(6) + `KernelEventFramesTest`(5)（ruoyi-modules/ruoyi-chat） | ✅ 6+5=11 |
| **合计** | **62** | | ✅ 13+11+27+11=62 |

## K-02 内核 PoC 链路 11+1

- 定义来源：W1 PoC 验收（AgentScope PoC 验收报告）+ W2 收口复测。语义：内核全链路集成实证（`@Tag("dev")` 真库）。

| 子套件 | 用例数 | 所在测试类（ruoyi-modules/ruoyi-chat / chat/poc/kernel） | 现树核对 |
|---|---|---|---|
| KernelPocIT | 7 | `AgentScopeKernelPocIT` | ✅ 7 |
| RagPocIT | 4 | `AgentScopeRagPocIT`（stub EmbeddingModel 范围除外） | ✅ 4 |
| 流式 | 1 | `AgentScopeStreamingPocIT` | ✅ 1 |
| **合计** | **11+1** | 11 主链 + 1 流式 | |

## K-03 B0 安全 18（9+4+2+3）

- 定义来源：B0 安全审计（commit `238dd9be`，卡 5c1cc6f8）+ W1/W2 波次收口复测。语义：KnowledgeAccessGate 授权/缓存身份段/会话派生身份回归。

| 子套件 | 用例数 | 所在测试类（ruoyi-modules/ruoyi-chat） | 现树核对 |
|---|---|---|---|
| Gate 授权 | 9 | `UserIdShareKnowledgeAccessGateTest` | ✅ 9 |
| 缓存身份段 | 4 | `KnowledgeRetrievalCacheIdentityTest` | ✅ 4 |
| Facade 调用点 | 2 | `ChatServiceFacadeKnowledgeAccessTest` | ✅ 2 |
| Wrapper | 3 | `KnowledgeInfoServiceImplWrapperTest` | ✅ 3 |
| **合计** | **18** | | ✅ 9+4+2+3=18 |

## K-04 委托面 10（SSE 5 + WS 5）

- 定义来源：W1 委托面收口（SSE/WS 内核委托接线 5+5，surefire 实测）。语义：内核委托的帧映射/错误帧/取消最小面。

| 子套件 | 用例数 | 所在测试类（ruoyi-modules/ruoyi-chat） | 现树核对 |
|---|---|---|---|
| SSE 委托 | 5 | `ChatServiceFacadeKernelDelegateTest` | ⚠ 现树 8 @Test / 最近 surefire 8（W2 起增补 modelVo 路由、知识拒绝、13 参兼容等），基准 5 不变 |
| WS 委托 | 5 | `MpChatWebSocketHandlerKernelDelegateTest` | ⚠ 现树 9 @Test / 最近 surefire 6（落库顺序等后增），基准 5 不变 |
| **合计** | **10** | | 按 §0-3 判据验收 |

## K-05 并发 4/4 → 6/6

- 定义来源：W1 同键并发负例 4/4 → W2 扩充 6/6（surefire 实测 6，2026-09-28）。语义：同键串行/并发负例（单进程 `LocalSessionTurnGate` 范围；跨副本未验，见 ADR-0075 §9）。

| 阶段 | 用例数 | 所在测试类 | 现树核对 |
|---|---|---|---|
| W1 | 4/4 | `AgentScopeKernelConcurrencyPocIT`（ruoyi-chat / chat/kernel） | 历史口径 |
| W2（现行） | 6/6 | 同上 | ✅ surefire 6 |

## K-06 W2 模型路由定向 14

- 定义来源：W2 波次收口（路由测试先红后绿、定向 14 项 0 失败；surefire 8+6）。

| 子套件 | 用例数 | 所在测试类（ruoyi-modules/ruoyi-chat / chat/kernel） | 现树核对 |
|---|---|---|---|
| 选型 | 8 | `KernelModelSelectorTest` | ✅ surefire 8（现树 9 @Test，后增） |
| 路由 | 6 | `AgentScopeChatKernelModelRoutingTest` | ✅ surefire 6（现树 7 @Test，后增） |
| **合计** | **14** | | |

## K-07 W2 全模块聚合 165/165

- 定义来源：W2 波次收口全模块测试聚合（165/165 绿，W2 波次报告）。聚合口径无固定类清单（以当时受改模块全量测试为准）；分套件口径以 K-01~K-06 为准，聚合数不随类增长改写。

## 附：口径 ↔ ADR 引用点

| 口径 | ADR-0075 引用位置 |
|---|---|
| K-01 | W2 收口裁定、W3 入条件裁定（62/62 零回退） |
| K-02 | W2 收口裁定（11+1） |
| K-03 | W1 出条件 B0 哨兵回归、W2 收口裁定 |
| K-04 | W2 收口裁定（委托面 10） |
| K-05 | W1/W2 出条件并发负例 |
| K-06 | W2 收口裁定（路由定向 14） |
| K-07 | W2 收口裁定（全模块 165/165） |

后续波次（W3–W8、G 系）收口一律引用本文件口径编号；本文件与 ADR-0075 同目录，ADR 修订增补时同步维护。
