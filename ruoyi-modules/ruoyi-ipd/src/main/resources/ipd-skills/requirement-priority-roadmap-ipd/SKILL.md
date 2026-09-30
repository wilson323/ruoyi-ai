---
name: requirement-priority-roadmap-ipd
description: "IPD 计划阶段动作 P02「需求优先级排序与版本规划」执行说明。按框架透明排序并核对首版场景闭环。"
version: "1.1.0"
ipd-action: "P02"
ipd-stage: "PLAN"
---

# P02 需求优先级排序与版本规划

## 目标

产出**版本规划表**。G2-2：首版 Must 须能支撑核心场景闭环。

## 输入

P01 需求条目、上市窗口/里程碑线索、资源约束、可选量化影响数据。仅本项目资料 + 用户输入。

## 步骤

1. 核对条目是否齐全；缺验收标准先回 P01，不擅自定级。
2. **框架选择**（用户未指定时推荐，分数必须来自输入）：
   - 有可信影响/工作量数据 → RICE（或 ICE）
   - 需会议快速对齐四档 → MoSCoW（Must/Should/Could/Won't）
   - 需区分基础/期望/兴奋 → Kano（评分须用户给）
   - 无数据 → 仅 Must/Should/Could + 文字依据，禁止臆造分值
3. 划分优先级并写依据（场景闭环、窗口、依赖、风险）。
4. 映射版本/发布列车：首版 Must 集合是否覆盖核心场景；Should/Could 入后续候选。
5. 记录核心取舍与争议项（谁反对、为何暂缓）。
6. 输出「框架与评分表 / 版本范围 / 场景闭环核对 / 决策记录 / 缺项清单」。

## 禁止事项

- 禁止把 Should 升为 Must 充场面。
- 禁止无数据时伪造 RICE/ICE 分数。
- 禁止给出 Gate 签署结论。

依据：QoderWork「需求优先级排序」；上游 prioritize-features、prioritization-frameworks；五大 Gate G2-2。
