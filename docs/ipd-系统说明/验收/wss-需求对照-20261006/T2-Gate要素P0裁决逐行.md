# wss 原始需求 × 系统现状 逐行全量比对台账（T2：Gate 要素 / P0 / 裁决）

> 生成日期：2026-10-07　审计：质量门禁逐行比对，禁止抽查
> 需求源目录：`/Users/mac/Documents/wss/产品流程细化管理工具/`
> 现状源：真库 `ipd_dev`（只读，mysql --defaults-file）+ 后端 `/Users/mac/Documents/ruoyi-ai`（Java/RuoYi）+ 前端 `/Users/mac/Documents/ruoyi-ipd-web`（Vue web-antd）
> 五值判定：一致 / 不一致 / 缺失未实现 / 缺失-已授权退役（回款台账+奖金池+业绩窗口 owner 2026-10-03 授权）/ 过度设计

---

# §A　Gate 评审要素：需求 33 行 × 库 87 行 逐条映射

## A.0 源计数确认（现查）

- 需求文档 `IPD系统_五大Gate评审要素_v1.md`：L208 合计行自述「**33 | 14 | 2 个**」= 33 要素 / 14 否决 / 2 联合 Gate（G1/G5 双签）。
  - 需求大表实际行数：G1 7（L59-65）、G2 6（L89-94）、G3 5（L117-121）、G4 8（L147-154）、G5 7（L178-184）= **33** ✓
  - **需求文档内部矛盾**：按各要素表「否决项」列实际带 ❌ 计数 = G1 5 / G2 **5** / G3 0 / G4 3 / G5 2 = **15**；而 L208 汇总行写 **14**（L204 把 G2 记为 4）。差异点在 **G2-6**：要素表(L94)带 ❌「强制认证缺失或周期冲突」，汇总行(L204)计 4 未含它。
- 真库 `gate_review_elements WHERE del_flag='0'`：**87 行**（G1=25 / G2=20 / G3=10 / G4=16 / G5=16），`is_veto=1` 全量=**38**。
  - 表列名实测为 `element_code` / `element_name` / `is_veto`（任务给出的示例 SQL 用 `code`/`title` 不存在，本审计以实际列名为准）。
  - 按状态：published=33 / archived=41 / draft=13；按编号形态：canonical(`G[1-5]-[1-9]`)=33 / legacy_zero_pad(`G[1-5]-0[1-9]`)=33 / test_probe=21。
  - 否决分布：published=14 / archived=18 / draft=6（合计 38）。
- seed `IpdGateElementSeedInitializer.java`（@Profile("dev")）仅含 **33 条规范编号**（L46-78），自述「33 项 / 否决 5-4-0-3-2=14」（L16-17），**不含**任何零填充码或测试码。

## A.1 需求侧 33 行 → 库匹配（标题语义匹配 + 否决属性比对）

> 匹配目标 = 库中 33 条 published 规范行（create_by=1、version=1、status=published、enabled=1）。全部 33 行标题与需求语义一致；否决属性 32/33 一致，仅 G2-6 不符。

