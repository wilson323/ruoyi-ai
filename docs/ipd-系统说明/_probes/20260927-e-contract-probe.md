# 契约测试与前后端错位盘点（2026-09-27，蜂群 e 探针）

> 任务：`只读调研`，不修改任何代码、不跑 mvn/vitest、不 commit/push。
> 输出：本报告 → `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/_probes/20260927-e-contract-probe.md`
> 关联探针：`20260927-a-backend-probe.md`（后端盘点）/ `20260927-b-frontend-probe.md`（前端盘点）/ `20260927-d-wiki-probe.md`（wiki 盘点）。
> 应用记忆：`d47e68be-...`（Gate ID 语义对齐）、`af6390ff-...`（三方对齐矩阵法）、`59f42f00-...`（IPD 前后端连通性）。

---

## 摘要

| 维度 | 关键结论 |
|------|----------|
| 后端契约测试 | 6 个文件，528+251+372+232+141+42 = **1566 行**；其中 `AiCardBlindSignContractTest` **未 git add**（兄弟会话文件） |
| 前端 vitest | 13+ 个文件，~4500+ 行；其中 `blind-sign-render.test.ts` **未 git add**（兄弟会话文件） |
| 字段一致性 | Long→String ID 转换全部走 `String.valueOf()` 兜底，**对齐**；`scene` 字段前端 7 场景 vs 后端 11 场景（**漂移**） |
| 假绿陷阱 | 现状：兄弟会话文件**已修复** `decision IS NULL` 形态（旧 mock 违规见 `mock合法性与已知死路登记-20260908.md`） |
| SSE 双通道 | 后端 5 端点；前端 4 消费端；**两套鉴权路径**（URL token vs Bearer header）**已分裂** |
| 基线与门禁 | `api-contract-orphan-baseline.json` 5 孤儿；`check-pre-commit.sh` 5 道门禁（0=untracked / 1=drift / 2=contract / 3=ratchet / 4=shell-var） |

---

## §1 后端契约测试覆盖矩阵

> 路径：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/`
> 行号：现查 `Read <file>` 第一行输出 total=N
> mock 模式：现查 `Grep "@Mock|@MockBean|Mockito\.mock|@ExtendWith"` 结果

### 1.1 AiCardBlindSignContractTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/AiCardBlindSignContractTest.java` |
| 行数 | **528 行**（`Read L1` 显示 total=528） |
| git 状态 | **`??` 未跟踪**（`git status` 实证：兄弟会话文件，未 `git add`） |
| commit | **N/A**（未入仓，无法用 `git log -1` 取） |
| 测试主题 | R232-P2-05 盲签隔离契约：卡片层 rowView 同源遮蔽 + arbitrate/finalRuling 无 AI 倾向 |
| @DisplayName | `L97`、`L276`、`L302`、`L317`、`L334`（5 个 `@DisplayName`） |
| @Test | `L275`、`L301`（2 个 `@Test` 抽样可见） |
| 关键断言 | `L284` `assertThat(resp.card()).as("出卡成功...")`；`L292` `assertThat(otherRow.get("decision")).as("对方判定字段为空...").isNull()`；`L293` `assertThat(otherRow.get("opinion")).as("对方意见字段为空...").isNull()`；`L294` `assertThat(otherRow.get("reviewerType")).as("遮蔽=值空非删行...").isEqualTo("RD_PM")` |
| 关键 mock 数据 | `L203-208` `stubInFlightOnlyOtherSideSigned()`：`review(91L, GATE_ID, "RD_PM", "APPROVE", OTHER_OPINION)` + `review(92L, GATE_ID, "MARKET_PM", null, null)`；`L211-216` `stubInFlightOnlyMySideSigned()`：镜像形态 |
| mock 模式 | `L51-57` `assertThat/assertThatThrownBy` 静态导入；`L57` `Mockito.mock` 静态导入（**未走 @MockBean**，纯 `Mockito.mock` + 手工注入） |
| 兄弟会话在途 | **是**（见 §6） |

### 1.2 GateReviewObserverAcceptanceTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/GateReviewObserverAcceptanceTest.java` |
| 行数 | **251 行**（`Read L1` 显示 total=251） |
| commit | `f987e0288b15b777c116f697b39744a56140f5c7` @ 2026-09-21 |
| 测试主题 | MEDIUM-1.3 列席人员 5-case acceptance |
| @ExtendWith | `L52` `@ExtendWith(MockitoExtension.class)` |
| @Mock | `L55`、`L57`、`L59`、`L61`、`L63`、`L65`、`L67`、`L69`、`L71`（9 个 `@Mock` 字段） |
| @Test | `L131`（5 个 `@DisplayName` 在 `L132/155/171/194/208`） |
| 关键断言 | `L143` `assertThat(count).isEqualTo(3)`；`L144` `verify(observerMapper, times(3)).insert(any(GateReviewObserver.class))`；`L145` `verify(notificationService, times(3))`；`L165` `verify(observerMapper, never()).insert(any(GateReviewObserver.class))`（幂等）；`L203` `verify(observerMapper, never()).updateById(...)`（403 越权防护） |
| 关键 mock 数据 | `L84` `@BeforeEach`（隐含）；`L150` `verify(auditLogService, atLeastOnce()).append(auditCap.capture())` |
| mock 模式 | **纯 Mockito**（`@Mock` + `MockitoExtension`），无 Spring 上下文 |

