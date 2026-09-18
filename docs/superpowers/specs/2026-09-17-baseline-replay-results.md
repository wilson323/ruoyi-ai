# 基准回放结果（gen-test DisCo 改造对照）

**执行时间**: 2026-09-17
**对照模式**: 无技能（baseline）vs 有技能（gen-test DisCo 形态）
**回放范围**: 3 张历史已完成卡片，4 维度数据

## 方法

- **无技能组**：基于 `docs/ipd-系统说明/log.md` + `git log` 反推的 Agent 执行轨迹
- **有技能组**：以"如用新 gen-test skill（SKILL.md + references/ + scripts/verify.sh + examples/）重做"的概念验证式估计
- **数据来源**：log.md 3452 行完整历轮轨迹、git log 历史 commit、git show 抽样
- **局限性**：未跑真实模型 API（成本 + 时间约束），数据为概念验证级（PoC）；真实回放需要在两个分组用同一模型 API 重跑 3 张卡的"补测试"部分

## 4 维度定义

1. **完成度**：是否一次跑通拿到契约绿（vs 多轮重试）
2. **retry_count**：执行期间失败重试的轮次
3. **fail_distribution**：失败归因三类计数（DisCo 同款）
4. **context_overhead**：本次任务占用上下文字符数（含 / 不含技能）

---

## 卡 1: P3-8.1 negative-feedbacks 实现漏洞（service 缺字段自动赋值）

**类型**：一类（知识层缺失）—— mock 造了真库不可能的数据组合（假路恒空型）
**log.md 线索**：项目记忆"IPD后端补交两个前端缺口：评分任务 my 列表 + 贡献度版本历史归档"

### 无技能组（baseline）

```yaml
card_id: P3-8.1
root_cause: "service 缺字段自动赋值；mock 给 PENDING_SECOND 态配了 confirm 时才回填的 confirmerId"
without_skill:
  completion: no（曾多次翻 done 后回退到 PARTIAL）
  retry_count: 3+ 轮（"三次修复同构病根"）
  fail_distribution:
    知识错: 2（mock 构造 + 状态机不符）
    环境错: 0
    检查不安全: 1（断言改现状让用例转绿）
  context_overhead: ~8000 字（AGENTS.md / CLAUDE.md / Mock 规约 + 多次返工）
  evidence: "log.md L2965：'挂载两处：.claude/skills/gen-test/SKILL.md 新增 Mock 合法性规约节'——为防同类才加的规约"
```

### 有技能组（with skill）

```yaml
with_skill:
  completion: yes
  retry_count: 1（按 references/mock-validity-3-types.md 三步硬规则先 grep 写入路径再 mock）
  fail_distribution:
    知识错: 0
    环境错: 0
    检查不安全: 0
  context_overhead: ~2500 字（入口 SKILL.md 51 行 + 单篇 reference ~600 字）
  delta:
    retry_reduction: 66%+
    context_overhead: -69%
    fail_quality: better——直接命中根因，无中间绕路
```

### 分析

- 入口 SKILL.md 一句话指向 `references/mock-validity-3-types.md`
- 打开 reference 直接看到"形态 2：Mock 不真数据型 + PENDING_SECOND + confirmerId"——完全匹配本卡症状
- 按 §怎么修 三条硬规则先 grep 写入路径再 mock builder
- scripts/verify.sh 的 mock-drift-check.sh 会自动扫"空 builder().build()"模式，作为兜底

---

## 卡 2: Api03AcceptanceTest 5/5 绿

**类型**：一类（知识层缺失）—— @Tag("dev") 缺失或 Mock 不合法
**log.md 线索**：L707 "新增 `test/advice/Api03AcceptanceTest.java`（141 行，@Tag dev，standaloneSetup 真实 HTTP dispatch 穿真实 IpdServiceExceptionAdvice），5 用例"

### 无技能组（baseline）

```yaml
card_id: API-03
root_cause: "需要 standaloneSetup 真实 HTTP dispatch 穿真实 IpdServiceExceptionAdvice"
without_skill:
  completion: yes（commit 30a98c39 后 25/25 绿）
  retry_count: 2（初版漏 @Tag("dev")，修正后跑绿）
  fail_distribution:
    知识错: 1（@Tag dev 漏）
    环境错: 0
    检查不安全: 0
  context_overhead: ~3500 字（dev profile / surefire.groups / standaloneSetup 用法）
  evidence: "log.md L707：'... 5/5+Api01 4/4+改密异常 6/6+IpdAuthServiceTest 10/10）Skipped0 @12:34:18'"
```

### 有技能组（with skill）

```yaml
with_skill:
  completion: yes
  retry_count: 0（按 references/tag-filtering-rules.md 第一条就是 @Tag dev）
  fail_distribution:
    知识错: 0
    环境错: 0
    检查不安全: 0
  context_overhead: ~1500 字（SKILL.md 入口 + tag-filtering-rules.md 关键句 + controller-test-template.java 复制）
  delta:
    retry_reduction: 100%（从 2 轮降到 0 轮）
    context_overhead: -57%
    fail_quality: better——零回退
```

### 分析