| 需求# | 需求名称 | 需求判定标准(摘要) | 主答 | 需求否决 | 库 code | 库 is_veto | 判定 |
|---|---|---|---|---|---|---|---|
| G1-1 | 市场机会真实性 | ≥5家一手验证或≥1家书面意向 | 市场PM | 无(—) | G1-1 | 0 | 一致 |
| G1-2 | 市场规模与目标设定 | 四项基准值完整(销售额/渠道/NPS/场景) | 市场PM | ❌基准值缺失 | G1-2 | 1 | 一致 |
| G1-3 | 竞争格局与差异化 | ≥3竞品，≥2差异点6月难复制 | 市场PM | 无(—) | G1-3 | 0 | 一致 |
| G1-4 | 技术可行性 | 可行/有条件可行(BioCV FAR/FRR) | 研发PM | ❌结论不可行 | G1-4 | 1 | 一致 |
| G1-5 | 商业性 | 毛利率≥产品线门槛 | 市场PM | ❌毛利率为负 | G1-5 | 1 | 一致 |
| G1-6 | 合规与知识产权 | 认证清单+Z03合规+FTO | 市场PM+研发PM | ❌Z03未过/认证障碍 | G1-6 | 1 | 一致 |
| G1-7 | 资源与组队 | 双PM均确认承接 | 产品组长 | ❌研发PM未到位 | G1-7 | 1 | 一致 |
| G2-1 | PRD完整性 | 条目化100%含验收标准 | 市场PM | ❌PRD未上传 | G2-1 | 1 | 一致 |
| G2-2 | 需求优先级与版本规划 | 首版Must支撑核心场景 | 市场PM | 无(—) | G2-2 | 0 | 一致 |
| G2-3 | 差异化卖点可交付性 | 每卖点研发确认+成本增量 | 研发PM | ❌卖点不可实现 | G2-3 | 1 | 一致 |
| G2-4 | 价值定价与毛利复核 | 毛利率≥门槛 | 市场PM | ❌低于门槛且无调价 | G2-4 | 1 | 一致 |
| G2-5 | 技术方案与里程碑 | P08 EVT/DVT/PVT/GTM 完整 | 研发PM | ❌里程碑缺失 | G2-5 | 1 | 一致 |
| G2-6 | 认证与法规清单确认 | 目标市场强制认证列入且周期匹配 | 研发PM | ❌强制认证缺失/周期冲突 | G2-6 | 0 | **不一致**（需求要素表标 ❌，库 is_veto=0、seed=L58 判非否决「认证周期进行中也可通过」；汇总行 L204=4 与库/seed 一致而与要素表 L94 矛盾） |
| G3-1 | 进度与里程碑 | 偏差≤5工作日或追赶计划 | 研发PM | 无(过程性) | G3-1 | 0 | 一致 |
| G3-2 | 场景完整度 | 核心场景端到端走通 | 市场PM | 无 | G3-2 | 0 | 一致 |
| G3-3 | 需求变更情况 | 累计变更率≤15%(预警12%) | 市场PM | 无 | G3-3 | 0 | 一致 |
| G3-4 | 技术风险与阻塞 | 无P0阻塞否则已升级 | 研发PM | 无 | G3-4 | 0 | 一致 |
| G3-5 | 成本与合规跟踪 | 成本偏差≤5%；认证正常 | 研发PM | 无 | G3-5 | 0 | 一致 |
| G4-1 | 产品就绪 | DVT/V02认证/V03试用/V06量产齐备 | 研发PM | ❌V02未过/量产未过 | G4-1 | 1 | 一致 |
| G4-2 | 质量与缺陷 | P0清零,P1≤3有workaround | 研发PM | ❌P0未清零 | G4-2 | 1 | 一致 |
| G4-3 | 供应与备货 | L05首批量产+备货满足预测 | 研发PM | 无(—) | G4-3 | 0 | 一致 |
| G4-4 | 价格与渠道体系 | 价格发布+渠道覆盖≥60% | 市场PM | 无(—) | G4-4 | 0 | 一致 |
| G4-5 | 销售工具与培训 | 工具包齐备+培训覆盖≥80% | 市场PM | 无(—) | G4-5 | 0 | 一致 |
| G4-6 | 本地化与合规落地 | V07/Z05本地化+国别认证落实 | 市场PM | ❌强制认证未取得/Z05 | G4-6 | 1 | 一致 |
| G4-7 | 售后与支持 | V08售后/备件/话术/退换货就绪 | 市场PM | 无(—) | G4-7 | 0 | 一致 |
| G4-8 | GTM方案可执行性 | 首批≥10客户且分配责任人 | 市场PM | 无(—) | G4-8 | 0 | 一致 |
| G5-1 | 销售达成情况 | 90天累计达成率≥25%(回款,Q2口径) | 市场PM | 无(预警线非否决) | G5-1 | 0 | 一致 |
| G5-2 | 渠道与场景覆盖 | 渠道/场景覆盖各≥50%目标 | 市场PM | 无(—) | G5-2 | 0 | 一致 |
| G5-3 | 客户反馈与质量 | P0问题100%闭环;NPS达标 | 市场PM | ❌P0未闭环 | G5-3 | 1 | 一致 |
| G5-4 | 需求准确率复盘 | 变更率统计+根因归类 | 市场PM | 无(—) | G5-4 | 0 | 一致 |
| G5-5 | 上市准时性与窗口命中 | 偏差计量归因(15/30天) | 市场PM+研发PM | 无(—) | G5-5 | 0 | 一致 |
| G5-6 | 利润与成本复盘 | 毛利偏差≤5pp或已归因 | 市场PM | 无(—) | G5-6 | 0 | 一致 |
| G5-7 | 迭代与生命周期决策 | 明确决议+指定责任人 | 双PM+产品组长 | ❌未形成决议 | G5-7 | 1 | 一致 |

**A.1 小结**：33/33 全部在库中找到对应规范行（无缺失）。一致 32 + 不一致 1（G2-6 否决属性）+ 缺失未实现 0。
（说明：需求「判定标准」文本在 seed 中被压缩改写为 pass_standard，属参数化配置表述，非要素名称/否决属性漂移，本表按任务口径「标题语义匹配 + 否决属性」判定。）

## A.2 库侧未被 33 需求映射的 54 行 → 过度设计 + 溯源

> 87 − 33(published 规范行) = **54 行**未被需求映射，逐行列出。seed 文件（33 条规范码）经全文 Read 确认**不含**以下任何码，故其来源须另溯。

### A.2-a　旧代零填充编号（archived，33 行）— 溯源：旧代 seed