### 1.3 P2_6_2_DualSignStageGuardTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/P2_6_2_DualSignStageGuardTest.java` |
| 行数 | **372 行**（`Read L1` 显示 total=372） |
| commit | `f987e0288b15b777c116f697b39744a56140f5c7` @ 2026-09-21 |
| 测试主题 | 双签回写 + 阶段门禁 7 维验证（P2-6-2 治理轮） |
| @ExtendWith | `L53` `@ExtendWith(MockitoExtension.class)` |
| @Mock | `L74-83`（10 个 `@Mock`：RequirementChangeMapper / RequirementMapper / IAuditLogService / ProjectMapper / StageActionMapper / KpiRecordMapper / GateEngine / ProjectBootstrapService / IProjectCertService / RequirementChangeService） |
| 关键 mock 模式 | `L100` `reqChangeService.setStateMachineGuard(org.mockito.Mockito.mock(StateMachineGuard.class))`（**嵌套 mock 替换**守卫） |
| @Test | `L132`（多测试入口，需全文确认） |
| 关键断言 | 需现查 `grep -n "assertThat\|@DisplayName" <file>` 完整列表（本报告 `L132` 抽样） |

### 1.4 StageActionServiceTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceTest.java` |
| 行数 | **232 行**（`Read L1` 显示 total=232） |
| commit | `2528d8707d91199e97b31be727b22fa55e8386c6` @ 2026-09-24 |
| 测试主题 | P1-4 深/轻管分离校验（深度/轻量管理边界） |
| 关联 1.5/1.6 | 同 commit（`2528d870`） |

### 1.5 StageActionServiceInstantiateBatchTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceInstantiateBatchTest.java` |
| 行数 | **141 行**（`Read L1` 显示 total=141） |
| commit | `2528d8707d91199e97b31be727b22fa55e8386c6` @ 2026-09-24 |
| 测试主题 | PERF-03 批量插入验证 |

### 1.6 StageActionServiceTransactionAnnotationTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceTransactionAnnotationTest.java` |
| 行数 | **42 行**（`Read L1` 显示 total=42） |
| commit | `2528d8707d91199e97b31be727b22fa55e8386c6` @ 2026-09-24 |
| 测试主题 | CODE-01 Part A：反射验证 `@Transactional(rollbackFor=...)` 注解保留（防配置漂移） |

### 1.7 §1 总结

- **覆盖率**：6 文件 / 1566 行覆盖 5 大领域（盲签契约 / 列席 acceptance / 双签阶段门禁 / 深轻管 / 批量性能 / 事务注解）
- **mock 风格**：全部走纯 Mockito（`@Mock` + `MockitoExtension`），**无 `@MockBean` Spring 上下文测试**——性能高，但无法覆盖 `@Transactional` / `@Cacheable` 等 AOP 行为（除 1.6 反射特例外）
- **未跟踪文件**：1 个（`AiCardBlindSignContractTest`，R232-P2-05 在途治理轮产物）
---

## §2 前端 vitest 覆盖矩阵

> 路径：`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/`
> 工具：`vitest`，`describe/it/expect` 抽样

### 2.1 ai-cards/blind-sign-render.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/blind-sign-render.test.ts` |
| 行数 | **127 行**（`Read L1` 显示 total=127） |
| git 状态 | **`??` 未跟踪**（兄弟会话文件，未 `git add`） |
| commit | **N/A**（未入仓） |
| describe | `L33` `describe("盲签隔离契约·渲染层（P2-05：arbitrate/finalRuling 卡无 AI 倾向渲染）")` |
| it | `L34` `gate.conclusion 卡：污染注入 AI 倾向字段零渲染`；`L78` `rowView 遮蔽形态（decision/opinion=null）：判定/意见单元格渲染安全占位「—」`；`L94` `gate.precheck 卡：污染注入 items 子字段零渲染` |
| 关键断言 | `L66-67` `expect(text).toContain("MARKET_PM")` + `expect(text).toContain("APPROVE")`；`L71` `expect(text, AI 倾向值不得渲染).not.toContain(leak)`；`L89` `expect(cells[0]!.text()).toBe("RD_PM")`；`L90` `expect(cells[1]!.text()).toBe("—")`；`L91` `expect(cells[2]!.text()).toBe("—")` |
| mock 模式 | `L18` 纯 `vitest`（`describe, expect, it`），**未走 `vi.mock`**，直接构造伪造卡片数据 + `render()` |
| 兄弟会话在途 | **是**（见 §6） |

### 2.2 ai-cards/card-components.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/card-components.test.ts` |
| 行数 | **249 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |

### 2.3 ai-cards/card-registry.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/card-registry.test.ts` |
| 行数 | **169 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |

### 2.4 ai-cards/catalog-reconcile.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/catalog-reconcile.test.ts` |
| 行数 | **486 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |
| 主题 | Catalog 对账哨兵（`system_configs.config_value="ai.suggest.cardCatalog"` ↔ 前端 types） |

### 2.5 ai-suggest.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-suggest.test.ts` |
| 行数 | **734 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |

### 2.6 ai-assistant.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-assistant.test.ts` |
| 行数 | **734 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |

### 2.7 ipd-state-machines.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ipd-state-machines.test.ts` |
| 行数 | **144 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |
| 主题 | 状态机迁移图、查表、未知值兜底 |

