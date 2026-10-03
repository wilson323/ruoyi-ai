# G 独立 E 节点未知效果审查

裁决：VERIFIED_IMPLEMENTATION_ONLY。C 冻结六路径当前 SHA-256 全部一致，独立临时快照 16 项测试、0 失败/错误/跳过；未写主 target/index/生产源码，未发送真实邮件或 HTTP。

实际分类由 WfComponentNameEnum.hasSideEffect 统一给 GraphBuilder 与 AbstractWfNode 使用，仍仅 HttpRequest、MailSend、Dalle3、Tongyiwanx 四类。副作用抛异常或 error=true 一次即停止，读操作保留三次规则。真实 Mail send 异常原先被吞为正常返回，现在返回 error=true 和安全未知文本；异常 cause 只留工程日志，Engine runNode 对上抛固定业务错误。

独立新增临时检查点反例逐一经过真实 GraphBuilder、Plan、JdbcCheckpointSaver（仅 mapper 替换内存 IO）：执行前 inFlight 已保存；未知失败后 completed 为空、afterNode 未调用；再次 resume 在调用 runner 前拒绝，累计调用仍为一次。既有 Mail 正反及 Abstract、Checkpoint 回归同时运行。探针是调度/仓储合同验证，不是真实数据库或外部服务验收。

失败通知本身抛错仍被 Abstract 捕获并一次终止；outputConsumer 持久 FAIL/通知抛错不会进入成功分支。Engine 只有 Plan 成功返回才写运行 SUCCESS，业务失败先写 FAIL，模板/消息/SSE 失败 best effort。节点持久写失败可能留下 DOING，运行状态写本身失败仍不能保证 FAIL 已落库；这些 IO 边界不被此补丁伪装成可靠原子提交。

send 已成功但成功通知/输出/检查点随后失败时，保守保留未知效果及 inFlight；此前成功通知可能已持久存在，不能据消息单独认定整条工作流成功。没有新增外部效果幂等键、事务 outbox、数据库 fence 或补偿确认接口，不能称 external atomic fence 或完整恢复闭环。

原始计数、六路径哈希、临时快照及日志见同目录 JSON。主线全量、实际加载和真实外部效果恢复另需验收。