来源证据：这 33 行 remark 空、status=archived、enabled=0、create_by=-1；对应现库 33 条规范行 remark 明示「R219U1软删:旧代seed与新代Initializer双套并存」，即这批 `G[1-5]-0[1-9]` 是**旧代种子编号**，已被规范代（seed `LEGACY_SEED_CODE` 正则识别，L41-42/L82-84）取代归档。现 `IpdGateElementSeedInitializer` SEED_ELEMENTS 用规范码，**不含**这些零填充码。

| # | 库 code | element_name（旧代，与需求名不同） | is_veto | 判定 |
|---|---|---|---|---|
| 1 | G1-01 | 市场机会与用户痛点验证 | 0 | 过度设计 |
| 2 | G1-02 | 商业模式可行性 | 1 | 过度设计 |
| 3 | G1-03 | 技术可行性评估 | 1 | 过度设计 |
| 4 | G1-04 | 竞品与替代方案分析 | 0 | 过度设计 |
| 5 | G1-05 | 初步财务评估 | 1 | 过度设计 |
| 6 | G1-06 | 法规与合规预审 | 0 | 过度设计 |
| 7 | G1-07 | 立项建议书（Charter） | 0 | 过度设计 |
| 8 | G2-01 | 项目计划书与里程碑 | 0 | 过度设计 |
| 9 | G2-02 | 资源预算与人力配置 | 1 | 过度设计 |
| 10 | G2-03 | 项目风险评估与应对 | 0 | 过度设计 |
| 11 | G2-04 | 开发与运营流程 | 0 | 过度设计 |
| 12 | G2-05 | 团队组建与能力盘点 | 0 | 过度设计 |
| 13 | G2-06 | 立项评审决议 | 1 | 过度设计 |
| 14 | G3-01 | 关键功能实现 | 1 | 过度设计 |
| 15 | G3-02 | 系统集成完成度 | 1 | 过度设计 |
| 16 | G3-03 | 内部测试报告 | 0 | 过度设计 |
| 17 | G3-04 | 代码质量与安全审查 | 0 | 过度设计 |
| 18 | G3-05 | 性能基准 | 0 | 过度设计 |
| 19 | G4-01 | UAT 用户验收测试 | 1 | 过度设计 |
| 20 | G4-02 | 安全评估与渗透测试 | 1 | 过度设计 |
| 21 | G4-03 | 性能压力与稳定性 | 0 | 过度设计 |
| 22 | G4-04 | 兼容性验证 | 0 | 过度设计 |
| 23 | G4-05 | 文档完整性 | 0 | 过度设计 |
| 24 | G4-06 | 培训与知识转移 | 0 | 过度设计 |
| 25 | G4-07 | 部署与回滚方案 | 1 | 过度设计 |
| 26 | G4-08 | 上市与运营就绪 | 1 | 过度设计 |
| 27 | G5-01 | 上市发布就绪 | 1 | 过度设计 |
| 28 | G5-02 | 客服与支持体系 | 0 | 过度设计 |
| 29 | G5-03 | 营销与渠道就绪 | 0 | 过度设计 |
| 30 | G5-04 | 运营监控与告警 | 0 | 过度设计 |
| 31 | G5-05 | 应急预案 | 1 | 过度设计 |
| 32 | G5-06 | 监管与法务终审 | 1 | 过度设计 |
| 33 | G5-07 | 上市复盘计划 | 0 | 过度设计 |

（小计 33；G3-01 恰为 seed L26-28 所述「旧编号仍启用会阻断补种」那一类——现库已 archived+enabled=0，故不触发整批中止，补种正常。）

### A.2-b　测试/探针造数据（draft/archived，21 行）— 溯源：运行态测试脚本（seed 不含）

来源证据：create_by=900101 或 remark「造数据 …」，或编码为探针/验收标记；seed SEED_ELEMENTS **不含**这些码，故来源为 dev/CI 运行态造数与探针，非 seed、非需求。