### 2.8 ipd-enums.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ipd-enums.test.ts` |
| 行数 | **267 行** |
| commit | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` @ 2026-09-27 |
| 主题 | 角色 / 阶段 / 状态 / 优先级 / 严重度枚举镜像对账 |

### 2.9 ipd-permission-codes.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ipd-permission-codes.test.ts` |
| 行数 | **352 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| 主题 | 权限码集中常量镜像对账（与后端 `IpdPermissionCode` 同步） |

### 2.10 ipd-error-text.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ipd-error-text.test.ts` |
| 行数 | **385 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| 主题 | IPD 业务错误码 → 中文文案（来源 R234 单一码表） |

### 2.11 zk-ipd-rules.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/zk-ipd-rules.test.ts` |
| 行数 | **146 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| describe | `L23` `describe("ZK-IPD 业务规则显示文案与 Prompt 强一致")` |
| 关键断言 | `L25-26` `expect(ZK_RULE_ARCHIVED_READONLY.rule).toContain("归档" + "只读")`；`L31-33` 奖金池公式三要素；`L38-40` 奖金分配比例区间 |

### 2.12 admin/gate-detail/index.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-detail/index.test.ts` |
| 行数 | **452 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| describe | `L80` `describe("R177-A6 Gate 评审详情页 button-policy 接入契约")`；`L309` `describe("R177-A6 Gate 评审详情页 · ORPHAN-A1 列席与遗留接线")` |
| it | `L81` PENDING 8 按钮；`L122` REJECTED 仅 2 按钮；`L163` APPROVED 仅刷新；`L204` ABSTAINED_TIMEOUT；`L245` 无 projectId 空态；`L348` 组长调 GET /observers；`L362` 普通 PM 不调；`L372` 邀约列席 POPCONFIRM |
| 关键断言 | `L348-362` 路由前置（避免必然失败请求）——**前端权限防御**，与后端 403 对偶 |
| mock 模式 | `L10` `import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"`；`L70` `beforeEach(() => { ... })` |

### 2.13 admin/gate-detail/button-policy.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-detail/button-policy.test.ts` |
| 行数 | **121 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| 主题 | button-policy 组件单测（index.test.ts 的子模块） |

### 2.14 admin/gate-elements/index.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-elements/index.test.ts` |
| 行数 | **144 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |
| 主题 | 页47 Gate 评审要素：加载/成功/空态/拒绝/断网与新增/停用契约 |

### 2.15 admin/gate-elements/button-policy.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-elements/button-policy.test.ts` |
| 行数 | **141 行** |
| commit | `76293644702d1ec147b5c7a7385faf6aaee5359b` @ 2026-09-27 |

### 2.16 §2 总结

- **覆盖率**：15 文件 / ~4400+ 行；按主题分 3 大块：
  - **AI 卡片栈**（§2.1-2.6）：盲签渲染 / 卡片组件 / 注册表 / Catalog 对账 / ai-suggest / ai-assistant
  - **IPD 通用**（§2.7-2.11）：状态机 / 枚举 / 权限码 / 错误文案 / 业务规则
  - **评审详情页**（§2.12-2.15）：gate-detail / gate-elements 两组页面 + button-policy
- **mock 风格**：以纯 vitest 构造数据为主，少量 `vi.mock`（gate-detail/index.test.ts 用 `vi` 拦截 API）
- **未跟踪文件**：1 个（`blind-sign-render.test.ts`，R232-P2-05 在途治理轮产物）
---

## §3 前后端字段类型一致性表

> 取样原则：选 3-5 个关键端点 × 主要字段 × 后端 DTO 类型 × 前端 `*.ts` 类型
> Long→String 转换：依赖后端 `BigNumberSerializer` 序列化策略

### 3.1 Gate 评审相关

| 端点 | 字段 | 后端类型（来源） | 前端类型（来源） | 一致 |
|------|------|------------------|------------------|------|
| `POST /api/v1/gates/{gateId}/sign` | `gateId` (PathVariable) | `Long`（`GateReviewController.java:73` `@PathVariable Long gateId`） | `string`（`gate-review.ts` URL 模板 `/gates/{gateId}/sign`，`encodeURIComponent`） | ✅ |
| 同上 | `id` (响应 SignView) | `String`（`GateReviewController.java:61` `record SignView(String id, String gateId, ...)`，L64 `String.valueOf(r.getId())`） | `string`（`gate-review.ts` 类型声明） | ✅ |
| 同上 | `gateId` (响应 SignView) | `String`（`GateReviewController.java:61` + L64 `String.valueOf(r.getGateId())`） | `string` | ✅ |
| `GET /api/v1/gates/{gateId}/review` | 返回 `Map<String,Object>` | `service.view(gateId, actor)` 返回 `Map`（`GateReviewController.java:82-84`） | 前端按 `map<string, any>` 消费 | ✅（任意键，未强约束） |

### 3.2 StageAction 相关

| 端点 | 字段 | 后端类型 | 前端类型 | 一致 |
|------|------|----------|----------|------|
| `StageActionFieldsReq` | `actualDoneAt` | `Date`（DTO） | `string` (ISO) | ✅ |
| 同上 | `farValue` / `frrValue` | `BigDecimal` | `number` | ✅（前端 JSON.parse 后即 number） |
| 同上 | `certNo` | `String` | `string` | ✅ |
| 同上 | `certPassedAt` | `Date` | `string` | ✅ |
| 同上 | `algoType` | `String` | `string` | ✅ |

### 3.3 AI 卡片相关

