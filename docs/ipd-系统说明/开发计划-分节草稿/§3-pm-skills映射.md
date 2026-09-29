# §3 pm-skills 映射（69 个 IPD 标准动作 × 技能/命令）— 分节草稿

> **状态**：草稿（供主计划 §3 合入前评审）　**产出日期**：2026-09-28
> **范围**：仅调研与映射，不含实现代码。本文件为新建分节草稿，不修改任何既有文件（尤其主计划文档）。
> **覆盖**：69/69 动作全覆盖、零重复（C01-C12 / P01-P13 / D01-D11 / V01-V12 / L01-L08 / LC01-LC09 / K01-K04）。

## 3.0 事实源与取证口径

| 代号 | 事实源 | 用途 | 取证方式 |
|---|---|---|---|
| **S1** | `ruoyi-ai/docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md`（308 行） | 69 动作 code/名称/深管轻管/阻断性/主导角色的**业务权威** | 本地只读全文 |
| **S2** | `最佳实践/OpenMCP/2026_09_19…PM_Skills…/article.md`（264 行） | pm-skills 中文解读，skill/命令交叉验证 | 本地只读全文 |
| **S3** | GitHub `phuryn/pm-skills` README（raw，530 行） | **skill/command 真名的权威清单** | WebFetch `https://raw.githubusercontent.com/phuryn/pm-skills/main/README.md`（2026-09-28 抓取，行号按 raw 版本） |
| **S4** | `pm-ai-shipping/skills/code-review/SKILL.md` | 验证 `code-review` skill 真实存在 | WebFetch raw（frontmatter `name: code-review` 实证） |

**口径规则**：
1. 69 动作的 code 与名称**逐字取自 S1**（去掉 markdown 加粗符号）；与主计划 §2 不一致处以 S1 为准，并在 §4 显式列出差异。
2. skill/command 名称**只采用 S3 列出的真名**，并与 S2 交叉一致；三源都找不到的写「未取到」，不臆造。
3. 推断性结论一律标注「推断」。

---

## 3.1 真实能力清单（9 插件 × skill/command，全部实抓）

> 计数来源：S3 README L11「69 PM skills and 42 chained workflows across 9 plugins」；S2 article.md L12「9 个插件、69 个 Skills、42 条命令」。两源一致。
> 行号列：S3=README raw 行号；S2=article.md 行号；URL=GitHub 目录页。

### 1. pm-product-discovery（13 skills / 5 commands）— 发现：点子→假设→排序→实验

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `brainstorm-ideas-existing` | S3 L147；S2 L45 |
| skill | `brainstorm-ideas-new` | S3 L148；S2 L46 |
| skill | `brainstorm-experiments-existing` | S3 L149；S2 L47 |
| skill | `brainstorm-experiments-new` | S3 L150；S2 L48 |
| skill | `identify-assumptions-existing` | S3 L151；S2 L49 |
| skill | `identify-assumptions-new` | S3 L152；S2 L50 |
| skill | `prioritize-assumptions` | S3 L153；S2 L51 |
| skill | `prioritize-features` | S3 L154；S2 L52 |
| skill | `analyze-feature-requests` | S3 L155；S2 L53 |
| skill | `opportunity-solution-tree` | S3 L156；S2 L54 |
| skill | `interview-script` | S3 L157；S2 L55 |
| skill | `summarize-interview` | S3 L158；S2 L56 |
| skill | `metrics-dashboard` | S3 L159；S2 L57 |
| command | `/discover`（ideation→assumption mapping→prioritization→experiment design） | S3 L163；S2 L35/L259 |
| command | `/brainstorm`（`ideas\|experiments` × `existing\|new`） | S3 L164 |
| command | `/triage-requests` | S3 L165 |
| command | `/interview`（`prep\|summarize`） | S3 L166 |
| command | `/setup-metrics` | S3 L167 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-product-discovery

### 2. pm-product-strategy（12 skills / 5 commands）— 战略：取舍与商业成立

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `product-strategy`（9 部分战略画布） | S3 L190；S2 L73 |
| skill | `startup-canvas` | S3 L191；S2 L74 |
| skill | `product-vision` | S3 L192；S2 L75 |
| skill | `value-proposition`（6 段式 JTBD） | S3 L193；S2 L76 |
| skill | `lean-canvas` | S3 L194；S2 L77 |
| skill | `business-model` | S3 L195；S2 L78 |
| skill | `monetization-strategy` | S3 L196；S2 L79 |
| skill | `pricing-strategy` | S3 L197；S2 L80 |
| skill | `swot-analysis` | S3 L198；S2 L81 |
| skill | `pestle-analysis` | S3 L199；S2 L82 |
| skill | `porters-five-forces` | S3 L200；S2 L83 |
| skill | `ansoff-matrix` | S3 L201；S2 L84 |
| command | `/strategy`（9 部分 Product Strategy Canvas） | S3 L205；S2 L63 |
| command | `/business-model`（`lean\|full\|startup\|value-prop\|all`） | S3 L206 |
| command | `/value-proposition` | S3 L207 |
| command | `/market-scan`（SWOT+PESTLE+五力+Ansoff 合成） | S3 L208；S2 L63 |
| command | `/pricing` | S3 L209 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-product-strategy