| # | 库 code | element_name | status | is_veto | 来源标记 | 判定 |
|---|---|---|---|---|---|---|
| 34 | DT1790084411 | dt | draft | 1 | create_by=900101 | 过度设计 |
| 35 | ZUI483920 | UI验证测试要素-双签 | draft | 1 | create_by=900101 | 过度设计 |
| 36 | R217PROBE-01 | R217PROBE双否决阈值验证 | draft | 1 | create_by=900101 | 过度设计 |
| 37 | ZCL-095718 | 清空语义验证B | draft | 1 | create_by=900101 | 过度设计 |
| 38 | ZPA-095718 | 两字段HTTP真活验证 | draft | 1 | create_by=900101 | 过度设计 |
| 39 | WP-R239-VERIFY-1 | R239写链路验收要素·已更新 | draft | 0 | create_by=900101 | 过度设计 |
| 40 | WP240V0036 | R239写链路验收要素·已更新 | draft | 0 | create_by=900101 | 过度设计 |
| 41 | L2DIAG2 | diag2 | archived | 1 | create_by=900101 | 过度设计 |
| 42 | L2ET20595 | LANE2 测试要素 | archived | 1 | create_by=900101 | 过度设计 |
| 43 | L2ET21032 | LANE2 测试要素-v2 | archived | 0 | create_by=900101 | 过度设计 |
| 44 | LANE2-DIAG-E1 | diag | archived | 1 | create_by=900101 | 过度设计 |
| 45 | G2-DRAFT-01 | DRAFT 测试 1（disabled） | draft | 0 | remark=造数据 DRAFT-1 | 过度设计 |
| 46 | G2-DRAFT-02 | DRAFT 测试 2（enabled） | draft | 0 | remark=造数据 DRAFT-2 | 过度设计 |
| 47 | G2-DRAFT-03 | DRAFT 测试 3（disabled+veto） | draft | 0 | remark=造数据 DRAFT-3 | 过度设计 |
| 48 | G2-DRAFT-04 | DRAFT 测试 4（enabled+veto） | draft | 0 | remark=造数据 DRAFT-4 | 过度设计 |
| 49 | G2-DRAFT-05 | DRAFT 测试 5（disabled） | draft | 0 | remark=造数据 DRAFT-5 | 过度设计 |
| 50 | G2-ARCH-01 | ARCHIVED 测试 1 | archived | 0 | remark=造数据 ARCH-1 | 过度设计 |
| 51 | G2-ARCH-02 | ARCHIVED 测试 2 | archived | 0 | remark=造数据 ARCH-2 | 过度设计 |
| 52 | G2-ARCH-03 | ARCHIVED 测试 3（veto） | archived | 0 | remark=造数据 ARCH-3 | 过度设计 |
| 53 | ZKDIFF-P102-A | P102验收-ZKDIFF-P102-A | draft | 1 | create_by=900101 | 过度设计 |
| 54 | ZKDIFF-P102-B | P102验收-ZKDIFF-P102-B | archived | 1 | create_by=900101 | 过度设计 |

（小计 21：G1 11 + G2 8 + G5 2；G3/G4 无测试行。均 status≠published 或 enabled≠1，非权威评审集合，但 del_flag='0' 仍计入 87。）

## A.3 对账（33 + 87 闭合）

- 需求侧 33：映射成功 **33**（100%）、缺失未实现 **0**、其中不一致 **1**（G2-6）。
- 库侧 87：被需求映射 **33**（published 规范行）+ 未被映射 **54**（33 legacy 零填充 + 21 测试造数据）= 87 ✓（33+54=87）。
- 编号形态：canonical 33 + legacy_zero_pad 33 + test_probe 21 = 87 ✓。
- 状态：published 33 + archived 41 + draft 13 = 87 ✓；archived 41 = 33 legacy + 8 测试(G2-ARCH×3 + L2DIAG2 + L2ET20595 + L2ET21032 + LANE2-DIAG-E1 + ZKDIFF-P102-B) ✓；draft 13 = 6 G1 + 5 G2-DRAFT + 1 ZKDIFF-P102-A + … 计数：G1 draft=7(DT/ZUI/R217PROBE/ZCL/ZPA/WP-R239/WP240)+G2 draft=5(G2-DRAFT-01..05)+G5 draft=1(ZKDIFF-A) = 13 ✓。
- 否决对账三口径：需求要素表实际 ❌=**15**；需求汇总行 L208 自述=**14**（G2 少计 1，差异在 G2-6）；库 published is_veto=**14**（与汇总/seed 一致，与要素表差 1）；库全量 is_veto=**38**（含 33 legacy 中 18 否决 + 13 draft 中 6 否决，均为过度设计噪声）。
- **结论**：权威评审集合应为 33 published 规范行；87 中 54 行为过度设计（其中旧代 33 + 测试 21）。G2-6 为唯一「需求文档自身矛盾 + 系统采非否决」的实质漂移点。

---

# §B　P0 任务清单（T-01~T-13）+ P0 门（27 条 AC）逐条

## B.0 口径与栈差异前置（现查）

- **技术栈整体错位（最高严重级）**：P0 任务/验收清单按 `NestJS + Prisma + React + PostgreSQL + Redis + MinIO` 编写（`apps/api/prisma/schema.prisma`、`seed.ts`、`pnpm -F api build`、`F:\devtools\*.bat`、`pg_dumpall`）。实测：
  - 后端 = Java/RuoYi 模块 `ruoyi-modules/ruoyi-ipd`（package：security/audit/approval/config/controller/service…），无 `apps/api`、无 `prisma`。
  - 库 = MySQL `ipd_dev`（`SHOW TABLES` 见 audit_logs/bonus_pools/receipt_ledgers/system_configs…），非 PostgreSQL。
  - 前端 = `/Users/mac/Documents/ruoyi-ipd-web`，pnpm monorepo（`apps/web-antd`、`pnpm-workspace.yaml`、`turbo.json`）Vue3 + Ant Design，非 React。
  - 认证 = Sa-Token（`IpdSaTokenBridgeConfig`/`IpdStpInterfaceBridge`），非自研 JWT。
  - 因此凡「文件/表/接口存在性」按功能等价核；凡需运行/篡改/登录态验的判「待验证（需环境）」并附命令。