| 端点 | 字段 | 后端类型 | 前端类型 | 一致 |
|------|------|----------|----------|------|
| `POST /api/v1/ai/suggest` | `scene` | `String @NotBlank`（`AiSuggestionService.java:86-93` SCENES 11 项） | `AiSuggestScene`（`ai-suggest.ts:17-24`，**7 项**） | ❌ **DRIFT** |
| 同上 | `projectId` | `Long` | `number`（`ai-cards/types.ts:32/86/94/100/112` 全部 `number`） | ✅ |
| 同上 | `entityId` | `Long` | `number` | ✅ |
| `AiSuggestResp.Card` | `type` | `String`（`AiSuggestResp.java:50`） | `string`（`ai-cards/types.ts:142`） | ✅ |
| 同上 | `version` | `int` | `number` | ✅ |
| 同上 | `data` | `Map<String,Object>` | `TData` 联合（4 卡 Discriminated Union） | ✅ |
| 同上 | `sourceRefs` | `Map<String,Object>` | `Record<string, number \| string \| string[]>` | ✅ |

### 3.4 Gate Precheck 相关

| 端点 | 字段 | 后端类型 | 前端类型 | 一致 |
|------|------|----------|----------|------|
| `POST /api/v1/gates/{gateId}/precheck` | `gateId` (PathVariable) | `Long` | `string` | ✅ |
| 同上 | 响应 `GatePrecheckView.gateId` | `String`（`GatePrecheckService.java` `String.valueOf(elementId)`） | `string`（`gate-precheck.ts:81`） | ✅ |
| 同上 | `GatePrecheckItem.elementId` | `String` | `string`（`gate-precheck.ts:26`） | ✅ |
| 同上 | `materials.projectId` / `gateId` | `String` | `string` | ✅ |
| 同上 | `materials.items[].actionId` | `String`（`gate-precheck.ts:47` 注释「Long 序列化字符串」） | `string` | ✅ |

### 3.5 API V1 包络（code=0/message/data/timestamp/traceId）

| 字段 | 后端（`ApiV1Response.java`） | 前端 | 一致 |
|------|-------------------------------|------|------|
| `code` | `int`（L26）— **0 = success**（区别于旧基线 `R<T>` 的 200） | 前端 `http.ts` 拦截器按 `code===0` 判 success | ✅ |
| `message` | `String`（L27） | `string` | ✅ |
| `data` | `T` 泛型（L28） | 端点对应类型 | ✅ |
| `timestamp` | `String` ISO-8601（L38） | `string` | ✅ |
| `traceId` | `String`（L39） | `string` | ✅ |

### 3.6 Scene 白名单漂移（Critical）

| 维度 | 后端 11 项（`AiSuggestionService.java:86-93`） | 前端 7 项（`ai-suggest.ts:17-24`） |
|------|----------------------------------------------|----------------------------------|
| 已对齐 | `gate.precheck-checklist`, `gate.conclusion-draft`, `project.create.suggest`, `demand.create.from-requirement`, `project.summary.refresh` | 5 项 ✅ |
| **后端独有** | `demand.dedupe`, `change.impact-analyze`, `handover.checklist-generate`, `report.nl-query` | 4 项 ❌ 前端 `AiSuggestScene` 类型缺失 |
| **前端独有** | — | 0 项 |

**结论**：前端 `AiSuggestScene` 联合类型比后端少 4 项，**新场景上线前端必缺 TS 报错**——这是 §4 假绿陷阱 §5 SSE 之外的第三类系统性漂移（**字段漂移**）。

### 3.7 Error Code 镜像（Critical）

| ErrorCode | 数值 | HTTP 映射（`ApiV1ErrorCode.java:93-118`） | 前端文案（`ipd-error-text.test.ts` 单一码表） |
|-----------|------|--------------------------------------------|-------------------------------------------|
| `PARAM_INVALID` | 10001 | 400 | R234 单一码表镜像 |
| `UNAUTHORIZED` | 20001 | 401 | 同上 |
| `FORBIDDEN` | 30001 | 403 | 同上 |
| `GATE_NOT_PASSED` | 40001 | 400 | 同上 |
| `NOT_FOUND` | 50001 | 404 | 同上 |
| `STATE_CONFLICT` | 50002 | 409 | 同上 |
| `INTERNAL_ERROR` | 90001 | 500 | 同上 |

**结论**：error code 数值 / HTTP 映射 / 前端文案三者**完全对齐**（`ipd-error-text.test.ts:385` 行单一码表守护）。
---

## §4 假绿陷阱清单

> 真源：`/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`（70 行，commit `3ab3c094740d81d6e2f3357efe8cc6a823353bae` @ 2026-09-08）
> 三条硬规则（mock 合法性规约）：
> 1. 写测试前先读写入路径：投递锚字段必须能由真实写入路径产生
> 2. 状态组合必须满足状态机：外层/子行状态组合必须真实可达
> 3. NOT NULL 列必须显式赋值：builder 中必须给值

### 4.1 旧存量违法（A 类，Critical）

> 见 `mock合法性与已知死路登记-20260908.md:27-34`（真库探针：均 0 命中）

