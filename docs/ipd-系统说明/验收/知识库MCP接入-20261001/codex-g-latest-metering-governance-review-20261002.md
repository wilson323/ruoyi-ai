部分闭环。主 MeteredModel、UsageSink、MeteredModelTest 与 G 冻结候选逐字 SHA 一致，稳定 10130 依赖隔离重跑 10/0/0。最新 HITL 主测试实际为 6 项，原日志与独立临时编译重跑皆 6/0/0；不读取主 target 当本次证据。

收据按 AgentState 实例、Agent 实例、user/session 和真实 ALLOWED 工具调用绑定，参数与 raw content 匹配，AtomicBoolean CAS 单次消费。不同会话盗用、并发重放以及原调用完成后的重放有拒绝覆盖。新 wrapper/冷 JVM 不持内存绑定，保守拒绝；这不等于已支持持久恢复。

新增实质反例：拥有运行 owning-run 的守卫拒绝 foreign-run，但仅凭 sub-UUID 字符串接受同用户未注册子会话；实际 onActing 注册后无害 effect 执行一次。探针 mock 可信 Agent/AgentState，无 HTTP、模型、外部工具或库写；证据只证明缺少权威父子归属检查，不能直接扩大为公开 API 已越权。最小修复应核实际父子注册关系，不能用命名正则当权威。

主 EventBridge 先 agui.accept 全部事件，再跳过非空 source 的父业务正文，符合已实测 SDK root source null/child source 非空语义。所有模型计量由共享 decorator 独立完成，桥仅保留生命周期。当前项目控制器尚无原运行工具确认恢复 API，WAITING_APPROVAL 仍是内核前意图确认，普通资源 cleanup 仍删除临时 checkpoint。正式 HITL/断线/冷恢复未闭环；未修改主源、索引、target、包或运行进程。