## B.1 P0 任务 T-01~T-13（逐条）

| 任务 | VERIFY/AC 要求摘要 | 字面(需求栈) | 功能等价(实测证据) | 判定 |
|---|---|---|---|---|
| T-01 monorepo+环境 | pnpm -F api/web build；AC-ENV-01/05 | apps/api(NestJS) 缺 | 前端确为 pnpm workspace：`ruoyi-ipd-web/pnpm-workspace.yaml`+`apps/web-antd`；后端为 Maven Java 模块 | 不一致（栈错位；前端 monorepo 符合、后端非 NestJS） |
| T-02 Prisma 全量表(20) | prisma validate/format；AC-CFG-01 | schema.prisma 缺 | `ipd_dev` 实测表集（SHOW TABLES）覆盖审计/奖金/回款/删除/配置等 | 不一致（非 Prisma；DDL 走 SQL/Flyway 侧），功能等价存在 |
| T-03 migration+seed+revoke | migrate deploy；system_configs 62 行；ipd_app UPDATE audit_logs 被拒；AC-ENV-02/AUD-01/07 | seed.ts/js 缺 | seed 由 `IpdGateElementSeedInitializer`+`IpdMockDataInitializer`+system_configs 承担；`audit_logs` **无 updated_at** ✓（SHOW COLUMNS）；system_configs 现 **61 行**（需求 T-03/§7 写 62）→ 差 1 待核 | 不一致（seed 机制不同）+ 待验证（revoke 需环境；62 vs 61 行数漂移） |
| T-04 统一响应/异常/校验 | 返回值包装;BusinessException→HTTP;AC-ENV-04 | common/response/filters/pipes(NestJS) 缺 | `IpdFirewallResponseConfig`、`IpdPermissionExceptionHandler`、`ApiV1Response` 包装（controller 普遍返回 ApiV1Response） | 一致（功能等价）|
| T-05 参数服务零硬编码 | 改参数即生效不重启;全局搜 1000/1500/0.05/1.5 零命中;AC-CFG-01/02/03 | parameter.service.ts 缺 | `SystemConfigController`/`BusinessConfigController` + `system_configs`（61 键，系数全在表内：bonus.* 等） | 待验证（需环境跑 AC-CFG-01 全局字面量检索 + CFG-02/03 热生效）；静态：系数存表未硬编码（见 B.2） |
| T-06 审计 @Audit+hash链 | 连写3条链可校验;篡改报断裂;AC-AUD-02/03/07 | common/audit(NestJS) 缺 | `IpdAudit`+`IpdAuditAspect`+`AuditHashChain`+`AuditChainHead`+`AuditChainIntegrityScheduler`+表 `audit_log_chain_heads`/`audit_logs` | 一致（功能等价存在）；链校验断裂检测待环境 |
| T-07 JWT+RBAC+数据域 | 改密前2xxxx;越权3xxxx无变更;AC-AUTH-01~11 | modules/auth(NestJS+JWT) 缺 | `security/`(IpdAuthSession/IpdPermission/IpdRolePermissionCatalog/IpdIdorGuard/IpdStpInterfaceBridge)+`IpdAuthController`+`IpdSaTokenBridgeConfig`（Sa-Token 非 JWT） | 不一致（认证体系为 Sa-Token 非 JWT）+ 越权集成测试待环境 |
| T-08 字典接口 | 返回全部分组;前端类型同步;AC-CFG-01 | modules/dict 缺 | 表 `sys_dict_data`/`sys_dict_type` + 前端 `apps/web-antd/src/api/system/dict` | 一致（功能等价）|
| T-09 前端骨架 | web build 通过;断网友好错误;AC-ENV-04 | apps/web(React) 缺 | `apps/web-antd`（Vue3+AntD）路由/请求封装/布局 | 一致（功能等价，React→Vue）|
| T-10 登录/强制改密/企微Mock/登出 | 手测新账号→强制改密→首页;AC-AUTH-01/02/04/05/07 | pages/auth(React) 缺 | `views/ipd/auth`+`views/_core/authentication` | 功能存在；强制改密/企微Mock 运行验收待环境 |
| T-11 通用删除审核引擎 | 两级全通过置deletedAt;超24h不可撤回;AC-DEL-01~08 | modules/deletion(NestJS) 缺 | `DeletionRequestController`+`AdminPermanentDeleteController`+`approval/ApprovalGuardSupport`+表 `deletion_requests`（leader_*/admin_* 两级+due 列）+配置 `deletion.leaderDeadlineDays=2`/`adminDeadlineDays=2`/`withdrawHours=24` | 一致（功能等价，期限符 owner F29=2工作日）|
| T-12 删除审核页面 | 发起→组长→超管→归档;AC-DEL-01~08 | pages/deletion(React) 缺 | `views/ipd/deletion/{my-requests,review,archive}`+`api/ipd/deletion.ts`（+ deletion.test.ts） | 一致（功能等价）|
| T-13 审计查询页+hash校验接口 | 篡改→接口报断裂索引;分层导出;AC-AUD-02~06 | verify.controller+pages/audit | `AuditLogController` `GET /api/v1/audit-logs/verify`(L82)+`/export`(L97)+`/scope`(L118)；前端 `api/ipd/audit.ts`+`views/ipd/project/detail/audit.vue` | 一致（接口存在）；verify 实际校验待环境 |