| # | 测试 | mock 违法组合 | 违反约束 | 真活后果 |
|---|------|---------------|----------|----------|
| A1 | `StrategicChangeAggregatorTest` L69-72/119-121/135-137 | `status="PENDING_SECOND" + confirmerId(2L)` | `LaunchDateChangeService.propose()` L76-86 落 PENDING_SECOND 不写 confirmerId；DDL nullable | LD- 卡**真活永不投递** |
| A2 | 同上 L96-98/122-124/138-140 | `status="PENDING_LEADER" + leaderId(...)` | `CoefficientChangeService.propose()` L71-79 不写 leaderId | CC- 卡**真活永不投递** |
| A3 | `ContributionConfirmAggregatorTest` L69-70/96-97/108-109 | `status="SUBMITTED" + leaderId(...)` | `ContributionService` L275 双 PM 齐评仅 setStatus，不写 leaderId | CT- 卡**真活永不投递** |
| A4 | `KeyGateAggregatorTest` L83-85/140-143/170-171 | `gate PENDING + review(decision=null)` | gate_reviews 全部 3 条 Java 写入路径不产生 NULL decision | GR- 签署卡**真活永不投递**（WB-17-1 镜像） |

### 4.2 NOT NULL 列 mock 为 null（B 类，Warning）

| 测试 | 缺 NOT NULL 列（`mock合法性与...md:42-46`） |
|------|----------------------------------------------|
| `DeletionReviewAggregatorTest` L54-59/82-84 | reason / requester_id |
| `StageSignAggregatorTest` L52-58 | stage_id / depth / is_bio_feature |
| `StrategicChangeAggregatorTest` | LD 缺 reason/proposer_id/proposer_role；CC 缺 reason/market_pm_id/rd_pm_id/proposer_id |

### 4.3 兄弟会话文件（A1-A4 修复范式：`AiCardBlindSignContractTest`）

**关键发现**：A4 类（`decision=null`）的修复范式由兄弟会话文件 `AiCardBlindSignContractTest.java` 实证：

| 行号 | 修复模式 |
|------|----------|
| `L203-208` `stubInFlightOnlyOtherSideSigned()` | `review(91L, GATE_ID, "RD_PM", "APPROVE", OTHER_OPINION)` + `review(92L, GATE_ID, "MARKET_PM", null, null)` |
| `L211-216` `stubInFlightOnlyMySideSigned()` | `review(91L, GATE_ID, "MARKET_PM", "APPROVE", MY_OPINION)` + `review(92L, GATE_ID, "RD_PM", null, null)` |
| `L292-294` 断言形态 | `decision IS NULL` **显式断言**为真（不是「空就当成功」假绿），而是 `OTHER_OPINION` 字面量区分「对方判定为空」与「己方判定已签」 |

**与旧 A4 区别**：旧 A4 是「`gate PENDING + review(decision=null)` 聚合器返回 0 条断言成功」假绿；新范式是「`gate IN_FLIGHT + review(OTHER_OPINION)` 显式构造状态机合法 IN_FLIGHT 形态 + 显式断言 rowView 遮蔽行为」——**真库可达 + 断言非平凡**。

### 4.4 §4 结论

- **存量违法 A1-A4**：未修复（蜂群盘点状态），仍然导致 4 张卡真活恒空
- **修复范式**：兄弟会话 `AiCardBlindSignContractTest` 已示范「**真库可达 + 显式断言**」的正确形态，可作为 A4 重写样板
- **未跟踪风险**：兄弟会话文件**未 git add**，进 git 前不会被 `check-pre-commit.sh` 门禁 0（untracked 引用）守护——必须先 `git add` 入仓再跑全部门禁

---

## §5 SSE 双通道扫描

### 5.1 后端 SSE 端点清单

> 实证：`Grep "@GetMapping.*produces.*text/event-stream|@RequestMapping.*text/event-stream" ruoyi-modules/`

| # | 文件 | 行号 | 端点 | 鉴权 | commit |
|---|------|------|------|------|--------|
| 1 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdSseController.java` | `L61` `+ L40 @RequestMapping("/api/v1/resource")` | `GET /api/v1/resource/sse` | URL Query `Authorization=Bearer ...`（`StpLogicJwtForSimple("ipd")`） | `7992c386703fea163a9a527f23ddd6f6ddec2a91` @ 2026-09-11 |
| 2 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AiCopilotController.java` | `L89` `+ L45 @RequestMapping("/api/v1/ai-copilot")` | `GET /api/v1/ai-copilot/chat/stream` | **Bearer Header**（与 #1 URL token 不同） | `27d6655fab460c2f629f825d6c244a81ada23023` @ 2026-09-26 |
| 3 | `ruoyi-modules/ruoyi-aiflow/.../WorkflowController.java` | `L83` | `POST /api/workflow/run` | Bearer Header | 历史 |
| 4 | `ruoyi-modules/ruoyi-chat/.../CodingHarnessController.java` | `L167` | 内部 SSE | Bearer Header | 历史 |
| 5 | `ruoyi-modules/ruoyi-chat/.../CodingController.java` | `L69` | `POST /chat` | Bearer Header | 历史 |

### 5.2 前端 SSE 消费端

| # | 文件 | 行号 | 机制 | 备注 |
|---|------|------|------|------|
| 1 | `apps/web-antd/src/utils/message.ts` | `L31` | `useEventSource(sseAddr)` 组件 | 主消息推送：`${apiURL}/resource/sse?clientid=...&Authorization=Bearer ...` |
| 2 | `apps/web-antd/src/api/core/auth.ts` | `L134` | 注释提及 IPD SSE close 端点缺失 | 业务面断连靠前端主动关闭 |
| 3 | `apps/web-antd/src/api/aiflow/runtime.ts` | `L24, L42` | `commonSseProcess("/api/workflow/run", ...)` | aiflow 模块专用 |
| 4 | `apps/web-antd/src/api/ipd/ai-copilot.ts` | `L11` | **注释警示**：`EventSource` 不支持自定义 Header 故走 `fetch + ReadableStream` | 与 #1 形成 SSE 双通道分歧 |

