# Track C — pm-skills 接入与 AI 引导编排 任务分解（分节草稿）

> **状态**：草稿（供主计划 Track C 合入前评审）　**产出日期**：2026-09-28
> **范围**：方法论话术库 + 引导编排器 + command 降级层 + 前端接线。**本稿只产出文档**（落盘本文件），不落任何 Java/TS/Vue 文件、不执行任何 DDL、不改任何既有文件。
> **依赖（只读事实源）**：
> 1. 映射 SSOT：`§3-pm-skills映射.md`（§3.1 真名清单、§3.2 69/69 映射表、§3.3 原则、§3.4.2-6 command 降级口径）
> 2. Track A：`Track-A-任务分解.md`（A0 全局规约、A2 `IpdSubStageService`/`IpdActionSkillMapService`、A3 `SubStageView`/`ActionSkillView`/`SubStageController.buildGuide`、A4 `SubStageGuideTool`/`GET /guide-events`、A5 `SubStageGateService`）
> 3. 主计划：`开发计划-AI工作界面六阶段小阶段化-20260928.md`（Global Constraints 1-21、§2 22 小阶段表、§2.8 五不变量）
> 4. 编译期动作 SSOT：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java` + `org/ruoyi/ipd/domain/ActionDef.java`（record 字段 `code,name,stage,ownerRole,depth,blocking,applicable,valueFields,bioFeature,gate,execMode`，实证 ActionDef.java L12-24）
> 5. 前端单轨红线：`ruoyi-ipd-web/docs/copilotkit单轨融合契约-20260928.md` §1.1 五条
> 6. 前端现状：`apps/web-antd/src/views/ipd/_shared/ai-assistant.vue`（970 行，CopilotKitProvider+IpdAiCardRenderHost+自绘四帧聊天）、`ai-cards/copilotkit-render.ts`、`api/ipd/http.ts`（`ipdGet` L22）、`api/ipd/ai-copilot.ts`、`vitest.ipd.config.mts`（include 白名单）、`ipd-theme.css`（`--ipd-*` token L16-32）
> 7. CopilotKit Vue 真实 API：`§4-CopilotKit-Vue-API纠正.md`（14 composable 签名表、R1-R8 纠正表）
> 8. pm-skills 知识库原文：`最佳实践/OpenMCP/` PM_Skills 相关文章（经 §3 交叉验证引用）
>
> **纪律**：SQL 一律标「**待 owner apply**」且不对真库执行任何写；新 Java 测试一律 `@Tag("dev")`（根 pom L479 `<groups>${profiles.active}</groups>`，无 tag 静默跳过假绿）；所有 "Commit" 步骤按 Global Constraints #10 替换为「暂存 + 输出 diff 摘要待批」，文中 commit 信息仅为获批后备用文案；不改 ActionCatalog 名称口径、不加新写入端点、不建第二聊天 UI、不动 `org.ruoyi.service.coding.harness`、不动 `apps/web-antd/package.json`；推断标「推断」，查不到标「未取到」。

---

## 0. 任务总览与依赖顺序

| Task | 内容 | 依赖 | allowedPaths（写权限） | 验证命令 |
|---|---|---|---|---|
| **C1** | 方法论话术库 `GuideScriptCatalog`（69 动作引导话术 + 四级绑定强度）+ 合同测试 | §3 定稿口径（§3.2/§3.2.8） | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/GuideScriptCatalog.java`（新）+ `src/test/java/org/ruoyi/ipd/seed/GuideScriptCatalogContractTest.java`（新） | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=GuideScriptCatalogContractTest test` |
| **C3** | command 降级层 `CommandDegrader`（42 命令 → skill 名+步骤话术，纯函数）+ 测试 | 可与 C1 并行（互不依赖） | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/copilotkit/CommandDegrader.java`（新）+ `src/test/java/org/ruoyi/ipd/copilotkit/CommandDegraderTest.java`（新） | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=CommandDegraderTest test` |
| **C2** | 引导编排器 `SubStageGuideOrchestrator`（sort_order 序列 + §2.8 不变量④投影 + 话术/降级装配）+ `guide-events` 增量键下发 | C1、C3；Track A 的 A2/A3/A4/A5 已落地 | `org/ruoyi/ipd/vo/GuideSequenceView.java`（新）+ `org/ruoyi/ipd/service/SubStageGuideOrchestrator.java`（新）+ `org/ruoyi/ipd/controller/SubStageController.java`（**Modify**：guideEvents 方法体内追加 2 键 + 换 intro 来源 + 第 5 依赖）+ `src/test/java/org/ruoyi/ipd/service/SubStageGuideOrchestratorTest.java`（新）+ `src/test/java/org/ruoyi/ipd/controller/SubStageControllerTest.java`（**Modify**：追加用例 + 既有 guideEvents 用例补桩） | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=SubStageGuideOrchestratorTest,SubStageControllerTest,SubStageGuideToolTest test` |
| **C4a** | 前端纯函数层 `guide-script.ts`（AG-UI 帧解析→话术 chips→agent context JSON）+ vitest | 可与 C1/C3 并行（wire 形状以 C2 的 `GuideStepView` 为准，先按本稿 §2.2 契约写测试） | `apps/web-antd/src/api/ipd/guide-script.ts`（新）+ `apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script.ts`（新）+ `apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script.test.ts`（新） | `pnpm exec vitest run --config vitest.ipd.config.mts`（仓根） |
| **C4b** | 接线层：`useAgentContext` 上下文注入 + chips UI + `ai-assistant.vue` 补丁 | C2 契约冻结、C4a；与 Track B5 **串行**（共享 `ai-assistant.vue`） | `apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script-host.ts`（新）+ `guide-suggestion-bar.vue`（新）+ `guide-suggestion-bar.test.ts`（新）+ `ai-assistant.vue`（**Modify**：补丁段） | 前端三件套（Global Constraints #9） |

**依赖链（一句话）**：C1 ∥ C3（两个纯静态库并行）→ C2（编排器消费 C1+C3+Track A 服务，扩 `guide-events` 下发面）→ C4b（消费 C2 wire 契约）；C4a 仅依赖本稿 §2.2 的 wire 契约可先行。跨轨依赖：C2 要求 Track A 的 A2-A5 落地（消费 `IpdSubStageService`/`IpdActionSkillMapService`/`SubStageGateService.pendingBlockingActions`）；C4b 与 Track B5 在 `ai-assistant.vue` 上走串行窗口（见 §7 并行编排表）。

**与既有轨的边界（防双轨声明）**：
- 引导**执行**仍走既有写路径（`StageActionService`/`stage_actions`），C2 是**只读编排 + 话术装配**，零新写入端点（单轨红线 #4）；
- 阻断门禁的**判定执行**仍由 A5 `SubStageGateService.assertAdvanceAllowed`（40001 fail-closed）收口，C2 只消费 `pendingBlockingActions` 做**只读投影**，不复制门禁判定逻辑（单轨红线 #5 精神）；
- `org.ruoyi.service.coding.harness` 自研 harness **零接触**（其 prompt/技能机制与本 Track 的 pm-skills 话术层完全独立）；
- command 降级只发生在话术/引导层（§3.4.2-6），**不修改** pm-skills 上游、不假装 slash 可执行。

---

## C0. 全局规约（每个 Task 都适用，继承 Track A0 + Global Constraints）

1. **构建与复核命令**（错峰、单模块、无 `-am`、无 `clean`，Track A0.1 同款）：
   ```bash
   export PATH="$HOME/tools/maven/bin:$PATH"
   export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
   cd /Users/mac/Documents/ruoyi-ai
   mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=XxxTest test
   ```
2. **测试规约**：新测试类必须 `@Tag("dev")`；mock 夹具必须是真库写入路径可产生的数据组合（`is_blocking` 只 '0'/'1'、轻管动作不造 `DELAYED`、动作名取 `ActionCatalog.byCode(code).name()` 真名——Track A0.2）；风格 JUnit5 + Mockito + AssertJ + `@DisplayName`。
3. **接口契约**：响应一律 `ApiV1Response<T>`（code=0/message="ok"/data/timestamp/traceId），禁用基线 `R<T>`；业务异常 `IpdBusinessException(ApiV1ErrorCode, String)`；ID 输出 `String.valueOf(...)`（Track A0.3）。
4. **单轨红线五条**（契约 §1.1）逐条对照本 Track：①C4 只在 `ai-assistant.vue` 既有聊天宿主内补 chips/上下文，不开第二聊天 UI；②C4 不加卡片注册表条目、不建平行卡片体系；③解析失败一律纯文本降级（话术以文本消息兜底，chips 不出）；④零新写入端点；⑤R2/R3 校验逻辑不复制。
5. **颜色红线**（Global Constraints #21 + preferences.ts L64-93）：新组件只用 `ipd-theme.css` 的 `--ipd-*` token（`--ipd-blue/-blue-soft/-green/-amber/-red/-text/-muted/-line/-surface/-bg/-focus-ring-color`，实证 ipd-theme.css L16-32）；组件层主色唯一入口 `preferences.ts theme.*` + `bootstrap.ts updatePreferences`（本 Track **不改这两处**，无调色诉求）；**禁止** `:root{--primary:...}`（无效代码）与硬编码色值。
6. **提交纪律**（Global Constraints #10）：不执行 git commit/push；"Commit" 步 = 「暂存 + 输出 diff 摘要待批」，commit 信息文案仅备获批后使用。
7. **前端验证三件套**（Global Constraints #9，仓根执行）：`pnpm run check:type` + `pnpm exec vitest run --config vitest.ipd.config.mts` + `pnpm run build:antd`。vitest 测试面由 `vitest.ipd.config.mts` include 白名单覆盖 `apps/web-antd/src/views/ipd/**/*.test.ts` 与 `apps/web-antd/src/api/ipd/**/*.test.ts`（实证该文件 include 段）——**C4 的新测试落在这两个目录内，不改 `vitest.ipd.config.mts`**（避开 Track E 登记的唯一共享门禁文件串行窗口）。

---
## C1. 方法论话术库落库/配置

### C1.0 存储形态决策（结论：Java 常量类 `GuideScriptCatalog`，不扩 `ipd_action_skill_map` 列）

| 候选形态 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| a) `ipd_action_skill_map` 扩 `guide_prompt` 列 | 与 §3 映射同表同行 | ① §6 口径该表唯一写者 = owner DDL（§6.4.3 69 行 seed 标「待 owner apply」，§6.6-4 该表 DDL 尚未 apply）；扩列即推翻已评审口径，且每次话术迭代都要 owner apply SQL；② 话术评审无法走代码 review | 否 |
| b) 独立配置表 `ipd_guide_script` | 可运行期热更 | 同 a) owner-apply 依赖；多一张表+一套 CRUD=更多写端点风险（单轨红线 4）；Track A 未规划该表，跨 Track 契约面扩大 | 否 |
| c) 资源文件 `resources/ipd/guide-scripts.yaml` | 与代码解耦 | ① 仓内无既有 YAML 资源加载先例（PromptTemplates.java L17-84 是 Java 字符串常量先例）；② 多一个解析器与失败模式；③ 合同测试多一步 IO/解析 | 否 |
| d) **Java 常量类（选用）** | ① 仓库先例：ActionCatalog.docTypeOf javadoc（L240-255）R236 决策「纯派生数据放在目录旁即 SSOT 同处，由哨兵测试锁定」；PromptTemplates.java（L17 `public final class`、L20 `static final String SLOT`、L71 `render`、L84 `templateOf`）确立「常量类+静态渲染」形态；② 话术纯静态 1:1 于 action_code，话术评审=改代码走 PR；③ 合同测试 hermetic 不连库（对齐 Track A1 `SubStageSeedSqlContractTest` 哲学）；④ C3 降级话术同在 Java 侧，防双事实源；⑤ 回滚=删文件零影响 | 缺运行期热更（话术变更频率=方法论评审频率，不需要） | **采用** |

> **SQL 说明**：C1 不产出任何 DDL/DML——`ipd_action_skill_map` 维持 §6 口径（skill_names NULL=未定稿，**待 owner apply**），Track C 不碰该表写面。若 owner 后续要求话术入库：常量类即数据源可原样导出，仍标「待 owner apply」，不在本 Track 范围。

### C1.1 Files

| 路径（相对 `ruoyi-modules/ruoyi-ipd/`） | 动作 | 说明 |
|---|---|---|
| `src/main/java/org/ruoyi/ipd/seed/GuideScriptCatalog.java` | **Create** | 69 条 `GuideScript` 常量 + 登记话术模板 + fail-loud 查询器 |
| `src/test/java/org/ruoyi/ipd/seed/GuideScriptCatalogContractTest.java` | **Create** | 合同测试 `@Tag("dev")`：69 行齐 / 码集=ActionCatalog / 话术非空 / 四级码集精确断言 / 词表∈§3.1 真名集 |

### C1.2 Interfaces

**Consumes（既有，只读复用）**：
- `org.ruoyi.ipd.seed.ActionCatalog.ALL: List<ActionDef>`（ActionCatalog.java L25）——码集对账基准；`ActionCatalog.byCode(String): ActionDef`（L129）
- `org.ruoyi.ipd.domain.ActionDef`（ActionDef.java L9-21）：`record ActionDef(code,name,stage,ownerRole,depth,blocking,applicable,valueFields,bioFeature,gate,execMode)`
- §3 映射 SSOT §3.2（69 行绑定 skill+command 链+引导要点）与 §3.2.8（四级码集）

**Produces（新增）**：

