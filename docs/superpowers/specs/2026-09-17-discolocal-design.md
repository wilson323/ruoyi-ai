# DisCo-Local: 把现有 IPD Skill 改造为 DisCo 形态 —— 设计文档

**作者**：主协调会话 · **日期**：2026-09-17 · **状态**：approved · **目标**：把 `.claude/skills/gen-test` 改造为 DisCo 形态（SKILL.md 入口 + references/ 按需披露 + scripts/verify.sh 动态断言），并沉淀一份《IPD skill 改造模板》供其他 10 个 skill 复用；选 3 张历史卡片做无技能 vs 有技能回放，量化提升。

> 注：本 spec 由 brainstorming skill 流程产出（用户已批准）。实施走 writing-plans 出的独立 plan。

---

## 1. 背景与目标

### 1.1 智源 DisCo 方法论核心

参考论文 `arXiv:2609.02749`（Repo-To-Skill: Distilling GitHub Repositories Into AI4AI Skills）。核心思想：

- **载体**：用 SKILL.md + references/ + scripts/ 这种"插上就能用"的知识包，把仓库知识蒸馏成可复用技能。
- **渐进披露**：Agent 先只读每条技能摘要，需要时才往下展开 references/，上下文不爆炸。
- **蒸馏流水线**：圈能力 → 收集证据 → 构建技能图 → 验证；验证不通过的不入库。
- **验证分两层**：动态（跑断言/示例/冒烟）+ 静态（元数据/链接/溯源）。

### 1.2 当前项目差距（与 DisCo 对照）

| 维度 | DisCo 形态 | 本项目现状 | 差距 |
|------|-----------|-----------|------|
| 技能结构 | SKILL.md + references/ + scripts/ | 单文件 SKILL.md（4 个 IPD skill + 7 个 ipd-guard） | 缺渐进披露分支 |
| 验证 | 动态断言 + 静态检查 | 部分有断言（如 gen-test 段落） | 缺独立 verify 脚本 |
| 路由 | 任务驱动渐进披露 | Skill tool 静态触发 | 缺任务→技能映射（不在本次范围） |
| 基准 | MLE-bench 等四项对照 | 无量化对照 | 缺可量化数据 |

### 1.3 目标（在范围内）

1. **示范改造**：把 `.claude/skills/gen-test` 升级为 DisCo 形态
2. **模板沉淀**：产出一份 `IPD-SKILL-DISCO-TEMPLATE.md`，其他 10 个 skill 可照搬
3. **基准量化**：选 3 张历史卡做无技能 vs 有技能回放，记录 4 维度数据
4. **套用验证**：拿 db-migration 套模板做一遍，证明模板可用

### 1.4 非目标（明确排除）

- 不一次性改造全部 11 个 skill（仅示范 + 1 个模板套用）
- 不起 ipd-skill-router（任务→技能路由）
- 不动 ruoyi-ipd-web 前端
- 不新建独立 skill 库目录（沿用现有 `.claude/skills/`）

---

## 2. 架构

### 2.1 目录结构（最终落地形态）

```
.claude/skills/gen-test/                       ← 改造目标（示范）
├── SKILL.md                                   ← 入口（精简到 < 100 行）
├── references/                                ← 按需披露（按"踩坑形态"分文件）
│   ├── red-baseline-rules.md                  ← 红名单基线规则
│   ├── mock-validity-3-types.md               ← 假绿三形态
│   ├── tag-filtering-rules.md                 ← Surefire profiles.active 过滤
│   ├── tenant-and-permission-rules.md          ← 多租户 + 权限注解
│   └── known-dead-ends.md                     ← 已知假路登记
├── scripts/                                   ← 动态验证
│   ├── verify.sh                              ← 主入口（自证能红）
│   ├── red-scan.sh                            ← 扫红名单
│   ├── mock-drift-check.sh                    ← Mock 合法性自检
│   └── env-probe.sh                           ← 跑前环境探测
└── examples/                                  ← 可复制示例
    ├── service-test-template.java             ← 标准 Service 单测
    ├── controller-test-template.java          ← 标准 Controller 单测
    └── integration-test-template.java         ← 标准集成测试

.claude/skills/_templates/                     ← 新建：模板库
├── IPD-SKILL-DISCO-TEMPLATE.md                ← 其他 skill 改造照抄
└── README.md                                  ← 模板使用说明

docs/superpowers/specs/                        ← 设计文档
├── 2026-09-17-discolocal-design.md            ← 本文档
├── 2026-09-17-baseline-replay-plan.md         ← 基准回放计划
└── 2026-09-17-baseline-replay-results.md      ← 回放结果（实施后产出）

.claude/skills/_templates/_TEMPLATE-EXAMPLE/   ← 用 db-migration 做模板套用验证
└── (按模板产出)
```