### 5.3 工作流相关 SSE 映射

| 业务流 | 后端端点 | 前端消费 | 一致 |
|--------|----------|----------|------|
| 主消息推送（Gate / Stage 状态变更） | `/api/v1/resource/sse`（#5.1.1） | `useEventSource` in `message.ts:31` | ✅ |
| AI Copilot 流式对话 | `/api/v1/ai-copilot/chat/stream`（#5.1.2） | `streamCopilot` in `ai-copilot.ts:11`（fetch+ReadableStream） | ✅ |
| Runtime 流（aiflow，非工作流核心） | `/api/workflow/run`（#5.1.3） | `commonSseProcess` in `runtime.ts:24,42` | ✅ |
| **盲签推送 / precheck 推送 / conclusion 推送** | **未发现独立 SSE 端点** | — | ❌ 缺通道 |

### 5.4 §5 结论

1. **两套鉴权路径分裂**：主消息推送走 URL token（`useEventSource` 不支持 Header 限制的副作用），AI Copilot 走 Header（`fetch + ReadableStream` 主动选择）。这种分裂是 EventSource W3C 协议本身的限制（不允许自定义 Header），不是 bug，但需要在所有新 SSE 接入点统一选择。
2. **盲签 / precheck / conclusion 推送缺失**：兄弟会话文件 `AiCardBlindSignContractTest` 暗示前端**通过普通 HTTP 轮询 / 单次响应**获取盲签数据（不是推送）。如果产品要求「对方已签即时揭示」，需新开 SSE 端点或在主 SSE 通道内加 event type。
3. **SSE 契约门禁**：`scripts/check-sse-contract.sh`（94 行，commit `ec841ed8`）作 8 端点最低守护，但**未涵盖 IPD 模块 5 端点**——IPD 模块 SSE 缺独立门禁。

---

## §6 兄弟会话在途测试的影响

> 「兄弟会话」= 与本会话并行、且 `git add`/`git commit` 状态不一致的工作副本
> 来源未知：当前工作区未跟踪，无法用 `git log -1 -- <path>` 取 commit hash