```java
public enum BindLevel { BIND, WEAK, CANDIDATE, NONE }   // §3.2.8 四级绑定强度
public record GuideScript(String actionCode, BindLevel bindLevel,
    List<String> skillNames,    // §3.1 真名；NONE 为空
    List<String> commandChain,  // §3.2 command 链（含参数变体，如 "/interview prep"）；可空
    String guidePrompt) {}      // 引导话术（非空）
// GuideScriptCatalog 公开面：
public static final String REGISTER_TEMPLATE            // 不绑定动作结构化登记话术模板
public static List<GuideScript> all()                   // 69 条不可变列表
public static GuideScript scriptOf(String actionCode)   // 未命中抛 IpdBusinessException(NOT_FOUND)
```

### C1.3 `GuideScriptCatalog.java`（Create，完整代码）

```java
package org.ruoyi.ipd.seed;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法论话术目录（Track C1）：69 动作 ×（绑定强度 + pm-skills 真名 + command 链 + 引导话术）。
 *
 * <p>存储形态决策见 Track-C 详稿 C1.0（R236 先例：纯派生数据放目录旁，哨兵测试锁定）。
 * 数据源 = §3-pm-skills映射.md §3.2 逐行转写（skill/command 只用 §3.1 真名，禁改 ActionCatalog 名称口径）。
 * 不绑定（NONE）动作走 {@link #REGISTER_TEMPLATE} 结构化登记话术，skillNames 为空。
 */
public final class GuideScriptCatalog {

    /** §3.2.8 四级绑定强度。 */
    public enum BindLevel { BIND, WEAK, CANDIDATE, NONE }

    /** 单动作话术行（1:1 于 ActionCatalog 的 action_code）。 */
    public record GuideScript(String actionCode, BindLevel bindLevel,
                              List<String> skillNames, List<String> commandChain,
                              String guidePrompt) {
    }

    /** 不绑定动作的结构化登记话术模板（占位符 {actionName}）。 */
    public static final String REGISTER_TEMPLATE =
        "本动作走结构化登记：{actionName}。请按页面表单逐项录入并留存凭证，AI 不代填决策值，仅做必填项与区间校验提醒。";

    private static final Map<String, GuideScript> SCRIPTS = new LinkedHashMap<>();

    private GuideScriptCatalog() {
    }

    private static void add(String code, BindLevel level, List<String> skills,
                            List<String> commands, String prompt) {
        SCRIPTS.put(code, new GuideScript(code, level,
            List.copyOf(skills), List.copyOf(commands), prompt));
    }

    private static void none(String code, String prompt) {
        add(code, BindLevel.NONE, List.of(), List.of(), prompt);
    }

    // ---- 阶段一 CONCEPT（12，§3.2.1）----
    static {
        add("C01", BindLevel.BIND,
            List.of("interview-script", "summarize-interview", "market-sizing"),
            List.of("/interview prep", "/interview summarize"),
            "先用 JTBD/Mom Test 提纲做痛点访谈，逐份归纳访谈信号，再估 TAM/SAM/SOM，产出市场调研报告。");
        add("C02", BindLevel.BIND,
            List.of("competitor-analysis", "porters-five-forces", "swot-analysis"),
            List.of("/competitive-analysis", "/market-scan"),
            "先做竞品四维对比（功能/价格/渠道/技术路线），再用五力+SWOT 收敛差异化空间。");
        add("C03", BindLevel.BIND,
            List.of("user-personas", "market-segments", "user-segmentation"),
            List.of("/research-users"),
            "从访谈证据提炼三类画像，收敛 3-5 个细分市场并评估匹配度，产出目标客户画像。");
        add("C04", BindLevel.BIND,
            List.of("pestle-analysis", "market-segments"),
            List.of("/market-scan"),
            "按目标国别跑 PESTLE（法规/文化/电压/插头等变量），输出区域市场差异清单。");
        none("C05", "轻管动作不强制交付物：仅登记技术可行性预研的状态/日期/备注三要素即完成。");
        add("C06", BindLevel.BIND,
            List.of("product-vision", "value-proposition", "positioning-ideas"),
            List.of("/strategy", "/value-proposition", "/market-product"),
            "先立产品愿景与 JTBD 六段式价值主张，再从竞品定位角度收敛差异化，产出产品概念说明书。");
        add("C07", BindLevel.BIND,
            List.of("pricing-strategy", "monetization-strategy"),
            List.of("/pricing"),
            "用定价模型+竞品价格+支付意愿测算，产出成本与定价测算表。");
        add("C08", BindLevel.BIND,
            List.of("market-sizing", "brainstorm-okrs", "north-star-metric"),
            List.of("/plan-okrs", "/north-star"),
            "录入四项基准值（上市6个月销售目标/渠道数/NPS/场景数）并锁口径（G1 后锁定，为奖金池/共担KPI 计算基准），产出商业计划书 Charter。");
        none("C09", "治理决策走结构化表单：录入项目等级（S/A/B）+差异化系数（S=1.5/A=1.0/B=0.8）+产品组长 A 角+双签审计；AI 不代填系数，仅做必填项与区间校验提醒。");
        add("C10", BindLevel.CANDIDATE,
            List.of("privacy-policy"),
            List.of("/privacy-policy"),
            "强制上传知识产权检索报告（含 FTO）；专利检索依赖人工/外部工具，AI 只提醒归档与完整性，隐私合规文本可辅助起草。");
        add("C11", BindLevel.BIND,
            List.of("summarize-meeting", "strategy-red-team", "stakeholder-map"),
            List.of("/red-team-prd", "/stakeholder-map", "/meeting-notes"),
            "会前用红队攻击 Charter 关键假设，按干系人策略对齐双签人，会后纪要+行动项留痕（G1 立项 Go/No-Go）。");
        add("C12", BindLevel.BIND,
            List.of("privacy-policy"),
            List.of("/privacy-policy"),
            "按 GDPR 特殊类别数据+个保法+《人脸识别技术应用安全管理办法》逐项勾稽，产出合规审查清单（法律红线，S/A/B 级均阻断）。");
    }
```

（续 `GuideScriptCatalog.java`；每阶段一个 static 初始化块，按声明序执行——分段仅为可读性）

```java
    // ---- 阶段二 PLAN（13，§3.2.2）----
    static {
        add("P01", BindLevel.BIND,
            List.of("create-prd", "strategy-red-team"),
            List.of("/write-prd", "/red-team-prd"),
            "按八段式起草 PRD，再用红队找最脆弱前提并修订，产出产品需求规格书。");
        add("P02", BindLevel.BIND,
            List.of("prioritize-features", "prioritization-frameworks", "outcome-roadmap"),
            List.of("/triage-requests", "/transform-roadmap"),
            "选 RICE/ICE 等框架排需求池，把功能清单转 outcome 路线图，产出版本规划表。");
        add("P03", BindLevel.CANDIDATE,
            List.of("shipping-artifacts"),
            List.of(),
            "轻管仅登记完成；若产出 AI 代码方案，可用可审查文档集骨架组织设计文档（轻量提示，不产强制交付物）。");
        none("P04", "硬件方案（ID/结构/硬件/固件）无对应技能：登记完成即可。");
        add("P05", BindLevel.CANDIDATE,
            List.of("shipping-artifacts"),
            List.of(),
            "登记完成；软件概要设计如需文档骨架可轻量提示可审查文档集（推断，不产强制交付物）。");
        add("P06", BindLevel.CANDIDATE,
            List.of("customer-journey-map"),
            List.of(),
            "登记完成；解决方案场景梳理可用客户旅程图轻量提示（推断）。");
        none("P07", "供应链评估无对应技能：登记完成即可。");
        add("P08", BindLevel.CANDIDATE,
            List.of("sprint-plan"),
            List.of(),
            "登记完成；必须登记关键里程碑日期（上市准时率/窗口命中率 KPI 依赖它），排期思路可轻量提示 sprint-plan。");
        none("P09", "资源与预算评估走表单登记：登记完成即可。");
        none("P10", "按国别认证模板库带出清单登记（认证与法规清单确认）；阻断走系统门禁（认证缺失无法上市），AI 不替代清单判定。");
        add("P11", BindLevel.CANDIDATE,
            List.of("pre-mortem"),
            List.of(),
            "轻管不产强制交付物；可用 Tigers/Paper Tigers/Elephants 话术口头引导风险识别并登记。");
        add("P12", BindLevel.BIND,
            List.of("value-proposition", "pricing-strategy", "value-prop-statements"),
            List.of("/value-proposition", "/pricing"),
            "卖点用 JTBD 六段式收束、定价复核毛利，产出差异化卖点清单+定价策略。");
        add("P13", BindLevel.BIND,
            List.of("summarize-meeting", "strategy-red-team"),
            List.of("/red-team-prd", "/meeting-notes"),
            "会前红队验证卖点可交付性与毛利复核，会后纪要双签（G2 差异化确认）。");
    }

    // ---- 阶段三 DEV（11，§3.2.3）----
    static {
        none("D01", "详细设计（结构/硬件/软件）登记完成（三要素登记）。");
        none("D02", "首版 BOM 冻结与采购为硬件/采购动作，无对应技能：登记完成即可。");
        none("D03", "手板/EVT 样机为硬件动作，无对应技能：登记完成即可。");
        add("D04", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；单测场景可轻量提示 test-scenarios（推断，不产强制交付物）。");
        add("D05", BindLevel.BIND,
            List.of("summarize-meeting", "outcome-roadmap"),
            List.of("/meeting-notes", "/sprint retro"),
            "双周纪要盯进度+场景完整度（市场 PM 视角）；连续 2 次 P0 阻塞未升级自动升级双方产品组长（G3）。");
        add("D06", BindLevel.BIND,
            List.of("analyze-feature-requests", "prioritize-features", "strategy-red-team"),
            List.of("/triage-requests", "/red-team-prd"),
            "变更请求归类→影响/优先级评估→红队检验必要性，产出需求变更单（双签否决；系统自动统计变更率供 KPI 取数）。");
        none("D07", "模具开发与 T1 试模为硬件动作，无对应技能：登记完成即可。");
        none("D08", "成本复核为表单登记（与 C07 区分：C07 绑定价技能，本动作不绑）：登记完成即可。");
        add("D09", BindLevel.CANDIDATE,
            List.of("release-notes"),
            List.of(),
            "登记内测发布日期；发布说明可轻量提示 release-notes（推断）。");
        add("D10", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；联调用例可轻量提示 test-scenarios（推断）。");
        none("D11", "无算法评测技能：引导录入实测 FAR/FRR 数值字段并提示与基线横向对比（数值登记）。");
    }
```

（续 `GuideScriptCatalog.java`）

```java
    // ---- 阶段四 VALID（12，§3.2.4）----
    static {
        add("V01", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "硬件 DVT 登记完成；测试场景骨架可轻量提示（推断）。");
        none("V02", "认证测试送检：登记证书编号+通过日期（不强制上传扫描件）；阻断走系统门禁。");
        add("V03", BindLevel.BIND,
            List.of("interview-script", "summarize-interview", "sentiment-analysis"),
            List.of("/interview", "/analyze-feedback"),
            "Beta 访谈提纲→逐字稿归纳→大样本情绪/主题分析，产出 Beta 试用报告。");
        add("V04", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；可用 test-scenarios 生成用例骨架（不产强制交付物）。");
        none("V05", "试产 PVT/小批量为硬件动作，无对应技能：登记完成即可。");
        add("V06", BindLevel.BIND,
            List.of("pre-mortem", "summarize-meeting"),
            List.of("/pre-mortem", "/meeting-notes"),
            "会前预演量产失败风险（A=产品组长），纪要+评审材料留痕。");
        add("V07", BindLevel.BIND,
            List.of("value-prop-statements", "grammar-check"),
            List.of("/market-product", "/proofread"),
            "文案价值表达定调后逐份校对，产出包装设计稿+用户手册（推断）。");
        none("V08", "售后与维修方案无对应技能：登记完成即可。");
        add("V09", BindLevel.BIND,
            List.of("test-scenarios", "intended-vs-implemented"),
            List.of("/test-scenarios", "/derive-tests"),
            "试点验收用例覆盖对照「文档意图 vs 实现」，产出试点交付验收报告（推断）。");
        add("V10", BindLevel.BIND,
            List.of("user-segmentation", "metrics-dashboard"),
            List.of("/setup-metrics"),
            "按人群分层定义 FAR/FRR 指标与告警阈值，分人群测试报告强制上传（推断；Z08 公平性测试并入本动作 SOP）。");
        add("V11", BindLevel.BIND,
            List.of("intended-vs-implemented", "shipping-artifacts"),
            List.of("/document-app", "/ship-check"),
            "登记对接范围与通过结论；解决方案模板升级深管时用「文档意图 vs 实现」对照审查集成偏差（推断绑定仅在解模板生效）。");
        add("V12", BindLevel.WEAK,
            List.of("grammar-check"),
            List.of("/proofread"),
            "本地化验收清单逐项打勾（语言/阿拉伯语 RTL/电压 110V-220V/插头/国别认证），闭环 C04↔V07↔L03；多语言文案可校对（推断）。");
    }

    // ---- 阶段五 LAUNCH（8，§3.2.5）----
    static {
        add("L01", BindLevel.BIND,
            List.of("gtm-strategy", "beachhead-segment", "ideal-customer-profile"),
            List.of("/plan-launch"),
            "滩头市场→ICP→信息/渠道/节奏一次成链，产出 GTM 上市方案。");
        add("L02", BindLevel.BIND,
            List.of("pricing-strategy", "gtm-motions", "monetization-strategy"),
            List.of("/pricing", "/growth-strategy"),
            "渠道组合用 gtm-motions 选型，价格体系复核毛利与竞品价，产出渠道价格政策。");
        add("L03", BindLevel.BIND,
            List.of("competitive-battlecard", "value-prop-statements", "marketing-ideas"),
            List.of("/battlecard", "/market-product"),
            "battlecard+价值主张语句做物料骨架，多语言物料对齐 V12 差异，产出工具包清单+物料。");
        add("L04", BindLevel.BIND,
            List.of("competitive-battlecard", "value-prop-statements"),
            List.of("/battlecard"),
            "培训材料以异议处理/赢单打法/价值话术为核心（推断），产出培训材料+签到记录。");
        none("L05", "首批量产与备货为硬件供应链动作：登记备货完成日期即完成。");
        add("L06", BindLevel.WEAK,
            List.of("value-prop-statements", "grammar-check"),
            List.of("/proofread"),
            "逐渠道确认上架项+文案校对，产出上架确认记录（推断：以上架文案质量替代产出物类引导）。");
        add("L07", BindLevel.BIND,
            List.of("pre-mortem", "stakeholder-map", "summarize-meeting"),
            List.of("/pre-mortem", "/stakeholder-map", "/meeting-notes"),
            "会前按 8 要素预演失败模式，销售/供应/售后干系人对齐，纪要双签后方可 L08（G4 GTM 就绪）。");
        add("L08", BindLevel.BIND,
            List.of("release-notes"),
            List.of("/sprint release"),
            "生成发布说明；上市日期录入后锁定（全部后置 KPI 起算原点，修改需双签+审计）。");
    }
```