## B.2 P0 门 27 条 AC（逐条，文件为准）

> 源 `IPD系统_验收清单.md` P0 节 L26-73，实际 27 条 = ENV 6 + AUTH 11 + AUD 7 + CFG 3。

| AC | 要求摘要 | 判定 | 证据 / 验证命令 |
|---|---|---|---|
| AC-ENV-01 | start-dev-env.bat 启 PG5432/Redis6379/MinIO9000 | 不一致（栈：无 PG/MinIO，实为 MySQL）+ 待环境 | `mysql --defaults-file=… -e "SELECT 1"` 验库连通 |
| AC-ENV-02 | migration+seed 建表+默认超管/产品组/参数/角色字典 | 待环境 | `mvn -pl ruoyi-modules/ruoyi-ipd -am test`；库表见 SHOW TABLES |
| AC-ENV-03 | pgdata 备份/恢复 | 不一致（无 pgdata，MySQL）| — |
| AC-ENV-04 | 断 API 前端不白屏显错 | 待环境 | 起前端 `pnpm dev` 断后端 |
| AC-ENV-05 | 代码内无开发期 `docker compose up` 指令 | 一致 | Grep java main `docker compose up`=0 命中 |
| AC-ENV-06 | 无 Redis 6.2+ 命令(ZRANGESTORE/GETDEL/COPY/SINTERCARD) | 一致 | Grep main：ZRANGESTORE/GETDEL/SINTERCARD 0；`.copy(` 仅 `Files.copy`(java NIO，非 Redis)/`GateElementService.copy`(业务复制)，非 Redis COPY |
| AC-AUTH-01 | 超管独立凭证登录(非PM姓名) | 一致(存在)+待环境 | `config/IpdProdAdminBootstrap.java` |
| AC-AUTH-02 | 默认密码首登强制改密拦截 | 待环境 | 见 IpdAuthController |
| AC-AUTH-03 | 改密后新密码成/旧密码败并写审计 | 待环境 | — |
| AC-AUTH-04 | 企微Mock扫码(已绑)签发令牌 | 待环境 | — |
| AC-AUTH-05 | 企微扫码(未绑)拒绝 | 待环境 | — |
| AC-AUTH-06 | 离职账号拒登+解绑写审计 | 待环境 | — |
| AC-AUTH-07 | Token过期→2xxxx→跳登录 | 待环境 | — |
| AC-AUTH-08 | 普通PM调超管接口→3xxxx无变更 | 待环境(越权集成测试) | `security/IpdIdorGuard`/`IpdRolePermissionCatalog` |
| AC-AUTH-09 | 普通PM查看本组他人项目→3xxxx/无数据 | 待环境 | 同上 |
| AC-AUTH-10 | 组长查看本组任意项目 | 待环境 | — |
| AC-AUTH-11 | 跨组组长编辑他组→可查看不可编辑 | 待环境 | — |
| AC-AUD-01 | ipd_app 身份 UPDATE audit_logs 被拒 | 待环境(需 MySQL 收权账号) | 库为 MySQL 非 PG，无 `REVOKE … FROM ipd_app` 等价路径可现核 |
| AC-AUD-02 | `/api/v1/audit-logs/verify` 通过 | 一致(端点)+待环境 | `AuditLogController.java:82` verify() |
| AC-AUD-03 | 篡改日志后再校验报断裂 | 待环境 | `AuditHashChain`/`AuditChainIntegrityScheduler` |
| AC-AUD-04 | 普通PM仅导出本人相关 | 待环境 | `AuditLogController:/export`(L97)+`/scope`(L118) |
| AC-AUD-05 | 组长本组/超管全局导出 | 待环境 | 同上 |
| AC-AUD-06 | 游客访问审计→2xxxx无数据 | 待环境 | — |
| AC-AUD-07 | 审计表无 updated_at | 一致 | SHOW COLUMNS audit_logs LIKE 'updated_at' = 空 |
| AC-CFG-01 | 无硬编码系数(1000/1500/0.05/1.5/7/2) | 待环境(全局字面量检索) | 静态：bonus.*/gate.* 系数均在 system_configs |
| AC-CFG-02 | 改双签期限=3天后新建Gate期限3天 | 待环境 | `gate.signDeadlineDays=3`（现值即 3）|
| AC-CFG-03 | 改参数立即生效不重启 | 待环境 | ParameterService 缓存失效（前端 api/system/config）|