### 3. pm-execution（16 skills / 11 commands）— 执行：PRD/路线图/故事/Sprint/复盘

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `create-prd`（8 部分 PRD） | S3 L232；S2 L122 |
| skill | `brainstorm-okrs` | S3 L233；S2 L123 |
| skill | `outcome-roadmap` | S3 L234；S2 L124 |
| skill | `sprint-plan` | S3 L235；S2 L125 |
| skill | `retro` | S3 L236；S2 L126 |
| skill | `release-notes` | S3 L237；S2 L127 |
| skill | `pre-mortem`（Tigers/Paper Tigers/Elephants） | S3 L238；S2 L128 |
| skill | `stakeholder-map`（权力×关注度） | S3 L239；S2 L129 |
| skill | `summarize-meeting` | S3 L240；S2 L130 |
| skill | `user-stories`（3C+INVEST） | S3 L241；S2 L131 |
| skill | `job-stories` | S3 L242；S2 L132 |
| skill | `wwas`（Why-What-Acceptance） | S3 L243；S2 L133 |
| skill | `test-scenarios` | S3 L244；S2 L134 |
| skill | `dummy-dataset` | S3 L245；S2 L135 |
| skill | `prioritization-frameworks`（RICE/ICE/Kano/MoSCoW 等 9 种） | S3 L246；S2 L136 |
| skill | `strategy-red-team` | S3 L247；S2 L137 |
| command | `/write-prd` | S3 L251；S2 L112 |
| command | `/plan-okrs` | S3 L252 |
| command | `/transform-roadmap` | S3 L253 |
| command | `/sprint`（`plan\|retro\|release`） | S3 L254；S2 L112 |
| command | `/pre-mortem` | S3 L255 |
| command | `/red-team-prd` | S3 L256 |
| command | `/meeting-notes` | S3 L257 |
| command | `/stakeholder-map` | S3 L258 |
| command | `/write-stories`（`user\|job\|wwa`） | S3 L259；S2 L112 |
| command | `/test-scenarios` | S3 L260 |
| command | `/generate-data` | S3 L261 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-execution

### 4. pm-market-research（7 skills / 3 commands）— 用户与竞争研究

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `user-personas` | S3 L284；S2 L100 |
| skill | `market-segments` | S3 L285；S2 L101 |
| skill | `user-segmentation` | S3 L286；S2 L102 |
| skill | `customer-journey-map` | S3 L287；S2 L103 |
| skill | `market-sizing`（TAM/SAM/SOM） | S3 L288；S2 L104 |
| skill | `competitor-analysis` | S3 L289；S2 L105 |
| skill | `sentiment-analysis` | S3 L290；S2 L106 |
| command | `/research-users`（画像+分群+旅程图） | S3 L294；S2 L90 |
| command | `/competitive-analysis` | S3 L295；S2 L90 |
| command | `/analyze-feedback` | S3 L296 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-market-research

### 5. pm-data-analytics（3 skills / 3 commands）— 数据分析

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `sql-queries` | S3 L319；S2 L153 |
| skill | `cohort-analysis` | S3 L320；S2 L154 |
| skill | `ab-test-analysis` | S3 L321；S2 L155 |
| command | `/write-query` | S3 L325 |
| command | `/analyze-cohorts` | S3 L326 |
| command | `/analyze-test` | S3 L327 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-data-analytics

### 6. pm-go-to-market（6 skills / 3 commands）— GTM

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `gtm-strategy` | S3 L349；S2 L173 |
| skill | `beachhead-segment` | S3 L350；S2 L171 |
| skill | `ideal-customer-profile` | S3 L351；S2 L172 |
| skill | `growth-loops` | S3 L352；S2 L174 |
| skill | `gtm-motions` | S3 L353；S2 L175 |
| skill | `competitive-battlecard` | S3 L354；S2 L176 |
| command | `/plan-launch`（滩头→上线计划全链） | S3 L358；S2 L161 |
| command | `/growth-strategy` | S3 L359 |
| command | `/battlecard` | S3 L360 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-go-to-market

### 7. pm-marketing-growth（5 skills / 2 commands）— 营销增长

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `marketing-ideas` | S3 L383；S2 L192 |
| skill | `positioning-ideas` | S3 L384；S2 L193 |
| skill | `value-prop-statements` | S3 L385；S2 L194 |
| skill | `product-name` | S3 L386；S2 L195 |
| skill | `north-star-metric` | S3 L387；S2 L196 |
| command | `/market-product`（营销想法/定位/价值主张/命名） | S3 L391；S2 L182 |
| command | `/north-star` | S3 L392；S2 L182 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-marketing-growth

### 8. pm-toolkit（4 skills / 5 commands）— PM 文书工具箱（不在主链路上）

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `review-resume` | S3 L414；S2 L212 |
| skill | `draft-nda` | S3 L415；S2 L213 |
| skill | `privacy-policy`（GDPR/CCPA 合规草稿） | S3 L416；S2 L214 |
| skill | `grammar-check` | S3 L417；S2 L215 |
| command | `/review-resume` | S3 L421 |
| command | `/tailor-resume` | S3 L422 |
| command | `/draft-nda` | S3 L423 |
| command | `/privacy-policy` | S3 L424 |
| command | `/proofread` | S3 L425 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-toolkit

### 9. pm-ai-shipping（3 skills / 5 commands）— AI 生成代码的可审查化

| 类型 | 真名 | 来源 |
|---|---|---|
| skill | `shipping-artifacts`（可审查文档集） | S3 L447；S2 L229 |
| skill | `intended-vs-implemented`（文档意图 vs 实现对照） | S3 L448；S2 L230 |
| skill | `code-review`（跨边界约定找可复现缺陷） | S4 SKILL.md frontmatter `name: code-review` 实证；S2 L219/L231；S3 摘要 L441「3 skills」 |
| command | `/ship-check`（发布前审查包） | S3 L452；S2 L233 |
| command | `/document-app`（逆向整理系统文档） | S3 L453；S2 L233 |
| command | `/derive-tests`（意图→测试覆盖图） | S3 L454；S2 L233 |
| command | `/security-audit-static` | S3 L455；S2 L233 |
| command | `/performance-audit-static` | S3 L456；S2 L233 |