（续 `GuideScriptCatalog.java`）

```java
    // ---- 阶段六 LIFECYCLE（9，§3.2.6）----
    static {
        add("LC01", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard", "cohort-analysis"),
            List.of("/write-query", "/setup-metrics", "/analyze-cohorts"),
            "月度回款台账取数+看板追踪+分批 cohort 对比（cohort 部分为推断）；口径=实际回款（Gavin Q2 决策）。");
        add("LC02", BindLevel.BIND,
            List.of("retro", "summarize-meeting"),
            List.of("/sprint retro", "/meeting-notes"),
            "复盘落到负责人+期限明确的行动项，产出 90 天复盘报告+纪要（G5 双签否决）。");
        add("LC03", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "回款达成率取数与口径核对；奖金池核算本身为表单计算，技能绑定仅覆盖取数与指标定义（推断）。");
        none("LC04", "治理评定走结构化评定表（三方评定=双 PM+各自产品组长，五维度市场 40-65%/研发 35-60%）：AI 不代评，仅做区间校验提醒。");
        add("LC05", BindLevel.BIND,
            List.of("sentiment-analysis", "analyze-feature-requests"),
            List.of("/analyze-feedback", "/triage-requests"),
            "反馈情绪/主题提取→问题归类分流→处理记录留痕（深管但非阻断：强制留痕+逾期提醒）。");
        add("LC06", BindLevel.CANDIDATE,
            List.of("release-notes"),
            List.of(),
            "登记完成；维护版发布说明可轻量提示 release-notes（推断）。");
        none("LC07", "生命周期状态维护（在售/限售/停产）为状态机变更记录：无方法论技能，结构化状态登记+变更留痕。");
        add("LC08", BindLevel.WEAK,
            List.of("grammar-check"),
            List.of("/proofread"),
            "停产评估为决策表单（A=产品组长），公告文案可校对（推断）。");
        add("LC09", BindLevel.BIND,
            List.of("shipping-artifacts"),
            List.of("/document-app"),
            "按「可审查文档集」骨架组织归档包转只读（R=系统自动，A=产品组长）；无代码仓时仅结构化归档清单（推断绑定）。");
    }

    // ---- 共担 KPI 归集（4，§3.2.7）----
    static {
        add("K01", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "ERP 出库取数（一期手动录入+凭证）+指标口径定义；产品组长录入，次月第 5 个工作日 18:00 前。");
        add("K02", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "渠道签约台账/CRM 取数+覆盖率指标定义。");
        add("K03", BindLevel.BIND,
            List.of("sentiment-analysis", "metrics-dashboard"),
            List.of("/analyze-feedback", "/setup-metrics"),
            "问卷有效样本≥30；NPS 文本用情绪/主题分析辅助解读（推断），数值走结构化字段。");
        add("K04", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "销售报备+交付验收记录取数+覆盖率口径定义。");
    }

    /** 69 条全量（不可变，插入序 = C→P→D→V→L→LC→K）。 */
    public static List<GuideScript> all() {
        return Collections.unmodifiableList(new ArrayList<>(SCRIPTS.values()));
    }

    /** 按动作码取话术；未命中抛 50001 fail-loud（调用方应先经 ActionCatalog 校验码）。 */
    public static GuideScript scriptOf(String actionCode) {
        GuideScript script = SCRIPTS.get(actionCode);
        if (script == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                "引导话术不存在: " + actionCode);
        }
        return script;
    }

    /** 结构化登记话术（NONE 动作统一口径）：模板占位符 {actionName} 由调用方替换。 */
    public static String registerPrompt(String actionName) {
        return REGISTER_TEMPLATE.replace("{actionName}", actionName);
    }
}
```

> 69 条计数核对：CONCEPT 12（C01-C12）+ PLAN 13（P01-P13）+ DEV 11（D01-D11）+ VALID 12（V01-V12）+ LAUNCH 8（L01-L08）+ LIFECYCLE 9（LC01-LC09）+ KPI 4（K01-K04）= **69**，与 §3.2.8「36 绑定+3 弱+12 候选+18 不绑定」逐码一致（断言由 C1.4 合同测试锁定）。

### C1.4 `GuideScriptCatalogContractTest.java`（Create，完整代码）

```java
package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.seed.GuideScriptCatalog.BindLevel;
import org.ruoyi.ipd.seed.GuideScriptCatalog.GuideScript;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C1 合同测试（hermetic，无 DB/Spring）：锁 69 行齐 + 四级码集 + 词表∈§3.1 真名集。 */
@Tag("dev")
class GuideScriptCatalogContractTest {

    /** §3.1 真名全集（9 插件 69 skills，S3 实抓 + S2 交叉一致）。 */
    private static final Set<String> KNOWN_SKILLS = Set.of(
        "brainstorm-ideas-existing", "brainstorm-ideas-new", "brainstorm-experiments-existing",
        "brainstorm-experiments-new", "identify-assumptions-existing", "identify-assumptions-new",
        "prioritize-assumptions", "prioritize-features", "analyze-feature-requests",
        "opportunity-solution-tree", "interview-script", "summarize-interview", "metrics-dashboard",
        "product-strategy", "startup-canvas", "product-vision", "value-proposition", "lean-canvas",
        "business-model", "monetization-strategy", "pricing-strategy", "swot-analysis",
        "pestle-analysis", "porters-five-forces", "ansoff-matrix",
        "create-prd", "brainstorm-okrs", "outcome-roadmap", "sprint-plan", "retro", "release-notes",
        "pre-mortem", "stakeholder-map", "summarize-meeting", "user-stories", "job-stories", "wwas",
        "test-scenarios", "dummy-dataset", "prioritization-frameworks", "strategy-red-team",
        "user-personas", "market-segments", "user-segmentation", "customer-journey-map",
        "market-sizing", "competitor-analysis", "sentiment-analysis",
        "sql-queries", "cohort-analysis", "ab-test-analysis",
        "gtm-strategy", "beachhead-segment", "ideal-customer-profile", "growth-loops",
        "gtm-motions", "competitive-battlecard",
        "marketing-ideas", "positioning-ideas", "value-prop-statements", "product-name",
        "north-star-metric",
        "review-resume", "draft-nda", "privacy-policy", "grammar-check",
        "shipping-artifacts", "intended-vs-implemented", "code-review");

    /** §3.1 真名全集（9 插件 42 commands；commandChain 首 token 必须∈此集）。 */
    private static final Set<String> KNOWN_COMMANDS = Set.of(
        "/discover", "/brainstorm", "/triage-requests", "/interview", "/setup-metrics",
        "/strategy", "/business-model", "/value-proposition", "/market-scan", "/pricing",
        "/write-prd", "/plan-okrs", "/transform-roadmap", "/sprint", "/pre-mortem",
        "/red-team-prd", "/meeting-notes", "/stakeholder-map", "/write-stories",
        "/test-scenarios", "/generate-data",
        "/research-users", "/competitive-analysis", "/analyze-feedback",
        "/write-query", "/analyze-cohorts", "/analyze-test",
        "/plan-launch", "/growth-strategy", "/battlecard",
        "/market-product", "/north-star",
        "/review-resume", "/tailor-resume", "/draft-nda", "/privacy-policy", "/proofread",
        "/ship-check", "/document-app", "/derive-tests",
        "/security-audit-static", "/performance-audit-static");

    /** §3.2.8 四级精确码集。 */
    private static final Set<String> BIND_CODES = Set.of("C01", "C02", "C03", "C04", "C06", "C07",
        "C08", "C11", "C12", "P01", "P02", "P12", "P13", "D05", "D06", "V03", "V06", "V07", "V09",
        "V10", "V11", "L01", "L02", "L03", "L04", "L07", "L08", "LC01", "LC02", "LC03", "LC05",
        "LC09", "K01", "K02", "K03", "K04");
    private static final Set<String> WEAK_CODES = Set.of("V12", "L06", "LC08");
    private static final Set<String> CANDIDATE_CODES = Set.of("C10", "P03", "P05", "P06", "P08",
        "P11", "D04", "D09", "D10", "V01", "V04", "LC06");
    private static final Set<String> NONE_CODES = Set.of("C05", "C09", "P04", "P07", "P09", "P10",
        "D01", "D02", "D03", "D07", "D08", "D11", "V02", "V05", "V08", "L05", "LC04", "LC07");

    private static Set<String> codesOf(BindLevel level) {
        return GuideScriptCatalog.all().stream()
            .filter(s -> s.bindLevel() == level)
            .map(GuideScript::actionCode)
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("69 行齐，且码集与 ActionCatalog.ALL 全等（禁改名口径）")
    void coversAll69CodesExactly() {
        List<GuideScript> all = GuideScriptCatalog.all();
        assertThat(all).hasSize(69);
        Set<String> catalogCodes = ActionCatalog.ALL.stream()
            .map(a -> a.code()).collect(Collectors.toSet());
        assertThat(all.stream().map(GuideScript::actionCode).collect(Collectors.toSet()))
            .containsExactlyInAnyOrderElementsOf(catalogCodes);
    }

    @Test
    @DisplayName("§3.2.8 四级码集精确断言：36 绑定 / 3 弱 / 12 候选 / 18 不绑定")
    void bindLevelDistributionMatchesSSOT() {
        assertThat(codesOf(BindLevel.BIND)).containsExactlyInAnyOrderElementsOf(BIND_CODES);
        assertThat(codesOf(BindLevel.WEAK)).containsExactlyInAnyOrderElementsOf(WEAK_CODES);
        assertThat(codesOf(BindLevel.CANDIDATE)).containsExactlyInAnyOrderElementsOf(CANDIDATE_CODES);
        assertThat(codesOf(BindLevel.NONE)).containsExactlyInAnyOrderElementsOf(NONE_CODES);
    }
```

（续 `GuideScriptCatalogContractTest.java`）

```java
    @Test
    @DisplayName("话术非空且无占位符残留；skill/command 词表∈§3.1 真名集")
    void promptsNonBlankAndVocabularyReal() {
        for (GuideScript s : GuideScriptCatalog.all()) {
            assertThat(s.guidePrompt())
                .as("guidePrompt %s", s.actionCode())
                .isNotBlank().doesNotContain("TODO", "TBD", "{actionName}");
            assertThat(s.skillNames())
                .as("skillNames %s", s.actionCode())
                .allMatch(KNOWN_SKILLS::contains);
            assertThat(s.commandChain())
                .as("commandChain %s", s.actionCode())
                .allMatch(c -> KNOWN_COMMANDS.contains(c.split(" ")[0]));
        }
    }

    @Test
    @DisplayName("不绑定（NONE）18 动作：skill/command 均空，话术走结构化登记口径")
    void noneActionsUseRegisterScript() {
        for (String code : NONE_CODES) {
            GuideScript s = GuideScriptCatalog.scriptOf(code);
            assertThat(s.skillNames()).as("skills %s", code).isEmpty();
            assertThat(s.commandChain()).as("commands %s", code).isEmpty();
            assertThat(s.guidePrompt()).as("prompt %s", code).contains("登记");
        }
        assertThat(GuideScriptCatalog.registerPrompt("X 动作"))
            .contains("X 动作").contains("结构化登记");
    }

    @Test
    @DisplayName("绑定/弱绑定动作必有 skill 与 command 链；候选动作必有 skill、可无 command")
    void bindingStrengthContract() {
        for (GuideScript s : GuideScriptCatalog.all()) {
            switch (s.bindLevel()) {
                case BIND, WEAK -> assertThat(s.skillNames()).as(s.actionCode()).isNotEmpty();
                case CANDIDATE -> assertThat(s.skillNames()).as(s.actionCode()).isNotEmpty();
                case NONE -> { /* 上一用例覆盖 */ }
            }
        }
    }

    @Test
    @DisplayName("scriptOf 未命中抛 50001 fail-loud")
    void scriptOfUnknownCodeFails() {
        assertThatThrownBy(() -> GuideScriptCatalog.scriptOf("A01"))
            .hasMessageContaining("引导话术不存在");
    }
}
```

### C1.5 勾选步骤（TDD 粒度）