**B.3 P0 对账（13 与 27 口径差异说明）**

- **27** = 验收清单 P0 节 AC 条目数（ENV6+AUTH11+AUD7+CFG3），本次实测确认文件为 27 条，与文件 L335「27 条逐条打勾」及 L390 结论模板「P0 底座 | 27」一致。
- **13** = 任务清单 T-01~T-13 的**任务数**，非门条目数。此前总表「4/13 全绿（T-09/10/11/13）」是以任务为分母的完成度，与「27 条 AC 门」是两个不同度量维度，不可互比。
- 本次静态可判定：一致 8（T-04/T-06/T-08/T-09/T-11/T-12/T-13/ + AC-ENV-05/06/07(AUD-07)/AUTH-01端点/CFG 系数存表…），不一致 5（T-01/T-02/T-03/T-07 技术栈错位；AC-ENV-01/03 无 PG/MinIO），缺失未实现 0，其余运行时 AC（AUTH 绝大多数、AUD 篡改类、CFG 热生效类）判待验证（需环境）。
- 新增漂移发现：**system_configs 现 61 行 vs 需求 T-03/§7「62 键」差 1**——须现核是哪一键缺失或已合并。

---

# §C　冲突裁决与最终待确认 逐条（矛盾 A/B/C + Q1~Q6，9 段）

> 源 `IPD系统_冲突裁决与最终待确认清单.md`（状态更新表 L13-20 + 矛盾A/B/C L60-108 + Q1~Q6 L135-180）+ `IPD系统_待确认决策表_v2.md`（H 节 L122-127、E 节 L88-89）。系数配置实测落 `system_configs`（**任务所述 `config_kv` 表不存在**）。

| # | 矛盾/问题 | 需求结论（owner 裁决） | 系统现状（证据） | 判定 |
|---|---|---|---|---|
| 矛盾A | 奖金池基数(=Q1) | **目标销售额**（§4.3 唯一算例）；改口径改 `bonus.poolBase` | `system_configs.bonus.poolBase=TARGET_SALES`（与裁决一致）；`bonus_pools` 表含 `target_sales/pool_rate/base_pool/final_pool` 列、174 行；但 main grep `poolBase`=**0 处 Service 读取** | 缺失-已授权退役（计算层随「算钱层」下线）；配置层与裁决一致 |
| 矛盾B | 回款概念缺失(=Q2) | owner 原文「**按照回款计算**」（L16 状态更新/L123）；但同表 E24(L88) 锁定「**回款口径作废，统一用销售额/出货量**」——文档内部互斥 | `receipt_ledgers` 表在（28 行）但写/算路径已停：`IpdEntityType.java:20-26`「回款台账随算钱层下线，常量刻意保留（历史49条审计）」；`RecoveryWarningService.java:93`「90日回款预警扫描已停用」；守卫测试 `ProjectAgentBusinessDeliveryCatalogTest:60 retainedSalesKpiDoesNotResurrectReceiptLedger`；`system_configs.bonus.salesSource=RECEIPT`（残留） | 缺失-已授权退役（回款台账，owner 2026-10-03）；⚠️ 且需求 Q2(回款) 与 E24(作废) 自相矛盾，系统采 E24+退役方向 |
| 矛盾C | 绩效系数取季(=Q3) | **项目维度**：评定概念→发布阶段完成情况（L17/L124 owner 原文） | `system_configs.bonus.performanceScoreStrategy=PROJECT_SCORE` | 一致 |
| Q1 | 奖金池基数 | 目标销售额 | 见矛盾A：poolBase=TARGET_SALES 配置在、计算层退役 | 缺失-已授权退役（计算层）|
| Q2 | 销售额取数口径 | 回款（⚠️文档已注 V3.1 无「回款」，E24 又作废回款） | 见矛盾B：salesSource=RECEIPT 残留、台账已退役 | 缺失-已授权退役 |
| Q3 | 绩效系数取值 | 项目维度（非季度加权，覆盖 E节建议「各季度加权」） | performanceScoreStrategy=PROJECT_SCORE | 一致 |
| Q4 | S/B 系数定值主体 | **产品组长**（直接上级=组长；G1 双PM提议+组长确认）；区间 S1.5-2.0/A1.0/B0.6-0.8 | `bonus.coefficientDecider=G1_DUAL_SIGN`（记为 G1 双签，未显式编码「组长确认」第三方）；`bonus.coefficientRange.S=1.5-2.0`/`B=0.6-0.8`/`coefficient.A=1.0`/`S=1.5`/`B=0.8` | 不一致（定值主体口径：配置=G1_DUAL_SIGN 双签 vs 需求=产品组长确认；系数区间/初始值与 V3.1 修正后一致，§0② E21/E22 作废已落地：A=1.0 非 1.2）|
| Q5 | 一产品多项目池分摊 | **不分摊**（一个产品=一个项目 1:1） | `bonus.multiProjectSplit=NONE` | 一致 |
| Q6 | 「上市」判定事件 | **L08 动作录入的上市日期**（双签不可改） | `bonus.launchAnchor=L08_ACTION` | 一致 |