URL：https://github.com/phuryn/pm-skills/tree/main/pm-ai-shipping
> ⚠️ 计数勘误（已核实）：README 插件摘要 L441 写「3 skills, 5 commands」，但正文 L445 写「**Skills (2):**」且只列 2 个——`code-review` 是后加入的第三个 skill（S2 L219「仓库现在还包含 code-review」+ S4 SKILL.md frontmatter 实证存在）。**以 3 为准**；README 正文 L445 为上游陈旧文本。

### 3.1.1 总计核对

| 插件 | skills | commands |
|---|---|---|
| pm-product-discovery | 13 | 5 |
| pm-product-strategy | 12 | 5 |
| pm-execution | 16 | 11 |
| pm-market-research | 7 | 3 |
| pm-data-analytics | 3 | 3 |
| pm-go-to-market | 6 | 3 |
| pm-marketing-growth | 5 | 2 |
| pm-toolkit | 4 | 5 |
| pm-ai-shipping | 3 | 5 |
| **合计** | **69** ✅（=S3 L11 / S2 L12 口径） | **42** ✅ |

**实抓结论：9/9 插件全部取到真名，无「未取到」项。** 69 个 skill 名与 42 个 command 名均在 S3 出现，且与 S2 的 69 个 skill 表逐名交叉一致（S2 未逐一列 command 名，command 以 S3 为准）。

---

## 3.2 映射表（6 阶段 + KPI 分组，69/69 全覆盖）

> 列说明：**动作**=code + S1 逐字名称；**深度/阻断**=S1 口径（深=深管、轻=轻管；Y/N=阻断性，`*` = 条件/例外见备注）；**小阶段**=主计划 §2 归属（分组对齐用）；**绑定 skill**=1-3 个真名，`〔候选〕`=轻管动作按 §3.3 原则 3 不强制绑定、仅轻量提示；**command 链**=按官方工作流顺序串联（均为 42 条真命令）。
> 记号：**绑定**=深管产出物类绑定；**弱**=仅文案/校对类轻量绑定（推断）；**候选**=不绑定、仅提示；**不绑定**=纯登记。

### 3.2.1 阶段一 概念 CONCEPT（12 动作，S1 L79-98）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| C01 市场机会与痛点调研 | 深 / Y | CONCEPT-S1 市场洞察 | **绑定** `interview-script`、`summarize-interview`、`market-sizing` | `/interview prep` → `/interview summarize` | 用 JTBD/Mom Test 提纲做痛点访谈，逐份归纳信号，估 TAM/SAM/SOM，产出市场调研报告 |
| C04 区域市场准入与需求差异调研（海外） | 深 / Y | CONCEPT-S1 市场洞察 | **绑定** `pestle-analysis`、`market-segments` | `/market-scan` | 按目标国别跑 PESTLE（法规/文化/电压/插头等变量），输出区域市场差异清单 |
| C02 竞品分析（功能/价格/渠道/技术路线） | 深 / Y | CONCEPT-S2 竞争与客群 | **绑定** `competitor-analysis`、`porters-five-forces`、`swot-analysis` | `/competitive-analysis` → `/market-scan` | 先竞品四维对比（功能/价格/渠道/技术路线），再五力+SWOT 收敛差异化空间 |
| C03 目标客户与细分市场定义 | 深 / Y | CONCEPT-S2 竞争与客群 | **绑定** `user-personas`、`market-segments`、`user-segmentation` | `/research-users` | 访谈证据→三类画像→3-5 细分市场→匹配度评估，产出目标客户画像 |
| C06 产品概念与差异化定位 | 深 / Y | CONCEPT-S3 商业论证 | **绑定** `product-vision`、`value-proposition`、`positioning-ideas` | `/strategy`（节选）→ `/value-proposition` → `/market-product` | 先立愿景与 JTBD 六段式价值主张，再从竞品定位角度收敛差异化，产出产品概念说明书 |
| C07 成本/定价/毛利初步测算 | 深 / Y | CONCEPT-S3 商业论证 | **绑定** `pricing-strategy`、`monetization-strategy` | `/pricing` | 用定价模型+竞品价格+支付意愿测算，产出成本与定价测算表 |
| C08 销量预测与商业目标（上市6个月销售目标/渠道数/NPS/场景数四项基准值在此录入） | 深 / Y | CONCEPT-S3 商业论证 | **绑定** `market-sizing`、`brainstorm-okrs`、`north-star-metric` | `/plan-okrs` → `/north-star` | 四项基准值在此录入并锁口径（G1 后锁定，是奖金池/共担 KPI 计算基准），产出商业计划书 Charter |
| C09 项目等级评定 S/A/B + 差异化系数（已定：S=1.5 / A=1.0 / B=0.8） | 深 / Y | CONCEPT-S3 商业论证 | 不绑定（详见 §4-1） | — | 治理决策走结构化表单（等级+系数+产品组长 A 角+双签审计），AI 不代填系数 |
| C05 技术可行性预研 | 轻 / N | CONCEPT-S4 合规与立项 | 不绑定（三要素登记） | — | 仅登记状态/日期/备注；AI 提示「轻管动作不强制交付物」 |
| C10 知识产权与合规预检（含专利 FTO 分析） | 深 / N | CONCEPT-S4 合规与立项 | 不绑定〔候选 `privacy-policy` 仅覆盖隐私合规文本〕（详见 §4-2） | —（可 `/privacy-policy` 辅助合规文本） | 强制上传检索报告（含 FTO）；专利检索需人工/外部工具，AI 只提醒归档与完整性 |
| C12 / Z03 生物特征数据合规审查 | 深 / Y（全等级） | CONCEPT-S4 合规与立项 | **绑定** `privacy-policy` | `/privacy-policy` | 按 GDPR 特殊类别数据+个保法+《人脸识别技术应用安全管理办法》逐项勾稽，产出合规审查清单（法律红线，S/A/B 级均阻断） |
| C11 Charter 立项评审会 | 深 / Y | CONCEPT-S4 合规与立项（**G1**） | **绑定** `summarize-meeting`、`strategy-red-team`、`stakeholder-map` | `/red-team-prd` → `/stakeholder-map` → `/meeting-notes` | 会前红队攻击 Charter 关键假设，按干系人策略对齐双签人，会后纪要+行动项留痕 |

