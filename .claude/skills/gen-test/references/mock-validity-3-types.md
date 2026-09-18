# Mock 合法性三形态

## 是什么
项目防假绿三条硬规约（2026-09-08 立），所有聚合器 / 待办类 Service 测试必须遵守。规约本体与存量违法清单（A/B 类）见 `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`。

## 为什么踩坑
WB-17-1 Gate 仲裁真活验证发现 mock 造了真库不可能的数据组合，单测全绿业务死路。三种典型形态：

1. **断言改现状型**：把测试断言改成"现状"（期望异常类型改成新类型），用例转绿但契约缺口仍在。
2. **Mock 不真数据型**：mock 造了真库写入路径不可能产生的数据组合（如 PENDING_SECOND 态配 confirmerId、`decision IS NULL` 但无任何生产者落 NULL 行）。
3. **跑挂用例型**：测试方法根本没被 Surefire 跑过（漏 `@Tag("dev")`）。

## 怎么识别
- 跑 `bash .claude/skills/gen-test/scripts/mock-drift-check.sh`：扫项目内 `*.test.java`，对照真库 NOT NULL 列与已知死路表。
- 写测试前必须先读对应 Service 的 insert/update 链（`grep -rn "insert\|update" src/main/java/.../<YourService>.java`），确认"什么状态下哪些字段是什么值"。

## 怎么修（写测试时三条硬规则）

1. **写测试前先读写入路径**：mock 的投递锚字段（confirmerId / leaderId / reviewerId / decision / arbitratorId 等）取值，必须能由真实写入路径产生。
2. **状态组合必须满足状态机**：外层状态与子行状态的组合必须真实可达（仲裁行只挂 REJECTED gate；「确认人 ID 回填」与离开待办态是同一事务的两面）。复制粘贴相邻聚合器测试时，强制 diff 检查状态过滤条件。
3. **NOT NULL 列必须显式赋值**：mock 依赖表的 NOT NULL 列在 builder 中必须给值——与被测路径无关也要补，防「真库不可能行」潜伏。

**配套**：「未办态」用例优先走真实写入路径构造数据（如 `service.sign()` 真实触发而非 builder 直造）；纯 mock 用例须在 `@DisplayName` 标注「未覆盖 DDL 合法性」。

## 验证
- 跑 `bash scripts/mock-drift-check.sh` 应能识别已知违规
- 故意改一个 mock builder 去掉 NOT NULL 列赋值，扫描应报错（自证能红）

## 来源
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
- `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`
- 项目记忆：`实体加字段但 DDL 漏迁移致真库查询 Unknown column`（P2-7.4 archived_at、notification_events 第二撞）