### 2.2 SKILL.md 入口设计原则

- 总行数 ≤ 100 行（含 YAML frontmatter）
- 只写"是什么 / 何时用 / 入口路由 / 怎么调 verify / 输出什么"
- 详细内容按踩坑形态指向 references/
- 禁止清单精简到 5 条以内（详细理由下沉 references/）

### 2.3 references/ 内容组织原则

**按"踩坑形态"分文件，不按"功能模块"**（DisCo 渐进披露原则）：

- `red-baseline-rules.md`：什么算红名单（按空格分割而非 #、时钟注入例外等）
- `mock-validity-3-types.md`：假绿三形态（断言改现状 / Mock 不真数据 / 跑挂用例）
- `tag-filtering-rules.md`：Surefire profiles.active 过滤机制与陷阱
- `tenant-and-permission-rules.md`：多租户过滤 + 权限注解必须覆盖维度
- `known-dead-ends.md`：登记项目里已知的假路/不可跑设设设

每篇 reference 文件结构：
```
## 是什么（一句话）
## 为什么踩坑（根因）
## 怎么识别（症状）
## 怎么修（fix pattern）
## 验证（跑哪个 verify 脚本）
## 来源（哪条记录到指出的）
```

### 2.4 scripts/verify.sh 设计原则

**自证能红优先**（参考 `.claude/helpers/sensitive-field-guard.cjs`）：

1. verify.sh 跑前必须能临时改一处错 → 跑出红 → 还原 → 跑绿
2. 验证分两层：动态（跑通 env-probe → red-scan → mock-drift-check）+ 静态（元数据/链接/路径泄漏）
3. 任一断言 fail 即归因三类：
   - **知识自身错**：修复 SKILL.md/references/，定点重跑
   - **环境跑不通**：写明环境约束，标记 WARNING
   - **检查不安全**：跳过或换其他检查方式
4. 与现有 7 个 ipd-guard 分工：ipd-guard-* 是 CI/手动门禁（强制拦截），verify.sh 是 skill 自带自检（Agent 拿到后自动跑）。两者互补，verify.sh 内部调用 ipd-guard-* 逻辑避免脚本复制。

### 2.5 examples/ 设计原则

- 每个示例可直接复制到目标 Service/Controller 测试目录
- 每个示例在 verify.sh 中跑通（至少编译过 + 一个 smoke assertion 通过）
- 示例文件 frontmatter 标注：适用场景 / 关键 funs / 引用哪个 reference

---

## 3. 关键设计决策

### D1：为什么入口要 ≤ 100 行？

DisCo 论文实测：单条技能开头摘要越大，Agent 越容易一次读全文，反而挤爆上下文。100 行 ≈ 一次屏幕可读，保证 Agent 只看入口就能路由。

### D2：references/ 为什么按踩坑形态分，不按功能模块分？

Agent 接到"生成 Service 单测"任务时，会先扫入口 → 跳到 references/red-baseline-rules.md（最常见坑）。如果按功能模块分（service-template.md / mock-template.md），Agent 不知道该先读哪个。按形态分 = 按"我会遇到什么错"分，符合任务驱动路径。

### D3：为什么不让 verify.sh 替代现有 ipd-guard-*？

ipd-guard-* 是项目级强制门禁（CI 拦截），verify.sh 是 skill 级自检（Agent 拿到 skill 主动跑）。两者粒度不同，verify.sh 失败不阻塞其他 skill 使用，pooled cargo gate 失败要阻塞提交。互补不重复。

### D4：为什么选 gen-test 做示范？

gen-test 直接关系到项目最严重的假绿/假红根因（CLAUDE.md 提到"85 跑 71 Error 中 ≥66 为假红"）。改造成果最能看、最能被验证。其他 skill 改完后也能复用 gen-test 的 scripts/verify.sh 模式。