### 3.2.2 阶段二 计划 PLAN（13 动作，S1 L106-135）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| P01 产品需求规格定义（PRD） | 深 / Y | PLAN-S1 需求定义 | **绑定** `create-prd`、`strategy-red-team` | `/write-prd` → `/red-team-prd` | 八段式 PRD 起草后红队找最脆弱前提并修订，产出产品需求规格书 PRD |
| P02 需求优先级排序与版本规划 | 深 / Y | PLAN-S1 需求定义 | **绑定** `prioritize-features`、`prioritization-frameworks`、`outcome-roadmap` | `/triage-requests` → `/transform-roadmap` | 选 RICE/ICE 等框架排需求池，功能清单转 outcome 路线图，产出版本规划表（注意 §4-3 名称差异） |
| P03 总体技术方案与系统架构设计 | 轻 / N | PLAN-S2 技术方案 | 不绑定〔候选 `shipping-artifacts`，仅 AI 代码方案时〕 | — | 轻管仅登记（§4-3：主计划 §2 标深、S1 为轻，以 S1 为准） |
| P04 ID/结构/硬件/固件方案设计 | 轻 / N | PLAN-S2 技术方案 | 不绑定（登记） | — | 硬件方案无对应 skill，登记完成即可 |
| P05 软件概要设计（架构/接口/数据模型） | 轻 / N | PLAN-S2 技术方案 | 不绑定〔候选 `shipping-artifacts`〕 | — | 登记完成；如需文档骨架可轻量提示（推断） |
| P06 解决方案场景设计与集成方案 | 轻 / N | PLAN-S2 技术方案 | 不绑定〔候选 `customer-journey-map`〕 | — | 登记完成；场景梳理可轻量提示旅程图（推断） |
| P07 关键器件选型与供应链评估 | 轻 / N | PLAN-S2 技术方案 | 不绑定（登记） | — | 供应链评估无对应 skill（§4-5） |
| P08 项目计划与里程碑排期（含双PM分工表） | 轻 / N | PLAN-S3 计划与资源 | 不绑定〔候选 `sprint-plan`〕 | — | **必须登记关键里程碑日期**（上市准时率/窗口命中率 KPI 依赖它） |
| P09 资源与预算评估 | 轻 / N | PLAN-S3 计划与资源 | 不绑定（登记） | — | 登记完成即可 |
| P11 风险识别与应对计划 | 轻 / N | PLAN-S3 计划与资源 | 不绑定〔候选 `pre-mortem`，仅话术提示〕 | — | 轻管不产强制交付物；AI 可用 Tigers/Paper Tigers/Elephants 话术口头引导风险识别 |
| P10 认证与法规清单确认（按目标市场配置） | 轻 / **Y**（阻断例外） | PLAN-S4 合规与差异化确认 | 不绑定（结构化登记） | — | 按国别认证模板库带出清单登记；阻断走系统门禁（认证缺失无法上市） |
| P12 差异化卖点确认与价值定价 | 深 / Y | PLAN-S4 合规与差异化确认 | **绑定** `value-proposition`、`pricing-strategy`、`value-prop-statements` | `/value-proposition` → `/pricing` | 卖点用 JTBD 六段式收束、定价复核毛利，产出差异化卖点清单+定价策略 |
| P13 差异化确认评审会 | 深 / Y | PLAN-S4 合规与差异化确认（**G2**） | **绑定** `summarize-meeting`、`strategy-red-team` | `/red-team-prd` → `/meeting-notes` | 会前红队验证卖点可交付性与毛利复核，会后纪要双签（G2） |