1. [ ] **写失败测试**：创建 `src/test/java/org/ruoyi/ipd/seed/GuideScriptCatalogContractTest.java`（C1.4 全文），此时 `GuideScriptCatalog` 不存在 → 编译失败（红）
2. [ ] **跑红**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest=GuideScriptCatalogContractTest -Dsurefire.failIfNoSpecifiedTests=false`（错峰、单模块、无 `-am`、无 `clean`，Track A0.1 口径）→ 期望编译错误/断言失败
3. [ ] **最小实现**：创建 `src/main/java/org/ruoyi/ipd/seed/GuideScriptCatalog.java`（C1.3 全文：骨架 → CONCEPT/PLAN/DEV/VALID/LAUNCH/LIFECYCLE/KPI 七个 static 块 → `all()/scriptOf()/registerPrompt()`）
4. [ ] **跑绿**：同 2 命令 → 5 用例全绿；再跑 `mvn -pl ruoyi-modules/ruoyi-ipd test`（`@Tag("dev")` 在 `${profiles.active}`=dev 时被 Surefire `<groups>` 纳入，pom.xml L479）
5. [ ] **回归锚**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest='ActionCatalogTest,IpdSeedConsistencyTest,ActionExecModeMatrixSentinelTest'` → 零回归（确认未动 ActionCatalog 口径）
6. [ ] **Commit（待批）**：暂存两文件 + 输出 diff 摘要。commit 信息：`feat(ipd): C1 方法论话术目录 GuideScriptCatalog（69 动作四级绑定+真名词表合同测试）`

**依赖顺序**：C1 无前置（仅依赖只读事实源）；是 C2 的前置。与 C3 可并行（见 §7）。
**验证命令汇总**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest=GuideScriptCatalogContractTest -Dsurefire.failIfNoSpecifiedTests=false`

---

## C2. 引导编排器（SubStageGuideOrchestrator）

**目标**：按 `ipd_sub_stage.sort_order` + §2.8 不变量（④阻断动作未完成禁止推进）生成「当前小阶段→动作→话术→工具调用」引导序列，经 Track A 既有 `guide-events` 契约下发；**零新写入端点、零新读端点**（只增强既有 `GET /guide-events` 响应载荷与 intro 话术）。

**§2.8 不变量在本任务的落点**：
- ①69 动作唯一归属：步骤序列以 `IpdActionSkillMapService.listBySubStage(code)` 为准（A1 seed 已锁不变量①②③⑤）；
- ②sort 连续、③Gate sort 最大：序列顺序按映射 `sortOrder` 升序（A2.5 `listBySubStage` 已 `orderByAsc(sortOrder)`），编排器不重排；
- ④38 阻断动作门禁：`advanceGate` 复用 `SubStageGateService.pendingBlockingActions`（**不复制校验逻辑**，单轨红线 5）；
- ⑤脏数据 A01/A02/A1/A2：编排器经 `ActionCatalog.byCode` fail-loud，脏码无法进入序列。

### C2.1 Files

| 路径（相对 `ruoyi-modules/ruoyi-ipd/`） | 动作 | 说明 |
|---|---|---|
| `src/main/java/org/ruoyi/ipd/vo/GuideSequenceView.java` | **Create** | 4 个 record：`GuideSequenceView`/`GuideStepView`/`AdvanceGateView` + 复用 C3 `CommandDegrader.DegradedStep` |
| `src/main/java/org/ruoyi/ipd/service/SubStageGuideOrchestrator.java` | **Create** | 编排器（与 `SubStageGateService` 同包，复用其包级成员） |
| `src/main/java/org/ruoyi/ipd/controller/SubStageController.java` | **Modify** | 仅改 `guideEvents` 方法体：intro 换编排器话术 + `progressPatch.value` 增 `guideSteps`/`advanceGate` 两键（A4.2 既有方法，非新端点） |
| `src/test/java/org/ruoyi/ipd/service/SubStageGuideOrchestratorTest.java` | **Create** | `@Tag("dev")` 单测（Mockito，对齐 A2.7 `IpdActionSkillMapServiceTest` 形态） |
| `src/test/java/org/ruoyi/ipd/controller/SubStageControllerTest.java` | **Modify**（Track A 建） | 追加 `guideEventsCarriesGuideStepsAndGate` 用例 |

### C2.2 Interfaces

**Consumes**（Track A 产出，签名引 Track-A-任务分解.md；A 未落地前本任务编译红，见 §0 依赖链）：
- `IpdSubStageService.listAll(): List<IpdSubStage>`、`getByCode(String): IpdSubStage`（A2.4；未命中抛 50001）
- `IpdActionSkillMapService.listBySubStage(String): List<IpdActionSkillMap>`、`parseSkillNames(String): List<String>`（A2.5）
- `SubStageGateService.pendingBlockingActions(Long, String): List<String>`、`SETTLED_STATUSES`、`HISTORY_MISSING`（A5.1，包级可见——编排器同包 `org.ruoyi.ipd.service` 即可访问）
- `SubStageView`/`ActionSkillView`（A3.1）、`SubStageGuideTool.translateGuide(...)`/`cardFrame(...)`（A4.1）、`StageActionMapper`（既有）
- C1 `GuideScriptCatalog.scriptOf/all()`；C3 `CommandDegrader.degradeChain(List<String>)`
- `ActionCatalog.byCode(String): ActionDef`（ActionCatalog.java L129）

**Produces**（新增）：

```java
// org.ruoyi.ipd.vo.GuideSequenceView.java（一个文件四形态：3 record + 复用 C3 DegradedStep）
public record GuideSequenceView(String subStageCode, String subStageName, String stageCode,
    String introText, List<GuideStepView> steps, AdvanceGateView advanceGate) {}
public record GuideStepView(String actionCode, String actionName, Integer sortOrder,
    String bindLevel, String aiMode, List<String> skillNames, List<String> commandChain,
    List<CommandDegrader.DegradedStep> degradedSteps,
    String guidePrompt, String stepState, Boolean blocking) {}
public record AdvanceGateView(String nextSubStageCode, Boolean advanceAllowed,
    List<String> pendingBlockingCodes) {}
// stepState 词表（锁定）：PENDING | DONE | NA | HISTORICAL_MISSING | NOT_INSTANTIATED

// org.ruoyi.ipd.service.SubStageGuideOrchestrator
public GuideSequenceView buildSequence(Long projectId, String subStageCode)
static String introText(SubStageView guide, List<GuideStepView> steps, AdvanceGateView gate)
private String nextSubStageCode(IpdSubStage current, List<IpdSubStage> all)   // 同 stage sort+1；越界→下一 stage 首个；KPI-S1→null
static String stepStateOf(StageAction row)  // null→NOT_INSTANTIATED；historyMark=HISTORICAL_MISSING 优先；status∈SETTLED_STATUSES→status

// Modify SubStageController.guideEvents（方法签名不变，仅方法体增强）：
//   ApiV1Response<List<Map<String,Object>>> guideEvents(String subStageCode, Long projectId)
//   progressPatch op.value 追加：guideSteps=编排器 steps、advanceGate=编排器 advanceGate
//   intro = SubStageGuideOrchestrator.introText(...)（替换 A4.2 硬编码 intro）
```

### C2.3 实现代码

```java
package org.ruoyi.ipd.vo;

import org.ruoyi.ipd.copilotkit.CommandDegrader;

import java.util.List;

/** 小阶段引导序列（GET /guide-events 的 progressPatch.value.guideSteps/advanceGate 载荷）。 */
public record GuideSequenceView(String subStageCode, String subStageName, String stageCode,
                                String introText, List<GuideStepView> steps,
                                AdvanceGateView advanceGate) {
}

/** 单动作引导步骤（步骤序 = ipd_action_skill_map.sortOrder 升序）。 */
public record GuideStepView(String actionCode, String actionName, Integer sortOrder,
                            String bindLevel, String aiMode, List<String> skillNames,
                            List<String> commandChain,
                            List<CommandDegrader.DegradedStep> degradedSteps,
                            String guidePrompt, String stepState, Boolean blocking) {
}

/** 推进门禁视图（§2.8 不变量④）：advanceAllowed=false 时 pendingBlockingCodes 点名待完成阻断动作。 */
public record AdvanceGateView(String nextSubStageCode, Boolean advanceAllowed,
                              List<String> pendingBlockingCodes) {
}
```

```java
package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.copilotkit.CommandDegrader;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.seed.GuideScriptCatalog;
import org.ruoyi.ipd.seed.GuideScriptCatalog.GuideScript;
import org.ruoyi.ipd.vo.AdvanceGateView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.GuideStepView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小阶段引导编排器（Track C2）：小阶段 → 动作序列 → 话术 → 降级命令步骤 → 门禁视图。
 *
 * <p>排序口径：ipd_action_skill_map.sortOrder 升序（A2.5 listBySubStage 已排序，编排器不重排）；
 * 门禁口径：复用 {@link SubStageGateService#pendingBlockingActions}（不复制校验逻辑）；
 * 进度口径：stage_actions 单行读（status + history_mark），本服务零写。
 */
@Service
@RequiredArgsConstructor
public class SubStageGuideOrchestrator {

    /** stepState 词表（GuideStepView.stepState 锁定值域）。 */
    static final String STATE_PENDING = "PENDING";
    static final String STATE_NOT_INSTANTIATED = "NOT_INSTANTIATED";

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final SubStageGateService gateService;
    private final StageActionMapper stageActionMapper;

    /** 生成完整引导序列；projectId 为空则进度全 PENDING、门禁按「无项目不拦」放行（查询侧降级，不伪造状态）。 */
    public GuideSequenceView buildSequence(Long projectId, String subStageCode) {
        IpdSubStage current = subStageService.getByCode(subStageCode);
        List<IpdSubStage> all = subStageService.listAll();
        String nextCode = nextSubStageCode(current, all);
        List<IpdActionSkillMap> maps = skillMapService.listBySubStage(subStageCode);
        Map<String, StageAction> progress = loadProgress(projectId, maps);
        List<GuideStepView> steps = new ArrayList<>();
        for (IpdActionSkillMap row : maps) {
            steps.add(buildStep(row, progress.get(row.getActionCode())));
        }
        AdvanceGateView gate = buildGate(projectId, nextCode);
        return new GuideSequenceView(current.getCode(), current.getName(), current.getStageCode(),
            introText(steps, gate, current), steps, gate);
    }
```

（续 `SubStageGuideOrchestrator.java`）

```java
    /** 单动作步骤：目录派生（aiMode/blocking/name）+ C1 话术 + C3 降级 + 进度态。 */
    private GuideStepView buildStep(IpdActionSkillMap row, StageAction progressRow) {
        GuideScript script = GuideScriptCatalog.scriptOf(row.getActionCode());
        ActionDef def = ActionCatalog.byCode(row.getActionCode());   // 脏码 fail-loud（不变量⑤）
        return new GuideStepView(
            def.code(), def.name(), row.getSortOrder(),
            script.bindLevel().name(), def.execMode(),
            script.skillNames(), script.commandChain(),
            CommandDegrader.degradeChain(script.commandChain()),
            script.guidePrompt(), stepStateOf(progressRow),
            def.blocking());
    }

    /** 项目进度装载：一次查询该小阶段全部动作的 stage_actions 行（零写）。 */
    private Map<String, StageAction> loadProgress(Long projectId, List<IpdActionSkillMap> maps) {
        if (projectId == null || maps.isEmpty()) {
            return Map.of();
        }
        List<String> codes = maps.stream().map(IpdActionSkillMap::getActionCode).toList();
        return stageActionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                    .eq(StageAction::getProjectId, projectId)
                    .in(StageAction::getActionCode, codes))
            .stream().collect(Collectors.toMap(StageAction::getActionCode, r -> r, (a, b) -> a));
    }

    /** 门禁视图：复用 SubStageGateService.pendingBlockingActions（校验逻辑唯一落点）。 */
    private AdvanceGateView buildGate(Long projectId, String nextSubStageCode) {
        if (nextSubStageCode == null) {           // KPI-S1 常驻：无推进目标
            return new AdvanceGateView(null, true, List.of());
        }
        if (projectId == null) {
            return new AdvanceGateView(nextSubStageCode, true, List.of());
        }
        List<String> pending = gateService.pendingBlockingActions(projectId, nextSubStageCode);
        return new AdvanceGateView(nextSubStageCode, pending.isEmpty(), List.copyOf(pending));
    }

    /** 下一小阶段：同 stage 内 sort+1；本 stage 末位 → 下一 stage 最小 sort；KPI-S1（sort=99 常驻）→ null。 */
    private String nextSubStageCode(IpdSubStage current, List<IpdSubStage> all) {
        if ("1".equals(current.getIsResident())) {
            return null;
        }
        String sameStageNext = all.stream()
            .filter(s -> s.getStageCode().equals(current.getStageCode()))
            .filter(s -> s.getSortOrder() > current.getSortOrder())
            .min(java.util.Comparator.comparingInt(IpdSubStage::getSortOrder))
            .map(IpdSubStage::getCode).orElse(null);
        if (sameStageNext != null) {
            return sameStageNext;
        }
        return all.stream()
            .filter(s -> !s.getStageCode().equals(current.getStageCode()))
            .filter(s -> IpdSubStageService.stageRank(s.getStageCode())
                > IpdSubStageService.stageRank(current.getStageCode()))
            .min(java.util.Comparator.comparingInt(s -> IpdSubStageService.stageRank(s.getStageCode()) * 100
                + s.getSortOrder()))
            .map(IpdSubStage::getCode).orElse(null);
    }

    /** 步骤状态（词表锁定）：缺行→NOT_INSTANTIATED；history_mark=HISTORICAL_MISSING 优先；否则 status。 */
    static String stepStateOf(StageAction row) {
        if (row == null) {
            return STATE_NOT_INSTANTIATED;
        }
        if (SubStageGateService.HISTORY_MISSING.equals(row.getHistoryMark())) {
            return SubStageGateService.HISTORY_MISSING;
        }
        return row.getStatus() == null ? STATE_PENDING : row.getStatus();
    }

    /** 开场话术：动作总数 + 阻断提示 + 门禁提示（A4.2 硬编码 intro 的替换物）。 */
    static String introText(List<GuideStepView> steps, AdvanceGateView gate, IpdSubStage current) {
        long blocking = steps.stream().filter(GuideStepView::blocking).count();
        StringBuilder sb = new StringBuilder("小阶段「").append(current.getName()).append("」共 ")
            .append(steps.size()).append(" 个动作，其中阻断动作 ").append(blocking)
            .append(" 个；请按序完成，每个动作的引导话术见 guideSteps。");
        if (gate.advanceAllowed() != null && !gate.advanceAllowed()) {
            sb.append(" 推进已阻断：待完成阻断动作 ")
                .append(String.join("、", gate.pendingBlockingCodes())).append("。");
        }
        return sb.toString();
    }
}
```

### C2.4 `SubStageController.guideEvents` Modify 补丁（方法签名不变，仅方法体）

```java
    // ① import 追加：org.ruoyi.ipd.service.SubStageGuideOrchestrator、org.ruoyi.ipd.vo.GuideSequenceView
    // ② 构造注入追加：private final SubStageGuideOrchestrator guideOrchestrator;
    // ③ guideEvents 方法体内，替换 intro 构造与 progressPatch 组装（A4.2 原代码 → 本段）：
        GuideSequenceView seq = guideOrchestrator.buildSequence(projectId, guide.code());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subStageCode", guide.code());
        if (projectId != null) {
            value.put("projectId", String.valueOf(projectId));
        }
        value.put("guideSteps", seq.steps());        // C2 新增键①（步骤序列）
        value.put("advanceGate", seq.advanceGate()); // C2 新增键②（门禁视图）
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/subStageGuide");
        op.put("value", value);
        List<Object> progressPatch = new ArrayList<>();
        progressPatch.add(op);
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            seq.introText(), guide, sourceRefs, progressPatch);
        return ApiV1Response.ok(events);