### D5：为什么套 db-migration 而非其他 ipd-guard？

db-migration 与 gen-test 同属"项目级规范"，有完整 DDL apply 验证链路（p1-ddl-apply-check.py），套用模板后跑得通概率高。ipd-guard-* 多是 hook 脚本，形态与 gen-test 不一致，套用价值低。

### D6：基准回放选哪 3 张卡？

候选池（按 CLAUDE.md bug-magnet 排序）：
- P3-8.1 negative-feedbacks 实现漏洞（service 缺字段自动赋值）—— 假路恒空型
- 任意一张 P3.* Mock 修断言型
- 任意一张 ipd-guard 类新报（门禁自证型）

具体哪 3 张在 `2026-09-17-baseline-replay-plan.md` 写实施计划时定，原则：
- 一类：根因是"知识层缺失"（mock 不合法、tag 漏标）
- 二类：根因是"环境配置"（Maven/JDK/Profile）
- 三类：根因是"契约对齐"（前后端接口/字段）

---

## 4. 数据流与接口

### 4.1 研究模式触发流（Agent 执行任务时）

```
Agent 收到任务（如"给新 Service 补单测"）
  ↓
扫所有 skill 的 SKILL.md 入口（description 字段路由）
  ↓
命中 gen-test → 加载 SKILL.md（< 100 行）
  ↓
SKILL.md 指向 references/ 按踩坑形态分文件
  ↓
Agent 按当前场景打开对应 reference（如 red-baseline-rules.md）
  ↓
Agent 准备开干前 → 调用 scripts/verify.sh 自检环境/工具就位
  ↓
生成代码 → 跑 mvn test -pl <module> -Dtest=<ClassName>
  ↓
失败 → 回到对应 reference 看 fix pattern
  ↓
成功 → 输出交付物清单（文件路径 / 跑测命令 / 覆盖缺口）
```

### 4.2 创作模式触发流（distill 新 skill 时）

```
收到"蒸馏新 skill"指令
  ↓
圈能力：开工前列"跑通它需要哪些能力"
  ↓
收集证据：grep 仓库源码 + 读 README + 跑示例
  ↓
构建技能图：入口 + references/ + scripts/ + examples/
  ↓
跑 verify.sh：动态断言 + 静态检查
  ↓
失败归因：知识错 / 环境错 / 检查不安全
  ↓
知识错 → 定点修复 → 重跑
  ↓
通过 → 入库 + 在 SKILL.md 顶部加 [verified YYYY-MM-DD]
```

---

## 5. 错误处理与回滚

### 5.1 失败归因三类

按 DisCo 论文：

1. **知识自身错**：SKILL.md 或 references/ 写错 → 定点修复 → 重跑 verify.sh
2. **环境跑不通**：JDK/Maven/Profile/网络问题 → 在 SKILL.md 顶部加 WARNING，标环境约束
3. **检查不安全**：verify 脚本本身跑挂 → 跳过或换其他检查方式

### 5.2 隔离实施

按 AGENTS.md 推荐：改造过程先在隔离 worktree 做，跑通后切回主分支落地。理由：多会话共工工作树，并发 `-am` 会假红；隔离 worktree 不污染主分支。

### 5.3 回滚路径

- verify.sh 失败：修复知识层，不删除 skill，只在 SKILL.md 顶部加 WARNING
- 模板套用失败：保留 db-migration 原状，模板加注释说明限制
- 基准回放数据异常：保留原始输出，按归因三类分析，不强行翻卡

---

## 6. 测试与验收

### 6.1 自证能红（每个 verify.sh 上岗前必跑）

```bash
# 1. 临时改一处错 → 跑出红
sed -i 's/正确断言/错误断言/' scripts/verify.sh
./scripts/verify.sh  # 应 exit 1
# 2. 还原 → 跑绿
sed -i 's/错误断言/正确断言/' scripts/verify.sh
./scripts/verify.sh  # 应 exit 0
```

### 6.2 基准回放数据格式

每张卡 4 维度数据：

```yaml
card_id: P3-XXXX
without_skill:
  completion: yes/no
  retry_count: N
  fail_distribution: {知识错: x, 环境错: y, 检查不安全: z}
  context_tokens: N
with_skill:
  completion: yes/no
  retry_count: N
  fail_distribution: {知识错: x, 环境错: y, 检查不安全: z}
  context_tokens: N
delta:
  retry_reduction: %
  context_overhead: %
  fail_quality: better/worse/same
```