### 3.2.3 阶段三 开发 DEV（11 动作，S1 L144-162）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| D01 详细设计（结构/硬件/软件） | 轻 / N | DEV-S1 设计与联调准备 | 不绑定（登记） | — | 仅登记完成（§4-3：§2 同码异名，以 S1 名称为准） |
| D02 首版BOM冻结与采购下单 | 轻 / N | DEV-S1 设计与联调准备 | 不绑定（登记） | — | 硬件/采购动作无对应 skill（§4-5） |
| D03 手板 / EVT 样机制作 | 轻 / N | DEV-S1 设计与联调准备 | 不绑定（登记） | — | 硬件动作无对应 skill（§4-5） |
| D10 解决方案联调与集成测试环境搭建 | 轻 / N | DEV-S1 设计与联调准备 | 不绑定〔候选 `test-scenarios`〕 | — | 登记完成；联调用例可轻量提示（推断） |
| D04 软件开发与单元测试 | 轻 / N | DEV-S2 迭代开发 | 不绑定〔候选 `test-scenarios`〕 | — | 登记完成；单测场景可轻量提示 `/test-scenarios`（推断，不产强制交付物） |
| D07 模具开发与 T1 试模 | 轻 / N | DEV-S2 迭代开发 | 不绑定（登记） | — | 硬件试模无对应 skill（§4-5） |
| D11 / Z01 BioCV 算法训练与评测 | 轻 / N（**FAR/FRR 必登**） | DEV-S2 迭代开发 | 不绑定（数值登记） | — | 无算法评测 skill（§4-6）；引导录入实测 FAR/FRR 数值字段并提示与基线横向对比 |
| D09 内测版本发布（Alpha） | 轻 / N | DEV-S2 迭代开发 | 不绑定〔候选 `release-notes`〕 | — | **登记发布日期**；发布说明可轻量提示 `/sprint release`（推断） |
| D05 双周开发评审（每两周一次，贯穿全阶段） | 深 / Y | DEV-S2 迭代开发（**G3**） | **绑定** `summarize-meeting`、`outcome-roadmap` | `/meeting-notes`（可 `/sprint retro`） | 双周纪要盯进度+场景完整度（市场PM 视角）；连续 2 次 P0 阻塞未升级自动升级双方产品组长 |
| D06 需求变更评估与审批 | 深 / Y | DEV-S3 变更与成本 | **绑定** `analyze-feature-requests`、`prioritize-features`、`strategy-red-team` | `/triage-requests` → `/red-team-prd` | 变更请求归类→影响/优先级评估→红队检验必要性，产出需求变更单（双签否决；系统自动统计变更率供 KPI 取数） |
| D08 开发阶段成本复核 | 轻 / N | DEV-S3 变更与成本 | 不绑定（登记） | — | 成本复核为表单登记，无对应 skill |

### 3.2.4 阶段四 验证 VALID（12 动作，S1 L168-188）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| V01 DVT 设计验证测试 | 轻 / N | VALID-S1 设计验证 | 不绑定〔候选 `test-scenarios`〕 | — | 硬件 DVT 登记完成；测试场景骨架可轻量提示（推断） |
| V04 软件系统测试与缺陷收敛 | 轻 / N | VALID-S1 设计验证 | 不绑定〔候选 `test-scenarios`〕 | — | 登记完成；可用 `/test-scenarios` 生成用例骨架（不产强制交付物） |
| V11 / Z04 平台兼容性与 SDK/API 对接验证 | 轻（解决方案类自动转**深**）/ N（解：**Y**） | VALID-S1 设计验证 | **绑定**（解模板）`intended-vs-implemented`、`shipping-artifacts` | `/document-app` → `/ship-check` | 登记对接范围与通过结论；解决方案模板升级深管时用「文档意图 vs 实现」对照审查集成偏差（推断绑定仅在解模板生效） |
| V02 认证测试送检（按目标市场清单） | 轻 / **Y**（阻断例外） | VALID-S2 认证与适配 | 不绑定（结构化登记） | — | 仅登记证书编号+通过日期（不强制上传扫描件）；阻断走系统门禁 |
| V10 / Z02 跨人种 / 跨年龄适配验证（含算法公平性偏见测试，Z08 并入） | 深 / Y | VALID-S2 认证与适配 | **绑定** `user-segmentation`、`metrics-dashboard` | `/setup-metrics` | 按人群分层定义 FAR/FRR 指标与告警阈值，分人群测试报告强制上传（推断；Z08 公平性测试并入本动作 SOP） |
| V12 / Z05 海外市场本地化适配验证 | 深 / Y | VALID-S2 认证与适配 | **弱** `grammar-check`（无专用本地化 skill，§4-7） | `/proofread` | 本地化验收清单逐项打勾（语言/阿拉伯语 RTL/电压 110V-220V/插头/国别认证），闭环 C04↔V07↔L03；多语言文案可校对（推断） |
| V05 试产 PVT / 小批量 | 轻 / N | VALID-S3 试产与量产准入 | 不绑定（登记） | — | 硬件动作无对应 skill（§4-5） |
| V06 量产准入评审 | 深 / Y | VALID-S3 试产与量产准入 | **绑定** `pre-mortem`、`summarize-meeting` | `/pre-mortem` → `/meeting-notes` | 会前预演量产失败风险（A=产品组长），纪要+评审材料留痕 |
| V03 Beta 客户试用与反馈收集 | 深 / Y | VALID-S4 客户与交付 | **绑定** `interview-script`、`summarize-interview`、`sentiment-analysis` | `/interview` → `/analyze-feedback` | Beta 访谈提纲→逐字稿归纳→大样本情绪/主题分析，产出 Beta 试用报告 |
| V09 解决方案试点客户交付验证 | 深 / Y | VALID-S4 客户与交付 | **绑定** `test-scenarios`、`intended-vs-implemented` | `/test-scenarios` → `/derive-tests` | 试点验收用例覆盖对照「文档意图 vs 实现」，产出试点交付验收报告（推断） |
| V07 包装、说明书、快速指南定稿 | 深 / Y | VALID-S4 客户与交付 | **绑定** `value-prop-statements`、`grammar-check` | `/market-product`（节选）→ `/proofread` | 文案价值表达定调后逐份校对，产出包装设计稿+用户手册（推断） |
| V08 售后与维修方案准备 | 轻 / N | VALID-S4 客户与交付 | 不绑定（登记） | — | 售后方案无对应 skill（§4-5） |