### 6.1 后端：AiCardBlindSignContractTest.java

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/AiCardBlindSignContractTest.java` |
| 行数 | 528 行 |
| **来源未知** | `git status` 实证：`??`（untracked，未入仓） |
| 主题 | R232-P2-05 盲签隔离契约（卡片层 rowView 同源遮蔽 + arbitrate/finalRuling 无 AI 倾向） |
| 关键 mock 形态 | `L203-208` `review(92L, GATE_ID, "MARKET_PM", null, null)` — `decision IS NULL` 但**显式断言 rowView 遮蔽**（非假绿） |
| 与 §4 旧 A4 区别 | 旧 A4 是「真库不可能行 + 假绿」；本文件是「真库可达 IN_FLIGHT 形态 + 显式断言遮蔽」——**修复范式正确** |
| 影响 | 1. `check-pre-commit.sh` 门禁 0（untracked 引用）当前无法守护（文件未在索引内）；2. 该文件正式入仓前，A4 重写工作无法宣称完成 |

### 6.2 前端：blind-sign-render.test.ts

| 字段 | 值 |
|------|-----|
| 文件 | `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/blind-sign-render.test.ts` |
| 行数 | 127 行 |
| **来源未知** | `git status` 实证：`??`（untracked） |
| 主题 | 盲签隔离契约 · 渲染层（与 6.1 镜像契约） |
| 关键断言 | `L90-91` `cells[1]!.text()).toBe("—")` + `cells[2]!.text()).toBe("—")` — 判定/意见渲染「—」安全占位（与后端 rowView 遮蔽 `decision/opinion IS NULL` 对偶） |
| 影响 | 1. 后端测试 + 前端渲染层测试**双双在途**——同时入仓才能真正闭环盲签契约；2. 当前状态是「双方代码都正确，但都不在仓内」，CI 全绿也不代表契约有守护 |

### 6.3 §6 结论

- **双双未跟踪**：`AiCardBlindSignContractTest` + `blind-sign-render.test.ts` 是**契约配对**测试，**任意一方单独入仓都会打破另一方的「参考基线」**——必须同步 `git add`。
- **风险路径**：兄弟会话完成后若只 commit 后端测试而忘 commit 前端测试（或反之），前端组件 `cells.text() === "—"` 断言就会失去后端契约支撑变成「无法回归失败的假绿」。
- **门禁现状**：`.claude/hooks/check-pre-commit.sh` 门禁 0（`L53-118 run_untracked_gate`）会在 commit 时扫 staged 内容是否引用 untracked 文件名——如果兄弟会话文件**被 MD 文档引用但未 add**，会被门禁 0 拦截。这恰好是「为什么是兄弟会话而非已入仓」的设计意图。
---

## §7 限制与未验证项

### 7.1 本报告限制

| # | 项 | 实证 | 影响 |
|---|----|------|------|
| 1 | 未跑 `mvn test` / `vitest run` | 任务约束 | 报告仅盘点**形态**，未实证**真跑结果** |
| 2 | 未跑 `node scripts/check-api-contract-fe-be.mjs` | 任务约束 | 仅读取 `--help` 与代码结构（`L1-120`），未实跑 orphan 基线对比 |
| 3 | `P2_6_2_DualSignStageGuardTest` 全文 7 维断言未完整列出 | `L132` 抽样 | 仅展示 `@Mock` 与 `Mockito.mock` 形态，未列 7 个 `@Test` 全清单 |
| 4 | `card-components.test.ts` 内部 `describe` 未抽样 | 行数 249 | 仅列文件级元信息 |
| 5 | `card-registry.test.ts` 内部 `it` 未抽样 | 行数 169 | 同上 |
| 6 | `cross-repo-orphan-baseline.json` 239 路径未全列 | 文件 249 行 | 仅列与工作流相关的端点（§5），未全列 |
| 7 | `mock合法性与已知死路登记-20260908.md` 70 行未全文引用 | 真源路径已列 | 仅抽取 A1-A4 与规约三条硬规则 |
| 8 | 前端 `_shared/ai-cards/ai-copilot.test.ts`（356 行）等未盘点 | `list_dir` 实证存在 | 与任务清单外的辅助测试文件未覆盖 |

### 7.2 未验证项（需后续探针补）

1. **catalog-reconcile.test.ts** 486 行未细看 — Catalog 对账哨兵的具体断言维度未列
2. **盲签后端 `GateReviewService.rowView()` 真实实现** — 仅看 mock 测试断言，未读真源 `GateReviewService` 的 `rowView` 方法（应在 `org/ruoyi/ipd/service/`）
3. **`.githooks/pre-commit` 14 行 / `post-commit` 30 行** — 仅看存在性，未看其是否真触发 `check-pre-commit.sh`（从 `exit 0` 与「repowise hook」注释推测是 stub）
4. **兄弟会话文件的实际作者 / commit 计划** — 兄弟会话非本会话能控制，需 owner 协同
5. **`scripts/check-api-contract-fe-be.mjs` 751 全文** — 仅读 L1-120（ratchet / 退出码逻辑），未读 L121-751（orphan 匹配算法）

### 7.3 跨探针一致性

- 本报告 §1 / §2 与 `20260927-a-backend-probe.md` / `20260927-b-frontend-probe.md` 互为补完（前者覆盖后端 / 前端全栈盘点，本报告聚焦契约测试与字段一致性）。
- §5 SSE 扫描与 `20260927-a-backend-probe.md` 第 4 节（接口扫描）数据**无冲突**。
- §6 兄弟会话在途测试为**新发现**，未被前 3 个探针记录。

---

## 附录 A：commit hash 全量索引

### A.1 后端仓 `/Users/mac/Documents/ruoyi-ai/`

| 文件 | commit | 日期 |
|------|--------|------|
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/AiCardBlindSignContractTest.java` | **untracked** | — |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/GateReviewObserverAcceptanceTest.java` | `f987e0288b15b777c116f697b39744a56140f5c7` | 2026-09-21 20:38 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/P2_6_2_DualSignStageGuardTest.java` | `f987e0288b15b777c116f697b39744a56140f5c7` | 2026-09-21 20:38 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceTest.java` | `2528d8707d91199e97b31be727b22fa55e8386c6` | 2026-09-24 11:23 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceInstantiateBatchTest.java` | `2528d8707d91199e97b31be727b22fa55e8386c6` | 2026-09-24 11:23 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageActionServiceTransactionAnnotationTest.java` | `2528d8707d91199e97b31be727b22fa55e8386c6` | 2026-09-24 11:23 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdSseController.java` | `7992c386703fea163a9a527f23ddd6f6ddec2a91` | 2026-09-11 16:54 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GateReviewController.java` | `27d6655fab460c2f629f825d6c244a81ada23023` | 2026-09-26 13:47 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java` | `27d6655fab460c2f629f825d6c244a81ada23023` | 2026-09-26 13:47 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AiCopilotController.java` | `27d6655fab460c2f629f825d6c244a81ada23023` | 2026-09-26 13:47 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AiSuggestionController.java` | `34f97b9fc309796f465b05a365a6648dbada82e3` | 2026-09-27 07:01 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GatePrecheckController.java` | `34f97b9fc309796f465b05a365a6648dbada82e3` | 2026-09-27 07:01 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/common/ApiV1Response.java` | `cf708585416f124854267dcca195d03a593292ad` | 2026-09-24 10:57 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/common/ApiV1ErrorCode.java` | `cf708585416f124854267dcca195d03a593292ad` | 2026-09-24 10:57 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/AiSuggestReq.java` | `b6d33a54f18dd1cc3fe4448b2f12f5ae3ef6de0a` | 2026-09-27 04:18 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/AiSuggestResp.java` | `b6d33a54f18dd1cc3fe4448b2f12f5ae3ef6de0a` | 2026-09-27 04:18 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/AiCopilotReq.java` | `b6d33a54f18dd1cc3fe4448b2f12f5ae3ef6de0a` | 2026-09-27 04:18 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/AiCopilotResp.java` | `b6d33a54f18dd1cc3fe4448b2f12f5ae3ef6de0a` | 2026-09-27 04:18 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/StageActionFieldsReq.java` | `b6d33a54f18dd1cc3fe4448b2f12f5ae3ef6de0a` | 2026-09-27 04:18 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/GateChecklistItem.java` | `bbf0ff495e94febd7493b062f313567fc2c46edd` | 2026-09-05 19:42 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/GateChecklistView.java` | `bbf0ff495e94febd7493b062f313567fc2c46edd` | 2026-09-05 19:42 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiSuggestionService.java` | `75d7145726952392e3ea1a737579efa0a836714f` | 2026-09-27 09:13 -0700 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GatePrecheckService.java` | `75d7145726952392e3ea1a737579efa0a836714f` | 2026-09-27 09:13 -0700 |
| `scripts/baselines/api-contract-orphan-baseline.json` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `scripts/baselines/cross-repo-orphan-baseline.json` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `scripts/check-api-contract-fe-be.mjs` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `scripts/check-sse-contract.sh` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `scripts/check-contract-tri-source.sh` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `scripts/api-contract/orphan-gate-lib.mjs` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `.claude/hooks/check-pre-commit.sh` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `.githooks/pre-commit` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `.githooks/post-commit` | `ec841ed8547cbe06816c6c6be3bd38a392426820` | 2026-09-26 13:30 -0700 |
| `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md` | `3ab3c094740d81d6e2f3357efe8cc6a823353bae` | 2026-09-08 10:05 -0700 |

