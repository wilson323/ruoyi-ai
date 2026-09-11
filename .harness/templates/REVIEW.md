# REVIEW.md — 代码审查标准
<!--
  Anthropic AI Native SDLC Playbook · Test/Deploy 阶段产物
  技术负责人书写：什么算真正重要的问题，什么只是格式细节。
  目的：把人的注意力从机械逐行看代码，转向判断变更的意图、行为和风险。
-->

## 元信息

```yaml
review_id: REVIEW-<YYYYMMDD>-<NNN>
plan_ref: PLAN-<YYYYMMDD>-<NNN>
reviewers: [<session-id-1>, <session-id-2>]   # 必须包含至少一个非实现者会话
created: <YYYY-MM-DD>
status: pending | in_review | approved | blocked
```

**铁律**：第一个审查者必须是**全新会话**，不能是写代码的同一个 session。
依据 Anthropic：「让写代码的 AI 检查自己，仍然可能沿用之前的错误思路。」

---

## 一、什么算"真正重要的问题"（Critical）

以下任一项 = 阻断合并：

1. **正确性**：逻辑与 `spec.md` 声明的行为不一致
2. **安全性**：权限校验缺失、注入风险、凭据泄露、PII 外泄
3. **数据**：不可逆的数据变更无回滚方案
4. **契约**：接口变更未同步文档，或破坏了既有消费方
5. **越界**：改动了 `plan.md` 声明范围外的文件或系统
6. **依赖**：新增了未经审查的第三方依赖（slopsquatting 风险）
7. **幻觉**：引入了不存在的包/API/字段

## 二、什么只是"格式细节"（Suggestion，不阻断）

- 命名风格偏好（在 `rules/` 未规定的范围内）
- 注释密度
- 代码组织顺序（不影响可读性时）
- 与本任务无关的历史代码风格

**规则**：格式细节不应占用人的审查注意力。能自动化的一律自动化（lint / format）。

---

## 三、四道机器扫描（按成本排序，任一严重项阻断）

| 顺序 | 扫描 | 工具 | 阻断条件 |
|---|---|---|---|
| 1 | 密钥扫描 | gitleaks / trufflehog | 发现任何真实凭据 |
| 2 | 静态扫描 | eslint / golangci-lint / ruff | Critical 级告警 |
| 3 | 依赖扫描 | npm audit / govulncheck / pip-audit | 新增 High/Critical CVE |
| 4 | 基础设施扫描 | checkov / tfsec | 生产配置风险项 |

## 四、人工只回答四个机器答不了的问题

1. **业务逻辑对不对**？（机器不知道业务想干什么）
2. **有没有幻觉依赖**？（那个新 import 是不是真实存在的包？）
3. **边界条件全不全**？（空值、并发、超时、权限边界）
4. **签字之后你敢不敢负责**？

---

## 五、按风险分级的审查强度（Anthropic 治理机制 1）

| 代码区域风险 | AI 自动审查 | 人工审查 |
|---|---|---|
| low（文档、测试、内部 UI） | 完整 + 独立审查者 | 抽检 5% |
| medium（API、schema） | 完整 + 独立审查者 | 部署签字 |
| high（认证、支付、生产数据） | 完整（仅作辅助） | **逐行 + 每步授权** |

## 六、观察模式（Anthropic 治理机制 2）

新的 AI 审查者前 **5 个任务**只在观察模式运行：

- 只发表评论，由人决定是否采纳
- 记录它的判断与人判断的一致率
- 一致率达标后才逐步放开自主决策权

## 七、风险加权抽样（Anthropic 治理机制 3）

AI 自主审批通过的变更，按风险加权定期抽样交人复查：

| 风险 | 抽样率 |
|---|---|
| low | 5% |
| medium | 20% |
| high | 100%（本来就全人工） |

每次抽样需记录：用了哪些信号、为什么做出这个判断。

---

## 八、审查结论格式

```markdown
## 结论: APPROVED | BLOCKED | NEEDS_DISCUSSION

### Critical（阻断）
- [file:line] <问题> — 依据: <spec 条款 / 安全规范>
  建议: <具体修法>

### Warning（不阻断但需处理）
- [file:line] <问题>

### Suggestion（可忽略）
- <格式类建议>

### 证据
- 密钥扫描: <command> → <result>
- 静态扫描: <command> → <result>
- 测试: <command> → <result>
- 越界检查: 改动文件 ⊆ plan.md 声明范围? Y/N
```

## 九、禁止事项

- 禁止审查者同时修改代码（审查与实现必须分离）
- 禁止只看 diff 不看调用关系（大范围重构用 `calldiff` 类工具看调用树变化）
- 禁止用"测试通过"替代"业务正确"
- 禁止审查者与实现者共享同一套盲区（跨供应商审查见 Skill `cross-vendor-review`）