### 3.2.5 阶段五 发布 LAUNCH（8 动作，S1 L195-208）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| L01 GTM 上市策略（目标区域/渠道/节奏/首批客户） | 深 / Y | LAUNCH-S1 GTM 策略 | **绑定** `gtm-strategy`、`beachhead-segment`、`ideal-customer-profile` | `/plan-launch` | 滩头市场→ICP→信息/渠道/节奏一次成链，产出 GTM 上市方案 |
| L02 销售渠道与价格体系发布 | 深 / Y | LAUNCH-S1 GTM 策略 | **绑定** `pricing-strategy`、`gtm-motions`、`monetization-strategy` | `/pricing` → `/growth-strategy` | 渠道组合用 gtm-motions 选型，价格体系复核毛利与竞品价，产出渠道价格政策 |
| L03 销售工具包（彩页/PPT/DEMO/视频/官网/多语言物料） | 深 / Y | LAUNCH-S2 销售赋能 | **绑定** `competitive-battlecard`、`value-prop-statements`、`marketing-ideas` | `/battlecard` → `/market-product` | battlecard+价值主张语句做物料骨架，多语言物料对齐 V12 差异，产出工具包清单+物料 |
| L04 销售与渠道培训（含海外分支） | 深 / Y | LAUNCH-S2 销售赋能 | **绑定** `competitive-battlecard`、`value-prop-statements` | `/battlecard` | 培训材料以异议处理/赢单打法/价值话术为核心（推断），产出培训材料+签到记录 |
| L05 首批量产与备货 | 轻 / N | LAUNCH-S3 上市执行 | 不绑定（登记） | — | **登记备货完成日期**；硬件供应链无对应 skill |
| L06 系统上架（ERP编码/官网/电商/渠道门户） | 深 / Y | LAUNCH-S3 上市执行 | **弱** `value-prop-statements`、`grammar-check`（无产出物类 skill，§4-8） | `/proofread` | 逐渠道确认上架项+文案校对，产出上架确认记录（推断：以上架文案质量替代产出物类引导） |
| L07 GTM 就绪评审 | 深 / Y | LAUNCH-S3 上市执行（**G4**） | **绑定** `pre-mortem`、`stakeholder-map`、`summarize-meeting` | `/pre-mortem` → `/stakeholder-map` → `/meeting-notes` | 会前按 8 要素预演失败模式，销售/供应/售后干系人对齐，纪要双签后方可 L08 |
| L08 正式上市发布，录入上市日期 | 深 / Y | LAUNCH-S3 上市执行 | **绑定** `release-notes` | `/sprint release` | 发布说明生成；**上市日期录入后锁定**（全部后置 KPI 起算原点，修改需双签+审计） |

### 3.2.6 阶段六 生命周期 LIFECYCLE（9 动作，S1 L216-232）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| LC01 上市后销售与回款跟踪（月度录入，v3按回款口径） | 深 / Y | LIFECYCLE-S1 上市追踪 | **绑定** `sql-queries`、`metrics-dashboard`、`cohort-analysis` | `/write-query` → `/setup-metrics` → `/analyze-cohorts` | 月度**回款**台账取数+看板追踪+分批 cohort 对比（cohort 部分为推断）；口径=实际回款（Gavin Q2 决策） |
| LC05 客户反馈与质量问题处理 | 深 / N | LIFECYCLE-S1 上市追踪 | **绑定** `sentiment-analysis`、`analyze-feature-requests` | `/analyze-feedback` → `/triage-requests` | 反馈情绪/主题提取→问题归类分流→处理记录留痕（深管但非阻断：强制留痕+逾期提醒） |
| LC06 版本迭代与维护发布 | 轻 / N | LIFECYCLE-S1 上市追踪 | 不绑定〔候选 `release-notes`〕 | — | 登记完成；维护版发布说明可轻量提示 `/sprint release`（推断） |
| LC02 上市后 90 天复盘 | 深 / Y | LIFECYCLE-S2 复盘与结算（**G5**） | **绑定** `retro`、`summarize-meeting` | `/sprint retro` → `/meeting-notes` | 复盘落到负责人+期限明确的行动项，产出 90 天复盘报告+纪要（G5 双签否决） |
| LC03 上市后 6 个月终算：回款达成率 + 奖金池核算 | 深 / Y | LIFECYCLE-S2 复盘与结算 | **绑定** `sql-queries`、`metrics-dashboard` | `/write-query` → `/setup-metrics` | 回款达成率取数与口径核对；奖金池核算本身为表单计算，skill 绑定仅覆盖取数与指标定义（推断） |
| LC04 双PM 贡献度评定（五维度，市场40–65%/研发35–60%） | 深 / Y | LIFECYCLE-S2 复盘与结算 | 不绑定（详见 §4-1） | — | 治理评定走结构化评定表（三方评定=双PM+各自产品组长），AI 不代评 |
| LC07 生命周期状态维护（在售 / 限售 / 停产） | 深 / Y | LIFECYCLE-S3 状态与退出 | 不绑定（状态登记） | — | 状态机变更记录，无方法论 skill（§4-1） |
| LC08 停产评估与公告 | 深 / Y | LIFECYCLE-S3 状态与退出 | **弱** `grammar-check`（无产出物类 skill） | `/proofread` | 停产评估为决策表单（A=产品组长），公告文案可校对（推断） |
| LC09 项目归档（资料归档，转只读） | 深 / Y | LIFECYCLE-S3 状态与退出 | **绑定**（推断）`shipping-artifacts` | `/document-app`（仅含代码仓时） | 按「可审查文档集」骨架组织归档包转只读（R=系统自动，A=产品组长）；无代码仓时仅结构化归档清单 |

### 3.2.7 共担 KPI 归集（4 动作，常驻小阶段，S1 L240-247）