### A.2 前端仓 `/Users/mac/Documents/ruoyi-ipd-web/`

| 文件 | commit | 日期 |
|------|--------|------|
| `apps/web-antd/src/views/ipd/_shared/ai-cards/blind-sign-render.test.ts` | **untracked** | — |
| `apps/web-antd/src/views/ipd/_shared/ai-cards/card-components.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ai-cards/card-registry.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ai-cards/catalog-reconcile.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ai-suggest.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ai-assistant.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ipd-state-machines.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ipd-enums.test.ts` | `e52763ddae19aa3a93b46ed7b6cccbb24afea524` | 2026-09-27 07:51 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ipd-permission-codes.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/_shared/ipd-error-text.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/_shared/zk-ipd-rules.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/admin/gate-detail/index.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/admin/gate-detail/button-policy.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/admin/gate-elements/index.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/views/ipd/admin/gate-elements/button-policy.test.ts` | `76293644702d1ec147b5c7a7385faf6aaee5359b` | 2026-09-27 09:30 -0700 |
| `apps/web-antd/src/api/ipd/gate-review.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/ipd/stage-action.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/ipd/ai-copilot.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/ipd/ai-suggest.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/ipd/gate-precheck.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/utils/message.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/aiflow/runtime.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |
| `apps/web-antd/src/api/core/auth.ts` | `4303a5c2a5bcfb0f0e24331d113174009ff27a50` | 2026-09-27 09:59 -0700 |

---

## 附录 B：api-contract-orphan-baseline.json 快照（37 行）

> `scripts/baselines/api-contract-orphan-baseline.json`（37 行，commit `ec841ed8547cbe06816c6c6be3bd38a392426820`）
> 实证：`Read` 全文件

| 关键字段 | 值 |
|---------|-----|
| `$schema_version` | 1 |
| `generated_at` | 2026-09-25T15:59:21.974Z |
| `git_head` | 025e5ac06cb1d55ca04a80923e65ada103243bf7 |
| `gen_cmd` | `node scripts/check-api-contract-fe-be.mjs --update-baseline` |
| `count` | **5** |
| 当前孤儿端点 | `/api/v1/audit-logs`, `/api/v1/audit-logs/export`, `/api/v1/negative-feedbacks/by-project/{VAR}`, `/api/v1/negative-feedbacks/by-severity/{VAR}`, `/api/v1/sop-templates/{VAR}/instantiate` |
| `paths_sha256` | `945f067ea821b8cebb60018d58ada666283581dd50e9ecc12d65a32faa1c22ff` |
| growth_log | 22 → 9 → 6 → **5**（持续递减，棘轮机制工作） |
| `notice` | 脚本独占写；人工编辑必被 sha256 自洽 + git HEAD 硬闸拦截；棘轮只减不增；新孤儿豁免走 `docs/ipd-系统说明/api-internal-whitelist.json` |

---

## 附录 C：check-pre-commit.sh 5 道门禁

> 实证：`Read` `.claude/hooks/check-pre-commit.sh` 全文 294 行

| 门禁 | 函数 | 行号 | 触发脚本 |
|------|------|------|----------|
| **0** untracked 引用 | `run_untracked_gate` | `L53-118` | 扫描 staged 内容是否引用 untracked 文件名 |
| **1** drift (doc↔db) | `run_drift_gate` | `L120-143` | doc/schema vs 真库 DDL 漂移 |
| **2** contract tri-source | `run_contract_gate` | `L145-171` | 契约三源（spec↔contract↔code）一致 |
| **3** ratchet (api-contract-fe-be) | `run_ratchet_gate` | `L173-208` | `node scripts/check-api-contract-fe-be.mjs` 默认 ratchet=fail，exit 位掩码 0/1/2/4 可叠加 |
| **4** shell var multibyte | `run_shell_var_gate` | `L210-255` | shell 脚本变量多字节安全 |

> 执行顺序：`L257-261` 默认跑全部；`L264+` fast 模式跑 0+3，跳过 1+2。

---

> **报告生成完毕。** 关联蜂群探针：a-backend-probe / b-frontend-probe / d-wiki-probe / **e-contract-probe**。