**C 对账**：9 段（A/B/C + Q1~Q6，其中 Q1/Q2/Q3 与矛盾 A/B/C 为同一决策的两处表述，已并列去重计数）。判定分布：一致 4（C/Q3/Q5/Q6）、不一致 1（Q4）、缺失-已授权退役 4（A/Q1、B/Q2、Q1、Q2）。全部有 SQL/Grep 证据；关键副作用——`bonus.salesSource=RECEIPT`、`receipt_ledgers`(28 行)、`bonus_pools`(174 行) 三处「算钱层」残留在库，与 owner 2026-10-03 退役授权并存，属退役未清理态。

---

# 末节　本次全部查询命令清单（可复算）

```bash
CNF=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf
# A 源确认
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT gate_code,COUNT(*) FROM gate_review_elements WHERE del_flag='0' GROUP BY gate_code;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT COUNT(*) FROM gate_review_elements WHERE del_flag='0' AND is_veto=1;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW COLUMNS FROM gate_review_elements;"
mysql --defaults-file="$CNF" ipd_dev -N --raw -e "SELECT gate_code,element_code,element_name,is_veto,status,enabled,version,IFNULL(create_by,'-'),IFNULL(LEFT(remark,40),'-') FROM gate_review_elements WHERE del_flag='0' ORDER BY gate_code,sort_order,element_code;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT status,COUNT(*),SUM(is_veto=1) FROM gate_review_elements WHERE del_flag='0' GROUP BY status;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT CASE WHEN element_code REGEXP '^G[1-5]-[1-9]$' THEN 'canonical' WHEN element_code REGEXP '^G[1-5]-0[1-9]$' THEN 'legacy_zero_pad' ELSE 'test_probe' END kind,COUNT(*) FROM gate_review_elements WHERE del_flag='0' GROUP BY kind;"
# B 审计/删除/配置
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW COLUMNS FROM audit_logs LIKE 'updated_at';"
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW COLUMNS FROM deletion_requests;"
mysql --defaults-file="$CNF" ipd_dev -N --raw -e "SELECT config_key,config_value FROM system_configs WHERE del_flag='0' AND (config_key LIKE '%deletion%' OR config_key LIKE '%reviewDays%');"
# C 系数/退役
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW TABLES LIKE '%config%';"
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW TABLES;"
mysql --defaults-file="$CNF" ipd_dev -N --raw -e "SELECT config_key,LEFT(config_value,50) FROM system_configs WHERE del_flag='0' AND (config_key LIKE '%bonus%' OR config_key LIKE '%gate%') ORDER BY config_key;"
mysql --defaults-file="$CNF" ipd_dev -N --raw -e "SELECT config_key,LEFT(config_value,60) FROM system_configs WHERE del_flag='0' AND (config_key LIKE '%poolBase%' OR config_key LIKE '%bonus.pool%');"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT COUNT(*) FROM system_configs WHERE del_flag='0';"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT COUNT(*) FROM receipt_ledgers;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SHOW COLUMNS FROM bonus_pools;"
mysql --defaults-file="$CNF" ipd_dev -N -e "SELECT COUNT(*) FROM bonus_pools;"
mysql --defaults-file="$CNF" ipd_dev -N --raw -e "SELECT config_value FROM system_configs WHERE config_key='bonus.achievementTiers' AND del_flag='0';"
# 代码/结构 溯源（后端）
ls ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/{security,audit,approval,config,controller,service}
# grep（后端，排除 .git/.codex/.harness/target）
grep -rn --exclude-dir=.git --exclude-dir=.codex --exclude-dir=.harness --exclude-dir=target -i "ReceiptLedger" ruoyi-modules/ruoyi-ipd
grep -rn --exclude-dir=.git --exclude-dir=.codex --exclude-dir=.harness --exclude-dir=target "poolBase\|PoolBase" ruoyi-modules/ruoyi-ipd/src/main
grep -rn --exclude-dir=.git --exclude-dir=.codex --exclude-dir=.harness --exclude-dir=target "ZRANGESTORE\|GETDEL\|SINTERCARD\|docker compose up" ruoyi-modules/ruoyi-ipd/src/main
grep -n "Mapping\|verify" ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/AuditLogController.java
# 前端（排除 node_modules/dist/.turbo）
find /Users/mac/Documents/ruoyi-ipd-web/apps -type d | grep -viE "node_modules|dist|.turbo" | grep -iE "ipd|deletion|auth|audit|config"
# 待环境验证命令
cd /Users/mac/Documents/ruoyi-ai/microservices 2>/dev/null && mvn -pl ruoyi-modules/ruoyi-ipd -am test
```