| 动作 | 深度/阻断 | 小阶段 | 绑定 skill（1-3） | command 链 | 引导要点 |
|---|---|---|---|---|---|
| K01 销量/出货量达成率归集 | 深 / N | KPI-S1 共担KPI归集 | **绑定** `sql-queries`、`metrics-dashboard` | `/write-query` → `/setup-metrics` | ERP 出库取数（一期手动录入+凭证）+指标口径定义；产品组长录入，次月第 5 个工作日 18:00 前 |
| K02 渠道商覆盖达成率归集 | 深 / N | KPI-S1 共担KPI归集 | **绑定** `sql-queries`、`metrics-dashboard` | `/write-query` → `/setup-metrics` | 渠道签约台账/CRM 取数+覆盖率指标定义 |
| K03 客户 NPS 调研归集 | 深 / N | KPI-S1 共担KPI归集 | **绑定** `sentiment-analysis`、`metrics-dashboard` | `/analyze-feedback` → `/setup-metrics` | 问卷有效样本≥30；NPS 文本用情绪/主题分析辅助解读（推断），数值走结构化字段 |
| K04 场景覆盖率归集 | 深 / N | KPI-S1 共担KPI归集 | **绑定** `sql-queries`、`metrics-dashboard` | `/write-query` → `/setup-metrics` | 销售报备+交付验收记录取数+覆盖率口径定义 |

### 3.2.8 映射覆盖率统计

| 类别 | 数量 | 动作 |
|---|---|---|
| **绑定**（深管产出物类 1-3 skill） | 36 | C01/C02/C03/C04/C06/C07/C08/C11/C12、P01/P02/P12/P13、D05/D06、V03/V06/V07/V09/V10/V11、L01/L02/L03/L04/L07/L08、LC01/LC02/LC03/LC05/LC09、K01/K02/K03/K04 |
| **弱绑定**（仅文案/校对类，推断） | 3 | V12、L06、LC08 |
| **候选**（轻管不绑定、仅轻量提示） | 12 | C10、P03/P05/P06/P08/P11、D04/D09/D10、V01/V04、LC06 |
| **无对应 skill**（纯结构化登记） | 18 | C05/C09、P04/P07/P09/P10、D01/D02/D03/D07/D08/D11、V02/V05/V08、L05、LC04/LC07 |

**合计 36+3+12+18 = 69 ✅ 零遗漏零重复**（逐 code 复核：C×12、P×13、D×11、V×12、L×8、LC×9、K×4）。
覆盖率口径：**51/69 有绑定或候选 skill（含 3 弱绑定），18/69 无对应 skill**；若仅计强制绑定（含弱）= 39/69。

---

## 3.3 映射原则（本表生成规则，实现与评审均以此为准）

1. **小阶段内同质**：同一小阶段内动作绑定的 skill 保持同一方法族（如 CONCEPT-S2 全部市场研究族、LAUNCH-S2 全部销售赋能族），不混插不相关框架；与主计划 §2 拆解原则①互为约束。
2. **沿官方链路顺序**：绑定与 command 链沿 pm-skills 官方工作流 `discovery → market-research/product-strategy → execution → ai-shipping/go-to-market → data-analytics → 回流 discovery`（S3 L49「Commands flow into each other」；S2 L241-247）推进；前一动作的产出物是后一动作的输入（如 C01/C03 访谈证据 → C06 定位 → P01 PRD → L01 GTM → LC01 数据回流）。
3. **深管绑产出物、轻管只登记**：深管动作优先绑定产出物类 skill（PRD/方案/评审纪要类：`create-prd`、`summarize-meeting`、`strategy-red-team` 等），1-3 个封顶；轻管动作默认不绑定（仅三要素登记），`〔候选〕`条目只做 AI 一句话提示，**不产交付物、不设门禁、不进 Skill 映射表 seed 的强绑定列**（推断的落地口径，待 §6 数据模型确认字段分级）。
4. **command 链只用真名且成链**：只使用 §3.1 实抓的 42 条 command，按「前产出→后输入」串 1-3 步；command 缺位时用对应 skill 名做提示词驱动（S3 L104-112：非 Claude 环境 command 不可直接执行，退化为自然语言调 skill）。
5. **无对应不硬凑**：硬件/认证/治理/算法类动作没有对应 skill 时留空做结构化登记；**pm-toolkit 不做通用兜底**——它是文书工具箱（简历/NDA/隐私政策/校对，S3 L408-410「PM utilities beyond core product work」），除 `privacy-policy`（C12）、`grammar-check`（V07/V12/L06/LC08 文案校对）有明确对应外，不向其他动作摊派。
6. **Gate/评审动作绑定对抗与纪要类 skill**：承载 Gate 的小阶段（C11/P13/D05/L07/LC02）一律绑定 `summarize-meeting`，深管评审前置 `strategy-red-team` 或 `pre-mortem`，保证「会前有质疑、会后有留痕」。
7. **推断即标注**：绑定关系凡非 S3/S2 明示的框架用途外延（如 `shipping-artifacts` 用于 LC09 归档、`user-segmentation` 用于 V10 分人群）均在表内标「推断」，评审时可整条否决不影响其他行。

## 3.4 不确定项清单（无法证明 / 需后补 / 需 owner 裁定）

### 3.4.1 无合适 skill 的动作与替代建议（18 个纯登记 + 3 个弱绑定的定性）