```
> 契约影响：`GET /guide-events` 仍是 A4.2 既有的唯一读端点（**零新端点**）；STATE_DELTA 的 `op.path` 仍为 `/subStageGuide`，仅 `value` 增两键（向后兼容——Track B 旧消费方忽略未知键即可，与 §4 单轨契约「工具参数下发」机制一致）。

### C2.5 `SubStageGuideOrchestratorTest.java`（Create，`@Tag("dev")`，Mockito 静态风格对齐 A2.7）

```java
package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.vo.AdvanceGateView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.GuideStepView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageGuideOrchestratorTest {

    @Mock private IpdSubStageService subStageService;
    @Mock private IpdActionSkillMapService skillMapService;
    @Mock private SubStageGateService gateService;
    @Mock private StageActionMapper stageActionMapper;
    @InjectMocks private SubStageGuideOrchestrator orchestrator;

    private static IpdSubStage subStage(String code, String name, String stage, int sort, String resident) {
        IpdSubStage s = new IpdSubStage();
        s.setCode(code); s.setName(name); s.setStageCode(stage); s.setSortOrder(sort); s.setIsResident(resident);
        return s;
    }

    private static IpdActionSkillMap map(long id, String action, String subStage, int sort) {
        return IpdActionSkillMap.builder().id(id).actionCode(action).subStageCode(subStage).sortOrder(sort).build();
    }

    @Test
    @DisplayName("buildSequence：步骤按 sortOrder 升序、话术/降级齐全、stepState 四态映射")
    void buildsOrderedStepsWithPromptAndState() {
        when(subStageService.getByCode("CONCEPT-S1"))
            .thenReturn(subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"));
        when(subStageService.listAll()).thenReturn(List.of(
            subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"),
            subStage("CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0"),
            subStage("KPI-S1", "共担KPI归集", "KPI", 99, "1")));
        when(skillMapService.listBySubStage("CONCEPT-S1"))
            .thenReturn(List.of(map(1L, "C01", "CONCEPT-S1", 1), map(2L, "C05", "CONCEPT-S1", 2)));
        StageAction done = new StageAction();
        done.setActionCode("C01"); done.setStatus("DONE");
        when(stageActionMapper.selectList(any())).thenReturn(List.of(done));
        when(gateService.pendingBlockingActions(anyLong(), anyString())).thenReturn(List.of("C12"));

        GuideSequenceView seq = orchestrator.buildSequence(7L, "CONCEPT-S1");

        assertThat(seq.steps()).extracting(GuideStepView::actionCode).containsExactly("C01", "C05");
        assertThat(seq.steps().get(0).stepState()).isEqualTo("DONE");
        assertThat(seq.steps().get(1).stepState()).isEqualTo("NOT_INSTANTIATED"); // 缺行
        assertThat(seq.steps().get(0).guidePrompt()).isNotBlank();
        assertThat(seq.steps().get(0).degradedSteps()).isNotEmpty();              // C3 降级
        assertThat(seq.steps().get(1).skillNames()).isEmpty();                    // NONE 动作
        assertThat(seq.advanceGate().nextSubStageCode()).isEqualTo("CONCEPT-S2");
        assertThat(seq.advanceGate().advanceAllowed()).isFalse();                 // C12 未完成
        assertThat(seq.advanceGate().pendingBlockingCodes()).containsExactly("C12");
        assertThat(seq.introText()).contains("2 个动作").contains("推进已阻断");
    }

    @Test
    @DisplayName("HISTORICAL_MISSING 优先于 status；KPI-S1 无推进目标")
    void historyMissingAndResidentSemantics() {
        StageAction hist = new StageAction();
        hist.setActionCode("C01"); hist.setStatus("DONE"); hist.setHistoryMark("HISTORICAL_MISSING");
        assertThat(SubStageGuideOrchestrator.stepStateOf(hist)).isEqualTo("HISTORICAL_MISSING");
        assertThat(SubStageGuideOrchestrator.stepStateOf(null)).isEqualTo("NOT_INSTANTIATED");
    }
}
```

> `SubStageControllerTest` 追加用例（Modify Track A 文件）：`guideEventsCarriesGuideStepsAndGate`——断言 `guideEvents("CONCEPT-S1", 7L)` 返回事件数组中 STATE_DELTA 帧 `op.value` 含 `guideSteps`（非空、首步 `actionCode="C01"`）与 `advanceGate`（键齐全），且事件序与 A4.3 `guideEventsBuildsAgUiSequence` 同契约（RUN_STARTED→TEXT_MESSAGE_*→TOOL_CALL_*→STATE_DELTA→RUN_FINISHED）。

### C2.6 勾选步骤（TDD 粒度）

1. [ ] **写失败测试**：创建 `SubStageGuideOrchestratorTest.java`（C2.5 全文）+ 在 `SubStageControllerTest` 追加 `guideEventsCarriesGuideStepsAndGate`（断言 `op.value` 含 `guideSteps`/`advanceGate`）→ 编译失败（红）
2. [ ] **跑红**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest=SubStageGuideOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false` → 期望编译错误（类不存在）
3. [ ] **最小实现①**：创建 `GuideSequenceView.java`（C2.3 VO 段）与 `SubStageGuideOrchestrator.java`（C2.3 两段全文）
4. [ ] **最小实现②**：按 C2.4 补丁 Modify `SubStageController.guideEvents`（3 处：import、注入、方法体替换段）
5. [ ] **跑绿**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest='SubStageGuideOrchestratorTest,SubStageControllerTest' -Dsurefire.failIfNoSpecifiedTests=false` → 全绿（含 Track A 的 A4.3 用例零回归）
6. [ ] **契约锚**：人工核对 STATE_DELTA 序列与 A4.1 `translateGuide` 事件序一致（RUN_STARTED→TEXT_MESSAGE_*→TOOL_CALL_*→STATE_DELTA→RUN_FINISHED），`op.path` 仍为 `/subStageGuide`
7. [ ] **Commit（待批）**：暂存 2 Create + 2 Modify + diff 摘要。commit 信息：`feat(ipd): C2 小阶段引导编排器（guideSteps/advanceGate 下发，复用 SubStageGateService 门禁）`

**依赖顺序**：C1（GuideScriptCatalog）+ C3（CommandDegrader，见下节）编译前置 + Track A（A2/A3/A4/A5 类在位）。C2 完成后 C4b 方可接线。
**验证命令汇总**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest='SubStageGuideOrchestratorTest,SubStageControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`

---

## C3. command 降级层（CommandDegrader）

**问题**（§3.4.2-6，S3 L104-112）：42 条 slash command 是 Claude/Cowork 特有语义，CopilotKit/Java 运行时不可执行 → 必须降级为「skill 名 + 步骤话术」的自然语言引导。
**方案**：纯静态函数 `CommandDegrader`（`org.ruoyi.ipd.copilotkit` 包，与 AG-UI 桥同包邻近），无状态、无 IO、可单测；未知命令 fail-loud（10001）。

### C3.1 Files

| 路径（相对 `ruoyi-modules/ruoyi-ipd/`） | 动作 | 说明 |
|---|---|---|
| `src/main/java/org/ruoyi/ipd/copilotkit/CommandDegrader.java` | **Create** | 42 命令降级规格表 + `degrade`/`degradeChain` 纯函数 |
| `src/test/java/org/ruoyi/ipd/copilotkit/CommandDegraderTest.java` | **Create** | `@Tag("dev")`：42 命令全量降级 + 5 参数变体 + 未知命令 fail-loud + 词表锚 |

### C3.2 Interfaces

**Consumes**：§3.1 的 42 command 真名与参数变体（`/brainstorm ideas|experiments × existing|new`、`/interview prep|summarize`、`/business-model lean|full|startup|value-prop|all`、`/sprint plan|retro|release`、`/write-stories user|job|wwa`）；§3.2 command 链（GuideScript.commandChain）。
**Produces**：

```java
public record DegradedStep(String command, List<String> skillNames, String stepPrompt) {}
public static DegradedStep degrade(String command)            // 单命令（含参数串，如 "/interview prep"）
public static List<DegradedStep> degradeChain(List<String> commandChain)  // 链式（保留顺序；空链→空列表）
// 未命中命令名抛 IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, ...)
```

### C3.3 `CommandDegrader.java`（Create，完整代码）

```java
package org.ruoyi.ipd.copilotkit;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * slash command 降级层（§3.4.2-6）：Claude 专属 slash 语义在 CopilotKit/Java 运行时
 * 降级为「skill 名 + 步骤话术」自然语言引导。纯静态无状态（可单测、无 IO）。
 *
 * <p>规格源：§3.1 的 42 command 真名 + §3.2 command 链用法；参数变体按 §3.1 标注解析。
 * 未命中命令 fail-loud（10001），防止出现无方法论引导的静默跳步。
 */
public final class CommandDegrader {

    /** 一条降级结果：原命令（含参数）→ 技能名列表 + 自然语言步骤话术。 */
    public record DegradedStep(String command, List<String> skillNames, String stepPrompt) {
    }

    private record Spec(List<String> skills, String stepPrompt) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    /** 参数变体覆盖：命令名 →（参数 key → 技能列表）；参数 key 为命令后首个词。 */
    private static final Map<String, Map<String, List<String>>> ARG_SKILLS = new LinkedHashMap<>();

    private CommandDegrader() {
    }

    private static void spec(String cmd, String skillsCsv, String prompt) {
        SPECS.put(cmd, new Spec(List.of(skillsCsv.split(",")), prompt));
    }

    static {
        // pm-product-discovery（5）
        spec("/discover", "brainstorm-ideas-new,identify-assumptions-new,prioritize-assumptions,opportunity-solution-tree",
            "按发现四步走：发散点子→映射假设→排优先级→设计实验验证。");
        spec("/brainstorm", "brainstorm-ideas-new", "发散想法（ideas/experiments × existing/new 变体见 ARG_SKILLS）。");
        spec("/triage-requests", "analyze-feature-requests", "把需求/反馈归类去重并评估价值，输出优先级清单。");
        spec("/interview", "interview-script", "准备访谈提纲（prep 变体）或归纳访谈（summarize 变体）。");
        spec("/setup-metrics", "metrics-dashboard", "定义指标口径、看板与告警阈值。");
        // pm-product-strategy（5）
        spec("/strategy", "product-strategy", "按 9 部分战略画布逐段填写产品战略。");
        spec("/business-model", "business-model", "梳理商业模式（lean/full/startup/value-prop/all 变体）。");
        spec("/value-proposition", "value-proposition", "按 JTBD 六段式写价值主张。");
        spec("/market-scan", "swot-analysis,pestle-analysis,porters-five-forces,ansoff-matrix",
            "四工具合成市场扫描：SWOT+PESTLE+五力+Ansoff。");
        spec("/pricing", "pricing-strategy", "用定价模型结合竞品价与支付意愿测算定价。");
        // pm-execution（11）
        spec("/write-prd", "create-prd", "按 8 部分 PRD 结构起草产品需求规格。");
        spec("/plan-okrs", "brainstorm-okrs", "设定 O/KR 并对齐商业目标。");
        spec("/transform-roadmap", "outcome-roadmap", "把功能清单转成 outcome 路线图。");
        spec("/sprint", "sprint-plan", "规划 Sprint（plan/retro/release 变体）。");
        spec("/pre-mortem", "pre-mortem", "用 Tigers/Paper Tigers/Elephants 预演失败模式。");
        spec("/red-team-prd", "strategy-red-team", "红队攻击文档最脆弱前提并修订。");
        spec("/meeting-notes", "summarize-meeting", "输出纪要：决议+行动项（负责人+期限）。");
        spec("/stakeholder-map", "stakeholder-map", "按权力×关注度画干系人地图并定沟通策略。");
        spec("/write-stories", "user-stories", "写故事（user/job/wwa 变体）。");
        spec("/test-scenarios", "test-scenarios", "生成测试场景与用例骨架。");
        spec("/generate-data", "dummy-dataset", "生成演示/测试用假数据集。");
    }
```

（续 `CommandDegrader.java`）

```java
    static {
        // pm-market-research（3）
        spec("/research-users", "user-personas,market-segments,user-segmentation,customer-journey-map",
            "画像→分群→旅程图三步用户研究。");
        spec("/competitive-analysis", "competitor-analysis", "竞品多维对比分析。");
        spec("/analyze-feedback", "sentiment-analysis", "反馈情绪与主题分析。");
        // pm-data-analytics（3）
        spec("/write-query", "sql-queries", "写取数 SQL 并核对口径。");
        spec("/analyze-cohorts", "cohort-analysis", "分批 cohort 对比分析。");
        spec("/analyze-test", "ab-test-analysis", "A/B 实验结果分析。");
        // pm-go-to-market（3）
        spec("/plan-launch", "gtm-strategy,beachhead-segment,ideal-customer-profile",
            "滩头市场→ICP→上市节奏全链规划。");
        spec("/growth-strategy", "growth-loops", "设计增长循环与增长策略。");
        spec("/battlecard", "competitive-battlecard", "产出竞品作战卡（异议处理/赢单打法）。");
        // pm-marketing-growth（2）
        spec("/market-product", "marketing-ideas,positioning-ideas,value-prop-statements,product-name",
            "营销想法/定位/价值主张/命名四步。");
        spec("/north-star", "north-star-metric", "确定北极星指标。");
        // pm-toolkit（5）
        spec("/review-resume", "review-resume", "简历评审。");
        spec("/tailor-resume", "review-resume", "按岗位 JD 调整简历（复用简历评审方法，推断）。");
        spec("/draft-nda", "draft-nda", "起草 NDA。");
        spec("/privacy-policy", "privacy-policy", "起草 GDPR/CCPA 合规隐私政策。");
        spec("/proofread", "grammar-check", "逐份校对语法与文案。");
        // pm-ai-shipping（5）
        spec("/ship-check", "shipping-artifacts", "发布前按可审查文档集过审查包。");
        spec("/document-app", "shipping-artifacts", "逆向整理系统文档骨架。");
        spec("/derive-tests", "intended-vs-implemented,test-scenarios", "文档意图→测试覆盖对照推导用例。");
        spec("/security-audit-static", "code-review", "静态安全审查（复用 code-review 找跨边界缺陷，推断）。");
        spec("/performance-audit-static", "code-review", "静态性能审查（复用 code-review，推断）。");

        // 参数变体覆盖（§3.1 标注的 5 条参数化命令）
        ARG_SKILLS.put("/interview", Map.of(
            "prep", List.of("interview-script"),
            "summarize", List.of("summarize-interview")));
        ARG_SKILLS.put("/business-model", Map.of(
            "lean", List.of("lean-canvas"),
            "full", List.of("business-model"),
            "startup", List.of("startup-canvas"),
            "value-prop", List.of("value-proposition"),
            "all", List.of("lean-canvas", "business-model", "startup-canvas", "value-proposition")));
        ARG_SKILLS.put("/sprint", Map.of(
            "plan", List.of("sprint-plan"),
            "retro", List.of("retro"),
            "release", List.of("release-notes")));
        ARG_SKILLS.put("/write-stories", Map.of(
            "user", List.of("user-stories"),
            "job", List.of("job-stories"),
            "wwa", List.of("wwas")));
    }

    /** /brainstorm 双参数变体（ideas|experiments × existing|new）单独解析。 */
    private static List<String> brainstormSkills(String[] words) {
        String kind = words.length > 1 ? words[1] : "ideas";
        String basis = words.length > 2 ? words[2] : "new";
        return List.of(("experiments".equals(kind) ? "brainstorm-experiments-" : "brainstorm-ideas-") + basis);
    }

    /** 单命令降级（command 可带参数串，如 "/interview prep"）。未知命令抛 10001 fail-loud。 */
    public static DegradedStep degrade(String command) {
        String[] words = command.trim().split("\\s+");
        String name = words[0];
        Spec spec = SPECS.get(name);
        if (spec == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未知 command: " + command);
        }
        List<String> skills = spec.skills();
        if ("/brainstorm".equals(name)) {
            skills = brainstormSkills(words);
        } else if (words.length > 1) {
            Map<String, List<String>> variants = ARG_SKILLS.get(name);
            if (variants != null && variants.containsKey(words[1])) {
                skills = variants.get(words[1]);
            }
        }
        return new DegradedStep(command, List.copyOf(skills), spec.stepPrompt());
    }

    /** 链式降级（保持顺序；空链→空列表）。 */
    public static List<DegradedStep> degradeChain(List<String> commandChain) {
        if (commandChain == null || commandChain.isEmpty()) {
            return List.of();
        }
        List<DegradedStep> out = new ArrayList<>(commandChain.size());
        for (String command : commandChain) {
            out.add(degrade(command));
        }
        return List.copyOf(out);
    }
}
```

> 计数核对：SPECS 5+5+11+3+3+3+2+5+5 = **42**，与 §3.1.1 合计一致；ARG_SKILLS 4 条 + `/brainstorm` 专用解析 = **5** 条参数化命令（§3.1 标注全集）。

### C3.4 `CommandDegraderTest.java`（Create，`@Tag("dev")`）

```java
package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.copilotkit.CommandDegrader.DegradedStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class CommandDegraderTest {

    /** §3.1 的 42 command 全集（SPECS 键面锚）。 */
    private static final List<String> ALL_COMMANDS = List.of(
        "/discover", "/brainstorm", "/triage-requests", "/interview", "/setup-metrics",
        "/strategy", "/business-model", "/value-proposition", "/market-scan", "/pricing",
        "/write-prd", "/plan-okrs", "/transform-roadmap", "/sprint", "/pre-mortem",
        "/red-team-prd", "/meeting-notes", "/stakeholder-map", "/write-stories",
        "/test-scenarios", "/generate-data", "/research-users", "/competitive-analysis",
        "/analyze-feedback", "/write-query", "/analyze-cohorts", "/analyze-test",
        "/plan-launch", "/growth-strategy", "/battlecard", "/market-product", "/north-star",
        "/review-resume", "/tailor-resume", "/draft-nda", "/privacy-policy", "/proofread",
        "/ship-check", "/document-app", "/derive-tests",
        "/security-audit-static", "/performance-audit-static");

    @Test
    @DisplayName("42 命令全量可降级：skill 非空、话术非空")
    void allFortyTwoCommandsDegrade() {
        for (String command : ALL_COMMANDS) {
            DegradedStep step = CommandDegrader.degrade(command);
            assertThat(step.skillNames()).as(command).isNotEmpty();
            assertThat(step.stepPrompt()).as(command).isNotBlank();
        }
    }

    @Test
    @DisplayName("参数变体映射：interview/business-model/sprint/write-stories/brainstorm")
    void argumentVariantsMapToSpecificSkills() {
        assertThat(CommandDegrader.degrade("/interview prep").skillNames())
            .containsExactly("interview-script");
        assertThat(CommandDegrader.degrade("/interview summarize").skillNames())
            .containsExactly("summarize-interview");
        assertThat(CommandDegrader.degrade("/business-model lean").skillNames())
            .containsExactly("lean-canvas");
        assertThat(CommandDegrader.degrade("/business-model all").skillNames()).hasSize(4);
        assertThat(CommandDegrader.degrade("/sprint release").skillNames())
            .containsExactly("release-notes");
        assertThat(CommandDegrader.degrade("/write-stories wwa").skillNames())
            .containsExactly("wwas");
        assertThat(CommandDegrader.degrade("/brainstorm ideas existing").skillNames())
            .containsExactly("brainstorm-ideas-existing");
        assertThat(CommandDegrader.degrade("/brainstorm experiments new").skillNames())
            .containsExactly("brainstorm-experiments-new");
    }

    @Test
    @DisplayName("degradeChain：顺序保持、空链→空列表、未知命令 fail-loud 10001")
    void chainPreservesOrderAndFailsLoud() {
        List<DegradedStep> chain = CommandDegrader.degradeChain(
            List.of("/write-prd", "/red-team-prd"));
        assertThat(chain).extracting(DegradedStep::command)
            .containsExactly("/write-prd", "/red-team-prd");
        assertThat(CommandDegrader.degradeChain(List.of())).isEmpty();
        assertThatThrownBy(() -> CommandDegrader.degrade("/no-such-command"))
            .hasMessageContaining("未知 command");
    }
}
```

### C3.5 勾选步骤（TDD 粒度）

1. [ ] **写失败测试**：创建 `CommandDegraderTest.java`（C3.4 全文）→ 编译失败（红）
2. [ ] **跑红**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest=CommandDegraderTest -Dsurefire.failIfNoSpecifiedTests=false`
3. [ ] **最小实现**：创建 `CommandDegrader.java`（C3.3 两段全文）
4. [ ] **跑绿**：同 2 命令 → 3 用例全绿
5. [ ] **Commit（待批）**：暂存 2 文件 + diff 摘要。commit 信息：`feat(ipd): C3 command 降级层（42 命令→skill+步骤话术，参数变体感知，fail-loud）`

**依赖顺序**：C3 无前置（纯函数），与 C1 并行；是 C2 的编译前置（`DegradedStep` 被 `GuideStepView` 复用）。
**验证命令汇总**：`mvn -pl ruoyi-modules/ruoyi-ipd test -Dtest=CommandDegraderTest -Dsurefire.failIfNoSpecifiedTests=false`

---

## C4. 前端接线（话术/建议进 CopilotKit 流）

### C4.0 三机制评估（结论：①工具参数 + ②pageContext；③不用 useSuggestions）

| 机制 | 事实依据 | 结论 |
|---|---|---|
| ① **工具参数/STATE_DELTA 下发**（guideSteps 经 `op.value` 到前端） | AgUiFrameTranslator 既有帧通道；C2 已把 guideSteps/advanceGate 装进 `/subStageGuide` value；前端从帧数据派生 VM | **主通道** |
| ② **useAgentContext** `description='pageContext'` | `AgUiCopilotRun.lookup`（L218-226）context 按 `description=键名` 消费，仅认 `projectId`/`pageContext` 两键；MAX_PAGE_CONTEXT=4000（L36-37） | **辅通道**：把「当前小阶段+下一步动作提示」摘要注入 agent 上下文（超 4000 截断） |
| ③ useSuggestions/useConfigureSuggestions | §4 纠正表 API 真实存在；但 `useSuggestions\|useAgentContext\|useFrontendTool` 在 `apps/web-antd/src` grep **零命中**（实测）——官方消费端 CopilotKitChat 未挂载 | **不用**：无消费端=死配置，且自绘建议条会构成「平行建议体系」风险（单轨红线 1/2） |

**chips 形态**：纯函数 `buildGuideSuggestions(steps)` 从帧数据派生字符串数组，渲染在**既有 input-row 上方**（`ai-assistant.vue` L725-749 的输入区，不新建聊天 UI、不新建卡片），点击=填入既有 `inputText` 后由用户/既有 `send()` 发送（不绕过既有 SSE 通道）。

### C4.1 Files

| 路径（相对 `apps/web-antd/src/`） | 动作 | 说明 |
|---|---|---|
| `api/ipd/guide-script.ts` | **Create** | `fetchGuideEvents`（`ipdGet` GET，零新写端点）+ AG-UI 事件类型 |
| `views/ipd/_shared/ai-guide/guide-script.ts` | **Create** | 纯函数：`parseGuideSteps`/`extractGuideText`/`buildGuideSuggestions`/`buildGuideContextValue` |
| `views/ipd/_shared/ai-guide/guide-script.test.ts` | **Create** | vitest（白名单 `views/ipd/**/*.test.ts` 自动纳入，**不改** `vitest.ipd.config.mts`） |
| `views/ipd/_shared/ai-guide/guide-suggestion-bar.vue` | **Create**（C4b） | chips 条（仅 `--ipd-*` token） |
| `views/ipd/_shared/ai-assistant.vue` | **Modify**（C4b） | 仅加：`ipd:guide-sub-stage` 监听 + chips 渲染 + pick 填 `inputText`（消息流/SSE/卡片零改动） |

### C4.2 Interfaces（C4a）

**Consumes**：`ipdGet<T>(path, query?)`（http.ts L22）；Track A 端点 `GET /api/v1/ipd/stage/sub-stages/guide-events?subStageCode=&projectId=`（A4.2，返回 `ApiV1Response<List<Map<String,Object>>>`，requestIpd 已剥 code=0 包络）；C2 的 `guideSteps`/`advanceGate` 键（C2.4）。
**Produces**：

```ts
// api/ipd/guide-script.ts
export interface GuideEvent { type: string; [key: string]: unknown }
export async function fetchGuideEvents(subStageCode: string, projectId?: string): Promise<GuideEvent[]>

// views/ipd/_shared/ai-guide/guide-script.ts（纯函数，零依赖可单测）
export interface GuideStepVm {
  actionCode: string; actionName: string; sortOrder: number;
  bindLevel: string; aiMode: string; skillNames: string[]; commandChain: string[];
  degradedSteps: { command: string; skillNames: string[]; stepPrompt: string }[];
  guidePrompt: string; stepState: string; blocking: boolean;
}
export interface AdvanceGateVm {
  nextSubStageCode: string | null; advanceAllowed: boolean; pendingBlockingCodes: string[];
}
export const GUIDE_CONTEXT_KEY = 'pageContext';   // AgUiCopilotRun.lookup 仅认键之一
export function parseGuideSteps(events: GuideEvent[]): GuideStepVm[]
export function parseAdvanceGate(events: GuideEvent[]): AdvanceGateVm | null
export function extractGuideText(events: GuideEvent[]): string
export function buildGuideSuggestions(steps: GuideStepVm[], limit?: number): string[]
export function buildGuideContextValue(subStageCode: string, step: GuideStepVm | null): string  // JSON，>4000 截断
```

### C4.3 实现代码（C4a）

```ts
// apps/web-antd/src/api/ipd/guide-script.ts（Create）
import { ipdGet } from './http';

/** AG-UI 事件帧（type + 任意键，防御性消费）。 */
export interface GuideEvent {
  type: string;
  [key: string]: unknown;
}

/**
 * 小阶段引导帧序列（Track A `GET /guide-events`，零新写端点）。
 * requestIpd 已校验 code=0 包络，此处拿到裸事件数组。
 */
export async function fetchGuideEvents(
  subStageCode: string,
  projectId?: string,
): Promise<GuideEvent[]> {
  const data = await ipdGet<unknown>('/stage/sub-stages/guide-events', {
    subStageCode,
    projectId,
  });
  return Array.isArray(data) ? (data as GuideEvent[]) : [];
}
```

```ts
// apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script.ts（Create，纯函数）
import type { GuideEvent } from '../../../api/ipd/guide-script';

export interface GuideStepVm {
  actionCode: string;
  actionName: string;
  sortOrder: number;
  bindLevel: string;
  aiMode: string;
  skillNames: string[];
  commandChain: string[];
  degradedSteps: { command: string; skillNames: string[]; stepPrompt: string }[];
  guidePrompt: string;
  stepState: string;
  blocking: boolean;
}

export interface AdvanceGateVm {
  nextSubStageCode: string | null;
  advanceAllowed: boolean;
  pendingBlockingCodes: string[];
}

/** AgUiCopilotRun.lookup 仅认的两键之一（projectId/pageContext，实证 L218-226）。 */
export const GUIDE_CONTEXT_KEY = 'pageContext';
/** 与 AgUiCopilotRun.MAX_PAGE_CONTEXT 同口径（L36-37），超限截断防后端丢弃。 */
export const MAX_CONTEXT_CHARS = 4000;

/** 运行期对象判别（与 ai-assistant.vue L178 同款形态，本地小判别防双轨 import）。 */
function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** STATE_DELTA 帧 → /subStageGuide op.value.guideSteps（非法帧跳过不崩）。 */
export function parseGuideSteps(events: GuideEvent[]): GuideStepVm[] {
  for (const event of events) {
    if (event.type !== 'STATE_DELTA') continue;
    const patch = event.delta;
    if (!Array.isArray(patch)) continue;
    for (const op of patch) {
      if (!isPlainRecord(op) || op.path !== '/subStageGuide') continue;
      const value = op.value;
      if (!isPlainRecord(value) || !Array.isArray(value.guideSteps)) continue;
      return value.guideSteps.filter(isPlainRecord).map((s, i) => ({
        actionCode: String(s.actionCode ?? ''),
        actionName: String(s.actionName ?? ''),
        sortOrder: Number(s.sortOrder ?? i),
        bindLevel: String(s.bindLevel ?? 'NONE'),
        aiMode: String(s.aiMode ?? ''),
        skillNames: Array.isArray(s.skillNames) ? s.skillNames.map(String) : [],
        commandChain: Array.isArray(s.commandChain) ? s.commandChain.map(String) : [],
        degradedSteps: Array.isArray(s.degradedSteps)
          ? s.degradedSteps.filter(isPlainRecord).map((d) => ({
              command: String(d.command ?? ''),
              skillNames: Array.isArray(d.skillNames) ? d.skillNames.map(String) : [],
              stepPrompt: String(d.stepPrompt ?? ''),
            }))
          : [],
        guidePrompt: String(s.guidePrompt ?? ''),
        stepState: String(s.stepState ?? 'PENDING'),
        blocking: s.blocking === true,
      }));
    }
  }
  return [];
}

/** STATE_DELTA → /subStageGuide op.value.advanceGate。 */
export function parseAdvanceGate(events: GuideEvent[]): AdvanceGateVm | null {
  for (const event of events) {
    if (event.type !== 'STATE_DELTA') continue;
    const patch = event.delta;
    if (!Array.isArray(patch)) continue;
    for (const op of patch) {
      if (!isPlainRecord(op) || op.path !== '/subStageGuide') continue;
      const value = op.value;
      if (!isPlainRecord(value) || !isPlainRecord(value.advanceGate)) continue;
      const gate = value.advanceGate;
      return {
        nextSubStageCode:
          typeof gate.nextSubStageCode === 'string' ? gate.nextSubStageCode : null,
        advanceAllowed: gate.advanceAllowed !== false,
        pendingBlockingCodes: Array.isArray(gate.pendingBlockingCodes)
          ? gate.pendingBlockingCodes.map(String)
          : [],
      };
    }
  }
  return null;
}

/** TEXT_MESSAGE_CONTENT 帧 delta 拼接（引导开场文本；帧序保持）。 */
export function extractGuideText(events: GuideEvent[]): string {
  return events
    .filter((e) => e.type === 'TEXT_MESSAGE_CONTENT')
    .map((e) => (typeof e.delta === 'string' ? e.delta : ''))
    .join('');
}
```

（续 `guide-script.ts`；`delta` 键名与 type 大写 wireName 经 AgUiEvents.java L100-106/AgUiEventType.java L12-32 实证）

```ts
/** chips：从步骤派生「序号+动作名+第一步引导」建议（默认 3 条，点击=填入输入框）。 */
export function buildGuideSuggestions(steps: GuideStepVm[], limit = 3): string[] {
  return steps.slice(0, limit).map((step, i) => {
    const head = `${i + 1}. ${step.actionName}`;
    const skill = step.skillNames.length > 0 ? `（技能 ${step.skillNames[0]}）` : '（结构化登记）';
    return `${head}${skill}：${step.guidePrompt}`;
  });
}

/** agent 上下文摘要（useAgentContext value 恒 JSON 字符串；>MAX_CONTEXT_CHARS 截断）。 */
export function buildGuideContextValue(
  subStageCode: string,
  step: GuideStepVm | null,
): string {
  const payload = {
    subStageCode,
    currentAction: step
      ? {
          actionCode: step.actionCode,
          actionName: step.actionName,
          guidePrompt: step.guidePrompt,
          skillNames: step.skillNames,
          degradedStepPrompts: step.degradedSteps.map((d) => d.stepPrompt),
        }
      : null,
  };
  const json = JSON.stringify(payload);
  return json.length > MAX_CONTEXT_CHARS ? json.slice(0, MAX_CONTEXT_CHARS) : json;
}
```

### C4.4 `guide-script.test.ts`（Create，vitest；白名单 `views/ipd/**/*.test.ts` 自动纳入）

```ts
import { describe, expect, it } from 'vitest';

import type { GuideEvent } from '../../../api/ipd/guide-script';
import {
  buildGuideContextValue,
  buildGuideSuggestions,
  extractGuideText,
  parseAdvanceGate,
  parseGuideSteps,
  MAX_CONTEXT_CHARS,
} from './guide-script';

const EVENTS: GuideEvent[] = [
  { type: 'RUN_STARTED', threadId: 't', runId: 'r' },
  { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm', delta: '小阶段「市场洞察」共 2 个动作' },
  {
    type: 'STATE_DELTA',
    delta: [
      {
        op: 'add',
        path: '/subStageGuide',
        value: {
          subStageCode: 'CONCEPT-S1',
          guideSteps: [
            {
              actionCode: 'C01', actionName: '市场机会与痛点调研', sortOrder: 1,
              bindLevel: 'BIND', aiMode: 'AI_GENERATE',
              skillNames: ['interview-script'], commandChain: ['/interview prep'],
              degradedSteps: [
                { command: '/interview prep', skillNames: ['interview-script'], stepPrompt: '准备访谈提纲' },
              ],
              guidePrompt: '先用 JTBD 提纲做痛点访谈。', stepState: 'PENDING', blocking: true,
            },
          ],
          advanceGate: {
            nextSubStageCode: 'CONCEPT-S2', advanceAllowed: false, pendingBlockingCodes: ['C12'],
          },
        },
      },
    ],
  },
];

describe('guide-script 纯函数', () => {
  it('parseGuideSteps：STATE_DELTA → 步骤 VM（字段全量映射）', () => {
    const steps = parseGuideSteps(EVENTS);
    expect(steps).toHaveLength(1);
    expect(steps[0]).toMatchObject({
      actionCode: 'C01', bindLevel: 'BIND', stepState: 'PENDING', blocking: true,
    });
    expect(steps[0].degradedSteps[0].stepPrompt).toBe('准备访谈提纲');
  });

  it('parseAdvanceGate：门禁视图映射；无 STATE_DELTA → null', () => {
    expect(parseAdvanceGate(EVENTS)).toEqual({
      nextSubStageCode: 'CONCEPT-S2', advanceAllowed: false, pendingBlockingCodes: ['C12'],
    });
    expect(parseAdvanceGate([{ type: 'RUN_FINISHED' }])).toBeNull();
  });

  it('extractGuideText：TEXT_MESSAGE_CONTENT delta 拼接', () => {
    expect(extractGuideText(EVENTS)).toContain('共 2 个动作');
  });

  it('buildGuideSuggestions：默认 3 条、含技能名、无技能动作标结构化登记', () => {
    const chips = buildGuideSuggestions(parseGuideSteps(EVENTS));
    expect(chips[0]).toContain('市场机会与痛点调研');
    expect(chips[0]).toContain('interview-script');
  });

  it('buildGuideContextValue：JSON 可解析且超 4000 截断', () => {
    const value = buildGuideContextValue('CONCEPT-S1', parseGuideSteps(EVENTS)[0]);
    expect(JSON.parse(value).currentAction.actionCode).toBe('C01');
    const long = buildGuideContextValue(
      'CONCEPT-S1',
      { ...parseGuideSteps(EVENTS)[0], guidePrompt: 'x'.repeat(5000) },
    );
    expect(long.length).toBeLessThanOrEqual(MAX_CONTEXT_CHARS);
  });

  it('非法帧（非对象/错键）不崩、返回空', () => {
    const bad = [{ type: 'STATE_DELTA', delta: [{ path: '/other', value: 1 }] }, { type: 'X' }];
    expect(parseGuideSteps(bad)).toEqual([]);
    expect(parseAdvanceGate(bad)).toBeNull();
  });
});
```

### C4.5 勾选步骤（C4a，TDD 粒度）

1. [ ] **写失败测试**：创建 `guide-script.test.ts`（C4.4 全文）→ `pnpm exec vitest run --config vitest.ipd.config.mts`（仓根）红（模块不存在）
2. [ ] **最小实现**：创建 `api/ipd/guide-script.ts` + `views/ipd/_shared/ai-guide/guide-script.ts`（C4.3 全文）
3. [ ] **跑绿**：同 1 命令 → 6 用例全绿；`pnpm run check:type` 过
4. [ ] **Commit（待批）**：暂存 3 文件 + diff 摘要。commit 信息：`feat(ipd-web): C4a 引导帧解析纯函数（guideSteps/advanceGate/chips/pageContext 摘要）`

**依赖顺序**：无前置（纯函数 + GET 封装）；C4b 的依赖。**验证**：`pnpm exec vitest run --config vitest.ipd.config.mts`（注意：vitest 无 `-t` 过滤时跑全白名单，三件套规约见 C0-7）。

---

### C4b. chips 条 + ai-assistant.vue 接线 + pageContext 注入

**Files（C4b）**：

| 路径（相对 `apps/web-antd/src/`） | 动作 | 说明 |
|---|---|---|
| `views/ipd/_shared/ai-guide/guide-suggestion-bar.vue` | **Create** | chips 展示条（emit `pick(text)`） |
| `views/ipd/_shared/ai-guide/guide-script-host.ts` | **Create** | `IpdGuideScriptHost`（`useAgentContext` 载体，`GUIDE_CONTEXT_KEY` 注入） |
| `views/ipd/_shared/ai-assistant.vue` | **Modify** | 三处：`guideSteps/advanceGate/suggestions` 状态 + `ipd:guide-sub-stage` 监听 + 模板挂 chips 与 host |

**Interfaces（C4b）**：
- Consumes：`useAgentContext`（`@copilotkit/vue/v2`，§4 纠正表：wire value 恒 JSON 字符串）；C4a 全部纯函数；既有 `messages`/`inputText`/`send()`（ai-assistant.vue L425-427、L631 defineExpose）；既有事件分发模式 `CustomEvent`（L514 `ipd:ai-fill-payload`、L575 `ipd:ai-card` 先例）
- Produces：`IpdGuideScriptHost`（props `{ value: string }`）；`GuideSuggestionBar`（props `{ suggestions: string[] }`、emit `(e: 'pick', text: string)`）；窗口事件契约 `ipd:guide-sub-stage`（detail：`{ subStageCode: string; projectId?: string }`，**由既有工作台页面派发**，ai-assistant 只订阅——派发端属于 Track B 页面侧，见 §7 边界）

**`guide-suggestion-bar.vue`（Create，颜色只用 `--ipd-*` token）**：

```vue
<script setup lang="ts">
/** 引导建议 chips（C4b）：点击=填入既有输入框，不发任何写请求（单轨红线 3/4）。 */
defineProps<{ suggestions: string[] }>();
const emit = defineEmits<{ (e: 'pick', text: string): void }>();
</script>

<template>
  <div v-if="suggestions.length" class="ipd-guide-chips" data-testid="ipd-guide-chips">
    <button
      v-for="(text, i) in suggestions"
      :key="i"
      class="ipd-guide-chip"
      :data-testid="`ipd-guide-chip-${i}`"
      type="button"
      @click="emit('pick', text)"
    >
      {{ text }}
    </button>
  </div>
</template>

<style scoped>
/* 色板仅用 _shared/ipd-theme.css 的 --ipd-* token（Global Constraints #21） */
.ipd-guide-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 0;
  border-top: 1px solid var(--ipd-line);
}
.ipd-guide-chip {
  border: 1px solid var(--ipd-line);
  border-radius: 4px;
  padding: 4px 10px;
  color: var(--ipd-blue);
  background: var(--ipd-blue-soft);
  cursor: pointer;
  font-size: 12px;
}
.ipd-guide-chip:hover {
  outline: 2px solid var(--ipd-focus-ring-color);
}
</style>
```

**`guide-script-host.ts`（Create，useAgentContext 载体）**：

```ts
// apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script-host.ts
import { defineComponent } from 'vue';

import { useAgentContext } from '@copilotkit/vue/v2';

import { GUIDE_CONTEXT_KEY } from './guide-script';

/**
 * 引导上下文注入宿主（C4b）：把当前小阶段摘要注入 agent context。
 * description=GUIDE_CONTEXT_KEY（'pageContext'）——AgUiCopilotRun.lookup 仅消费
 * projectId/pageContext 两键（L218-226），value 恒 JSON 字符串（§4 纠正表 L3995-4052）。
 * 挂载于 CopilotKitProvider 内（ai-assistant.vue 与 IpdAiCardRenderHost 并列）。
 */
export const IpdGuideScriptHost = defineComponent({
  name: 'IpdGuideScriptHost',
  props: {
    value: { type: String, required: true },
  },
  setup(props) {
    useAgentContext({
      description: GUIDE_CONTEXT_KEY,
      value: props.value,
    });
    return () => null; // 零渲染，仅上下文通道
  },
});
```

**`ai-assistant.vue` Modify 补丁（三处，消息流/SSE/卡片零改动）**：

```ts
// ① import 追加（与 L60-70 既有 import 区并列）：
import { fetchGuideEvents } from '../../../api/ipd/guide-script';
import {
  buildGuideContextValue,
  buildGuideSuggestions,
  extractGuideText,
  parseAdvanceGate,
  parseGuideSteps,
  type AdvanceGateVm,
  type GuideStepVm,
} from './ai-guide/guide-script';
import { IpdGuideScriptHost } from './ai-guide/guide-script-host';
import GuideSuggestionBar from './ai-guide/guide-suggestion-bar.vue';

// ② 状态与监听追加（与 L422-428 既有 ref 区并列；onMounted/onUnmounted 从 vue import 补）：
const guideSteps = ref<GuideStepVm[]>([]);
const guideGate = ref<AdvanceGateVm | null>(null);
const guideSuggestions = ref<string[]>([]);
const guideContextValue = ref(buildGuideContextValue('none', null));

async function onGuideSubStage(event: Event) {
  const detail = (event as CustomEvent<{ subStageCode: string; projectId?: string }>).detail;
  if (!detail?.subStageCode) return;
  const events = await fetchGuideEvents(detail.subStageCode, detail.projectId);
  const steps = parseGuideSteps(events);
  guideSteps.value = steps;
  guideGate.value = parseAdvanceGate(events);
  guideSuggestions.value = buildGuideSuggestions(steps);
  guideContextValue.value = buildGuideContextValue(detail.subStageCode, steps[0] ?? null);
  const text = extractGuideText(events);
  if (text) {
    // 文本降级路径照常进消息流（单轨红线 3）
    messages.value.push({ content: text, intent: null, role: 'assistant', sources: null, streaming: false });
  }
}

function onPickSuggestion(text: string) {
  inputText.value = text;   // 只填输入框，发送走既有 send()（红线 4：不加写端点）
}

onMounted(() => window.addEventListener('ipd:guide-sub-stage', onGuideSubStage));
onUnmounted(() => window.removeEventListener('ipd:guide-sub-stage', onGuideSubStage));

// ③ 模板两处挂载（既有结构不动）：
//    a) <IpdAiCardRenderHost :on-confirm="onCardConfirm" /> 之后追加同级：
//       <IpdGuideScriptHost :value="guideContextValue" />
//    b) <div class="input-row">（L725）之前追加：
//       <GuideSuggestionBar :suggestions="guideSuggestions" @pick="onPickSuggestion" />
```

> 单轨红线自查：①无第二聊天 UI（chips 仅 input-row 上方建议条）；②无平行卡片体系（guideSteps 走文本+chips，不进 ai-cards 注册表）；③文本降级保留（`extractGuideText` → messages）；④无新写端点（`fetchGuideEvents`=GET、pick 只填 `inputText`）；⑤无复制校验（帧解析仅本地 `isPlainRecord` 小判别，同 ai-assistant.vue L178 先例形态）；`org.ruoyi.service.coding.harness` 零触碰；`package.json` 零改动（`@copilotkit/vue/v2` 为既有依赖，copilotkit-render.ts L31 已用）。

### C4.6 勾选步骤（C4b，TDD 粒度）

1. [ ] **红**：`pnpm run check:type` 现状通过基线记录 → 临时在 `guide-script.test.ts` 追加一条引用 `buildGuideSuggestions` 多步排序断言（多步骤 chips 序号 1..n）跑红（当前 `buildGuideSuggestions` 已实现则改为新断言先写）
2. [ ] **最小实现①**：创建 `guide-suggestion-bar.vue`、`guide-script-host.ts`（C4b 代码段）
3. [ ] **最小实现②**：按补丁 Modify `ai-assistant.vue`（import 区、ref/监听区、模板两挂载点；`onMounted/onUnmounted` 加进 L37-44 的 vue import）
4. [ ] **跑绿**：`pnpm exec vitest run --config vitest.ipd.config.mts` 全绿（6+1 用例）+ `pnpm run check:type` + `pnpm run build:antd`（三件套 C0-7，仓根执行）
5. [ ] **点检锚**（无组件测试框架依赖，package.json 不动）：浏览器手检 ①工作台派发 `ipd:guide-sub-stage` 后 chips 出现 ②点击 chip 填入输入框 ③引导文本进消息流 ④断网时 chips 不崩（fetch 失败→空数组）
6. [ ] **Commit（待批）**：暂存 2 Create + 1 Modify + diff 摘要。commit 信息：`feat(ipd-web): C4b 引导 chips 与 pageContext 注入接线（单轨红线自查通过）`

**依赖顺序**：C4a + C2（后端 guideSteps/advanceGate 键就位方能联调）；与 Track B 的 `ai-assistant.vue` 修改**串行**（§7 冲突面）。
**验证命令汇总**（仓根）：`pnpm run check:type && pnpm exec vitest run --config vitest.ipd.config.mts && pnpm run build:antd`

---

## 7. 多智能体并行编排表（allowedPaths 写权限隔离）

| 窗口 | 任务 | allowedPaths（写） | 并行对象 | 冲突面/串行条件 |
|---|---|---|---|---|
| W1 | C1 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/GuideScriptCatalog.java`、`.../src/test/java/org/ruoyi/ipd/seed/GuideScriptCatalogContractTest.java` | W2（C3）并行 | 无共享文件 |
| W2 | C3 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/copilotkit/CommandDegrader.java`、`.../copilotkit/CommandDegraderTest.java` | W1（C1）并行 | 无共享文件；不动 AgUiCopilotRun/AgUiFrameTranslator/AgUiEvents |
| W3 | C2 | `.../vo/GuideSequenceView.java`、`.../service/SubStageGuideOrchestrator.java`、`.../service/SubStageGuideOrchestratorTest.java` | 无 | **串行**于 W1+W2（编译依赖）与 Track A（A2-A5 类）；Modify `SubStageController.java`+`SubStageControllerTest.java` 需与 Track A 开发者同锁（A4 已建该文件） |
| W4 | C4a | `apps/web-antd/src/api/ipd/guide-script.ts`、`apps/web-antd/src/views/ipd/_shared/ai-guide/guide-script.ts`、`.../guide-script.test.ts` | W1-W3 并行 | 无共享文件 |
| W5 | C4b | `.../ai-guide/guide-suggestion-bar.vue`、`.../guide-script-host.ts`、`views/ipd/_shared/ai-assistant.vue` | 无 | **串行**于 W4 + Track B 对 `ai-assistant.vue` 的窗口（Track B/E 已登记同文件冲突）；**不改** `vitest.ipd.config.mts`（Track E 串行窗口文件，本 Track 零触碰） |

**里程碑门禁**：
- **G-C1**：`GuideScriptCatalogContractTest` 5 用例绿 + ActionCatalog 三哨兵零回归 → 话术库可评审
- **G-C3**：`CommandDegraderTest` 3 用例绿 + 42 命令计数锚 → 降级层可接入
- **G-C2**：`SubStageGuideOrchestratorTest`+`SubStageControllerTest` 绿 + STATE_DELTA 事件序与 A4.3 同契约 → 后端链路通
- **G-C4a**：vitest 6 用例绿 + `check:type` 过 → 前端纯函数层可接线
- **G-C5**（总门禁）：三件套全绿 + 单轨红线五条自查逐条勾 + 联调点检锚（C4.6-5）→ Track C 可合入

**风险登记**：
| # | 风险 | 缓解 |
|---|---|---|
| R-C1 | 话术质量未经理论评审（69 条为 §3.2 引导要点转写） | 合同测试锁结构非锁文采；话术评审=改常量类走 PR（C1.0 形态优势） |
| R-C2 | Track A 类未落地，C2 编译依赖悬空 | §0 依赖链声明；C2 在 A2-A5 合入后开工；`SubStageControllerTest` 追加用例与 Track A 同锁 |
| R-C3 | `ipd_action_skill_map.skill_names` 仍 NULL（§6 待 owner apply），C2 steps 的 skillNames 全部取自 GuideScriptCatalog 而非表 | 设计即如此（防双事实源）；表列定稿后可做对账哨兵（未证明项 U-C5） |
| R-C4 | `ipd:guide-sub-stage` 派发端（工作台页面）不在本 Track 范围 | §7 边界声明；无派发则 chips 不出现，静默安全 |
| R-C5 | pageContext 4000 字符截断可能截断中文字符 | `buildGuideContextValue` 按 UTF-16 长度截断（与后端 MAX_PAGE_CONTEXT 计数口径差异见未证明项 U-C4） |

---

## 8. 未证明项（查不到/待裁定，实现时不得当已证事实使用）

| # | 未证明项 | 现状 | 影响与处置 |
|---|---|---|---|
| U-C1 | Track A 类（`IpdSubStageService`/`IpdActionSkillMapService`/`SubStageView`/`SubStageGuideTool`/`SubStageGateService`）**尚未在仓内实现**（实测 `find ruoyi-modules/ruoyi-ipd/src -name 'SubStage*'` 零命中，2026-09-28）；本文签名均引 Track-A-任务分解.md 规划 | 未取到实体代码 | C2 严格等 A2-A5 合入后开工；签名以 Track A 详稿为准，若落地时漂移则按实际代码修订 C2.2 |
| U-C2 | `useAgentContext` 在 `@copilotkit/vue/v2` 的精确参数形态（§4 纠正表有签名说明，但仓内无任何既有调用可对照——`useSuggestions\|useAgentContext\|useFrontendTool` grep 零命中） | 推断（引 §4 纠正表） | C4b 首次调用若 TS 报错，以包内 `.d.ts` 实际签名为准调整 `guide-script-host.ts`；不影响 C4a 纯函数层 |
| U-C3 | `ipd:guide-sub-stage` 事件的**派发端**（工作台/详情页）归属 | 未取到（Track B 页面侧职责） | 本 Track 只订阅；无派发时 chips 静默不出现（安全降级）。派发端落地由 Track B 或后续批次补 |
| U-C4 | 后端 `MAX_PAGE_CONTEXT=4000` 的计数口径（字节/字符/码点）未取到实现细节（AgUiCopilotRun.java L36-37 仅见常量名与值） | 未取到 | 前端按 4000 字符保守截断（UTF-16 code unit）；若后端按字节计，中文场景实际可用更小——联调时核对 |
| U-C5 | `ipd_action_skill_map.skill_names` 定稿值与 GuideScriptCatalog.skillNames 的对账机制 | §6 该列 NULL=未定稿（待 owner apply） | 当前 GuideScriptCatalog 为 skill 词表唯一事实源（C1.0 决策）；owner apply 定稿后建议补「表↔常量类」对账哨兵测试（不在本 Track 范围） |
| U-C6 | LC09 绑定 `shipping-artifacts`、V12/L06/LC08 弱绑定 `grammar-check` 等**推断绑定**（§3.3 原则 7 标注）未经 pm-skills 上游 SKILL.md 逐文件核验（§3.4.2-5：仅实抓 code-review 一个 SKILL.md） | 推断 | 话术/降级表已逐条标「推断」；评审可整条否决不影响其他行（§3.3 原则 7 先例） |
| U-C7 | `IpdSubStage.getIsResident()` 值域（"1"/"0" 或 Y/N）与 `IpdActionSkillMap.builder()` 可用性 | 推断（A2.7 测试 L751 用 builder、A5.1 javadoc 提 is_resident=1） | 若实际实体值域不同，调整 `nextSubStageCode` 首行判断与 C2.5 测试桩；编译期即暴露 |
| U-C8 | 69 条话术文案的业务准确性（转写自 §3.2 引导要点，未逐条经业务 owner 确认） | 推断（转写） | 话术评审走 PR diff（C1.0 形态优势）；文案改动零迁移成本 |

---

**本分节草稿完（Track C）。** 合入提示：① C2 开工前先确认 Track A A2-A5 已合入（U-C1）；② 69 条话术与 42 条降级话术请 owner 过一遍文案（U-C8）；③ 本文所有 SQL 均无——`ipd_action_skill_map` 维持 §6「待 owner apply」口径，Track C 零 DDL/DML。