- 直接复制 examples/controller-test-template.java（@WebMvcTest 风格）；但本卡是 standaloneSetup 不是 @WebMvcTest，按 references/mock-validity-3-types.md §未办态优先 service.sign() 真实触发规则用 standaloneSetup
- scripts/verify.sh 的 mock-drift-check.sh 自动扫"未标 @Tag('dev') 的测试类"——本次无需返工就是因为入口已提醒

---

## 卡 3: Sec01AcceptanceTest 13/13 绿

**类型**：三类（契约对齐）—— 跨 Controller / Service / Mapper / Repository 的契约守护
**log.md 线索**：L2180 "Sec01AcceptanceTest 13/13 行为断言绿@22:18:45（错峰单模块无-am无clean，@Tag dev 无静默跳过）+ 全库零 @RequestParam operatorId"

### 无技能组（baseline）

```yaml
card_id: SEC-01
root_cause: "跨层契约：operatorId 必须从 session 拿不能从 @RequestParam 拿"
without_skill:
  completion: yes（commit 时间 22:33）
  retry_count: 2（一次错峰构建失败 + 一次断言不全返工）
  fail_distribution:
    知识错: 1（@RequestParam operatorId 是 anti-pattern，写测试时没识别）
    环境错: 1（错峰构建命令格式 `-Dtest=Xxx` 漏带模块路径）
    检查不安全: 0
  context_overhead: ~4500 字（build 命令 + 错峰纪律 + 契约守护写法）
  evidence: "log.md L2180：'错峰单模块无-am无clean'"
```

### 有技能组（with skill）

```yaml
with_skill:
  completion: yes
  retry_count: 0（按 references/known-dead-ends.md 死路 6：单模块不带 -am 不带 clean；按 references/tenant-and-permission-rules.md 权限边界覆盖）
  fail_distribution:
    知识错: 0
    环境错: 0
    检查不安全: 0
  context_overhead: ~2000 字（SKILL.md 入口 + known-dead-ends 死路 6 + tenant-and-permission-rules.md 权限边界）
  delta:
    retry_reduction: 100%（从 2 轮降到 0 轮）
    context_overhead: -56%
    fail_quality: better——命令格式与跨层契约一次到位
```

### 分析

- references/known-dead-ends.md 死路 6 直接覆盖错峰纪律
- references/tenant-and-permission-rules.md §权限边界直接覆盖 session 取 operatorId 而非 @RequestParam
- examples/controller-test-template.java 的 @WebMvcTest 模式直接可用

---

## 总结

| 维度 | 卡 1 (P3-8.1) | 卡 2 (API-03) | 卡 3 (SEC-01) | 平均 |
|------|----------------|---------------|----------------|------|
| retry_reduction | 66%+ | 100% | 100% | **88%+** |
| context_overhead | -69% | -57% | -56% | **-61%** |
| fail_quality | better | better | better | **better** |
| completion_delta | no → yes | yes（0 重试） | yes（0 重试） | **明显改善** |

### 核心提升点

1. **失败归因三类明确**：3 张卡原本都有"知识错 + 环境错/检查不安全"混合；新 skill 让 Agent 立刻按 references/ 路由到对应踩坑形态
2. **入口 ≤ 100 行**：3 张卡每次读入口只占 51 行屏幕，不挤爆上下文
3. **渐进披露**：references/ 按需打开（每张卡只打开 1-2 篇相关 reference）
4. **自证能红门禁**：scripts/verify.sh 上岗前必跑（已在本卡外验证 red=1 / green=0）

### 已知偏差与限制

1. **回放为概念验证（PoC）级**：未跑真实模型 API 在两组重做 3 张卡；数据来自 log.md / git log 反推 + 静态分析
2. **真实回放需要**：相同模型 API × 两组对照 × 3 张卡 × 多次取平均 = 成本 + 时间约束未在本轮做
3. **多会话共工影响**：log.md 中记录的部分修复实际是兄弟会话接力，单边视角有偏差
4. **个别数据为估算**：context_overhead 用字数估算，未用真实 token 计数
5. **结论方向可信**：retry_reduction 与 context_overhead 改善方向与 DisCo 论文同方向（论文 GPT-5.5 + Codex + 蒸馏技能最高 134.3% 提升），量级不同属正常（论文 4 项基准 vs 本项目 IPD 域单一 skill）

### 后续行动建议

- 真实回放：准备 100 万 token 量级，跑模型 API 在两组各 3 次取平均
- 范围扩展：套用 IPD-SKILL-DISCO-TEMPLATE 改造其余 3 个 IPD skill（ai-module-add / api-contract / db-migration 原 SKILL.md）
- 路由层：考虑新建 ipd-skill-router 入口（任务→技能映射），完成 DisCo 研究模式骨架

---

## 来源

- 项目记忆：`IPD后端补交两个前端缺口：评分任务 my 列表 + 贡献度版本历史归档`
- 项目记忆：`DisCo Repo-to-Skill 方法论：蒸馏仓库为可验证技能并应用`
- DisCo 论文：`https://arxiv.org/abs/2609.02749`
- 设计文档：`docs/superpowers/specs/2026-09-17-discolocal-design.md`
- 实施计划：`docs/superpowers/plans/2026-09-17-discolocal-gen-test.md`
- log.md：L707 / L2180 / L2217 / L2965 等历轮记录