| # | 动作 | 为什么无对应 | 替代建议（结论） |
|---|---|---|---|
| 1 | C09 项目等级评定、LC04 双PM贡献度评定 | 治理/评定决策，pm-skills 无治理评定方法论 | **留空只做结构化登记**（等级+系数/五维度表单+双签审计）。不用 pm-toolkit（不适用），AI 仅做必填项与区间校验提醒 |
| 2 | C10 知识产权与合规预检（FTO） | 无专利检索/FTO skill；`privacy-policy` 只覆盖隐私政策草稿 | **留空+结构化登记**（强制上传检索报告）；`privacy-policy` 仅作 C12 场景，FTO 检索依赖外部工具/人工 |
| 3 | P10 认证与法规清单确认、V02 认证测试送检 | 无认证/法规清单 skill | **留空+结构化登记**（国别认证模板库带出清单、证书编号+通过日期），阻断逻辑全走系统门禁，不靠 AI |
| 4 | D02 首版BOM冻结、D03 手板/EVT、D07 模具T1试模、V05 试产PVT、L05 备货 | 硬件/供应链/制造动作，pm-skills 面向软件产品 | **留空只做登记**；若后续需要，可另寻硬件 IPD 方法论库（本库不适合硬凑） |
| 5 | D11 / Z01 BioCV 算法训练与评测 | 无算法评测 skill | **留空+FAR/FRR 数值字段登记**（S1 L67 例外二口径）；评测方法论走算法国标/内部 SOP，不经 pm-skills |
| 6 | V08 售后与维修方案准备 | 无售后服务设计 skill | **留空只做登记** |
| 7 | D08 开发阶段成本复核 | 成本复核为表单核算，`pricing-strategy` 面向对外定价不匹配 | **留空只做登记**（与 C07 区分：C07 绑定价 skill，D08 不绑） |
| 8 | LC07 生命周期状态维护 | 状态机登记，无方法论 skill | **留空只做结构化状态登记**（在售/限售/停产+变更记录） |
| 9 | V12 海外本地化、L06 系统上架、LC08 停产公告（弱绑定 3 项） | 无本地化/渠道上架/EOL 专项 skill | **弱绑定 `grammar-check`/`value-prop-statements` 仅作文案校对**（推断）+ 结构化验收清单/登记；核心执行不依赖 skill |
| 10 | LC09 项目归档（推断绑定 `shipping-artifacts`） | 归档≠代码文档，属外延用法 | **二选一待定**：a) 保留推断绑定，按文档集骨架组织归档包；b) 降级为纯结构化归档清单。建议 a（归档包即「可审查文档集」，语义贴合），请 owner 拍板 |

**关于 `pm-toolkit` 兜底的明确结论：不采用。** pm-toolkit 是单点文书工具（S2 L202「这组不在主产品链上」），把 `review-resume`/`draft-nda` 摊派给硬件/认证/治理动作会造成「skill 同质性」破坏与虚假绑定；统一按「留空+结构化登记」处理（选项 b：留空登记；本表已按此执行）。

### 3.4.2 事实源间差异（需 owner 裁定，本表已按「S1 为准」执行）

| # | 差异 | 详情 | 影响 |
|---|---|---|---|
| 3.4.2-1 | **动作同码异名（严重）** | 主计划 §2.3 的 D01「智能锁通信协议评审」/ D02「联动场景用例设计」/ D03「固件联调计划」与 S1 的 D01「详细设计（结构/硬件/软件）」/ D02「首版BOM冻结与采购下单」/ D03「手板 / EVT 样机制作」完全不同；§2.2 的 P02「渠道商对接名单确认」vs S1 P02「需求优先级排序与版本规划」 | 本映射名称取 S1（硬约束）；**小阶段归属暂按 §2 的 code 分组**。若 §2 命名才是目标产品（智能锁）口径，则 S1 需升级版本号并重出映射——**必须 owner 拍板，seed 数据不可两存** |
| 3.4.2-2 | 动作简称差异 | §2 中 C02「目标市场与竞品分析」、C08/C09 简称 vs S1 全称 | 低影响，统一 S1 全称 |
| 3.4.2-3 | 深度口径差异 | §2.2 标 P03/P04「深/非阻断」，S1 L110-111 为轻管 | 本映射按 S1 轻管处理（不绑定 skill）；若 §2 为新决策，需回写 S1 或在 §6 标注覆盖规则 |
| 3.4.2-4 | README 插件计数 | pm-ai-shipping 摘要「3 skills」vs 正文「Skills (2)」 | 已用 S4 实证 `code-review` 存在，按 3 计；README 正文为上游陈旧，不影响本表 |
| 3.4.2-5 | SKILL.md 逐文件取证 | 仅实抓 `code-review/SKILL.md` 一个文件；其余 68 个 skill 名基于 S3 README 全量列示 + S2 交叉一致 | 风险低（两独立源逐名一致）；若做 skill 内容级接入（Track C），需后补逐 SKILL.md 抓取与 frontmatter 校验 |
| 3.4.2-6 | command 在 CopilotKit 侧的可执行性 | S3 L104-112：slash command 为 Claude/Cowork 特有，其他运行时需降级为自然语言调 skill | 影响 Track C 的触发实现：本表 command 链在非 Claude 运行时按「skill 名+步骤话术」降级（推断），需在 §4/§6 定契约 |

### 3.4.3 未取到项

**无。** 9 插件 / 69 skills / 42 commands 全部在 S3 实抓到真名并与 S2 交叉一致；不存在「未取到，需后补」的插件。

---

**本分节草稿完。** 待合入主计划 §3 时：① 请 owner 先裁定 §3.4.2-1（D01-D03/P02 同码异名）；② §3.2 表格可直接转 `ipd_action_skill_map` seed（skill/command 列为真名，`绑定/弱/候选/不绑定` 四级可映射为绑定强度字段，推断口径待 §6 确认）。