### 6.3 模板套用验收

- 拿 db-migration 套模板做一遍
- 跑通 scripts/verify.sh
- 与原 SKILL.md 对比：内容覆盖度 ≥ 80%，结构合规

### 6.4 回归

- 原有 7 个 ipd-guard 行为不变（跑一次确认）
- 4 个 IPD skill 中未改造的 3 个（ai-module-add / api-contract / db-migration 其中 db-migration 套用）不受影响

---

## 7. 实施计划（高层概览，细节走 writing-plans）

按用户的"立即完整执行"指令，实施按以下顺序：

```
阶段 1：环境准备（10 分钟）
  - 创建隔离 worktree（feature/disco-gen-test）
  - 备份原 gen-test/SKILL.md

阶段 2：示范改造（2-3 小时）
  - 写新 SKILL.md（精简到 ≤ 100 行）
  - 写 5 个 references/*.md
  - 写 scripts/verify.sh + 3 个子脚本
  - 写 3 个 examples/*.java
  - 跑自证能红 + 真活验证

阶段 3：模板沉淀（1 小时）
  - 写 IPD-SKILL-DISCO-TEMPLATE.md
  - 写 _templates/README.md

阶段 4：模板套用（1-2 小时）
  - 拿 db-migration 套模板
  - 跑通 verify.sh
  - 套用验收

阶段 5：基准回放（3-4 小时）
  - 选 3 张历史卡（按 D6 原则）
  - 无技能 vs 有技能各跑一次
  - 记录 4 维度数据
  - 写结果文档

阶段 6：收口（30 分钟）
  - git add + commit
  - SSOT 镜像 + log.md 登记
  - 看板翻卡（如有对应卡）
  - 写实施总结
```

---

## 8. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| 多会话共工工作树污染 | 高 | 隔离 worktree + 错峰构建（单模块、不带 `-am` 不带 `clean`） |
| OPS-09 单写者约束冲突 | 中 | 主协调会话串行写，其他会话只做只读探针 |
| gen-test verify.sh 失败率高 | 中 | 自证能红优先 + 失败归因三类明确 |
| 基准回放数据波动大 | 低 | 4 维度量化 + 不强行翻卡，只交付数据 |
| 模板套用后 db-migration 现有行为破坏 | 中 | 保留原 SKILL.md，先并列放置新结构，跑通后切 |
| 假绿（绿的是现状不是契约） | 高 | verify.sh 上岗前必跑"自证能红"，每个断言独立可验 |

---

## 9. 范围声明

- **本 spec 范围**：gen-test 改造 + 模板沉淀 + db-migration 套用 + 3 张历史卡基准回放
- **本次不做**：其他 10 个 skill 批量改造、ipd-skill-router、前端蒸馏、独立 skill 库
- **本次要交付**：
  1. `.claude/skills/gen-test/` DisCo 形态完整目录
  2. `.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md`
  3. db-migration 套用结果
  4. `2026-09-17-baseline-replay-results.md`（量化数据）
  5. 设计文档（本文件）+ baseline-replay-plan + 实现记录

---

## 10. 来源与参考

- DisCo 论文：`https://arxiv.org/abs/2609.02749`
- AREX-Skill 仓库：`https://github.com/VectorSpaceLab/AREX-Skill`
- 项目记忆：`DisCo Repo-to-Skill 方法论：蒸馏仓库为可验证技能并应用`（2026-09-11）
- CLAUDE.md §自动化栈：4 个项目 skill + 7 个 ipd-guard 现状
- AGENTS.md：构建/测试纪律、OPS-09、五必现查规约

---

## 11. 自审结果（写后自审）

按 brainstorming skill 要求：

1. **占位扫描**：无 TBD/TODO，所有目标已明确。
2. **内部一致性**：架构（§2）与设计决策（§3）一致；数据流（§4）与错误处理（§5）一致。
3. **范围检查**：聚焦单示范 + 1 模板套用 + 3 张基准卡，复杂度合适。
4. **歧义检查**：所有"应该"改成"必须"/"按"以减少解读空间；"如需要"标注为可选。

无问题，本 spec 已自审通过，用户已批准（"立即完整执行"），进入 writing-plans → 实施。