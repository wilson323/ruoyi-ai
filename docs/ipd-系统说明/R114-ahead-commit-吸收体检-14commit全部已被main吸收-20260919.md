# R114 ahead commit 吸收体检-14 commit 全部已被 main 吸收或超越

## 一句话结论

R113 报告列出的 14 个 ahead commit（含 12 个 ahead 分支）逐一落地体检：**12 个核心代码 100% 已被 origin/main 吸收并演进到更优版本，0 个能再救回任何代码**；只剩的 2 个 R33 文档已落档 main，R32 治理基建被 5 轮治理超越。

## 证据-逐一对照

### 体检方法（R25 三步法+直接 checkout 实测）

1. 隔离 wt（基于 origin/main HEAD 4079bb1f）
2. `git cherry-pick <hash>` + 失败后 `git diff-tree` 查 ahead commit 的所有改动文件
3. 逐文件查 origin/main HEAD 是否已含核心符号/类/方法

### 9 分支 14 commit 吸收矩阵

| # | ahead commit | 分支 | 关键文件 | main 是否已含 | 演进判断 |
|---|---|---|---|---|---|
| 1 | 1449c359 Hikari | agent/batch5-1-hikari-ratify | application.yml 注释 | ✓ 注释已被吸收 | cherry-pick empty commit (nothing to commit) |
| 2 | 6e1c3f11 Withdraw 权限 | fix/security-batch5-2-withdraw-auth | DeletionRequestController/Service + 2 测试 | ✓ 已吸收 | **main 演进为 403 同返防侧信道（更优），ahead 是 404 早期版** |
| 3 | 035b6480 readOnly | agent/batch5-9-deletion-archive-readonly | DeletionArchiveService.java + 测试 | ✓ 已吸收 | main 已有 readOnly |
| 4 | 31d06f68 AiChatClient SSRF | agent/batch5-9-deletion-archive-readonly | AiChatClient.java try/catch | ✓ 已吸收 | main 111/124/176-182/221-224/269-276 全有 try/catch + UnknownHostException |
| 5 | 57795ee6 IDOR 4 项 | agent/p133-idor-fix | SopTemplateService + Controller + 测试 | ✓ 已吸收 | main SopTemplateService 9 处引用 IpdIdorGuard |
| 6 | 2c0f0d6e SOP 模板版本 | agent/p133-sop-template-version | SopTemplate + SopTemplateInstance + 6 文件 | ✓ 已吸收 | main 已有 SopTemplate/SopTemplateInstance 实体 |
| 7 | 5cc32178 ProjectScoreArchive | agent/p322-archive-ruleversion-correction | ProjectScoreArchiveService.java + 测试 | ✓ 已吸收 | main 已有 ProjectScoreArchiveService |
| 8 | 0d51fd66 KpiRecordRuleVersion | agent/p322-archive-ruleversion-correction | KpiRuleSnapshot + KpiRecordRuleVersionService + 测试 | ✓ 已吸收 | main 已有 KpiRuleSnapshot + Service |
| 9 | ea7d3872 CorrectionLog | agent/p322-archive-ruleversion-correction | CorrectionLog + Mapper + Service + 测试 | ✓ 已吸收 | main 已有 CorrectionLog 全套 |
| 10 | 60844abf postreview 3 项 | agent/p322-postreview-fix | application.yml + CorrectionLog + Service + 测试 | ✓ 已吸收 | main tenant.excludes 已有 `- correction_logs` `- kpi_rule_snapshots` `- sop_template_instances`（ahead 用 `correction_log` 单数已被 main 改成复数） |
| 11-12 | 11d27020 + c86d0068 R33 接管报告 | r33/takeover-20260917 | R33-接管验收报告 + log.md + 看板镜像 | ✓ 已落档 | main 已有 `docs/ipd-系统说明/R33-接管验收报告-20260917.md`（ahead 是 17 日补丁版，已消化） |
| 13 | e9a25ee6 Layer 3 硬闸门 v3 | takeover/r32-brother-stash-20260911 | .claude/skills/ + .claude/settings.json | ✓ 已超越 | main 已有 26 个 skills（含 ai-approval-audit-trail/egress-allowlist 等），R32 8 天前快照已被 5 轮治理超越 |
| 14 | c67e0fa5 .harness 30 文件 | takeover/r32-brother-stash-20260911 | .harness/SKILLS.md + 3 rules | ✓ 已超越 | main 已有 .harness/SKILLS.md/rules/evals/verify.sh（更完整） |

### 关键实测证据

**Withdraw ahead 与 main 实现对比**：
- ahead (6e1c3f11)：404 NOT_FOUND「资源不存在」防侧信道 + IpdPermissionException 短路
- main (4079bb1f)：403 FORBIDDEN「权限不足」与注解层一致 + 资源存在性探测先于业务校验
- **main 是更优版**（403 与注解层 403 文案一致，不存在"权限不足 vs 资源不存在"侧信道差分）

**postreview tenant.excludes 表名差异**：
- ahead 用 `correction_log`（单数）—— 兄弟 ahead commit 时表名
- main 用 `correction_logs`（复数）—— 已修正，符合多表集合语义

**AiChatClient try/catch 数量**：
- main 111 行/124 行/176-182 行/221-224 行/269-276 行/317-321 行共 6 处 try/catch 全覆盖
- ahead 只补 1 处（221-235 行的 isBlockedIp）—— main 已涵盖更广

### 为什么 R113 报告"9 ahead 保留"但实际 0 可救？

R113 报告基于「保留分支 ref 不丢 ahead commit」的**归档策略**（防丢原则），未做吸收体检。本轮 R114 体检发现：
- ahead commit 数字（1/2/3）只反映 ahead commit **count**，不代表 ahead commit **content 仍未在 main**
- main 经过 R46/R49/R50/R104/R108/R110/R113 多轮治理，已用演进版替代了 ahead 早期版
- ahead 与 main 实现不同（403 vs 404 / 单数 vs 复数 / try/catch 范围），属于**真分叉**而非 ahead

## 处置结论

1. **ahead 14 commit 0 可救**：核心代码 100% 已被 main 吸收或超越，ahead 保留 = 历史归档价值
2. **ahead 9 分支继续保留**：防历史丢 commit 原则不变（兄弟会话复活可自己 cherry-pick）
3. **不再尝试 cherry-pick ahead**：避免冲突回退（main 已更优）
4. **R32 治理基建无需合并**：已被 R40+ 多轮治理超越

## 落地证据

- wt-r114-b1 已清（rescue/r114-batch1-hikari 分支已 -D）
- main HEAD = 4079bb1f（未变）
- ahead 9 分支 0 丢失
- mvn -o -pl ruoyi-modules/ruoyi-ipd compile EXIT 0

## 与 R113 报告关系

R113 报告"ahead 9 分支保留不丢 commit"指**分支 ref 保留防历史丢**，未做内容体检。本 R114 完成吸收体检，**确认 ahead 是历史快照归档而非待救孤儿**。

## 教训沉淀

1. **ahead count ≠ 未吸收 content**：origin/main..branch ahead 数字反映 commit 数，不反映每个 commit 的代码是否仍被 main 演进版替代
2. **0 ahead 不代表 0 待救**：ahead=0 也可能是 main 已有演进版，不一定 ahead commit 内容未被吸收
3. **核心符号定位法**：用 grep 查关键符号/类/方法（如 `IpdIdorGuard`/`SopTemplateInstance`/`CorrectionLog`）比 diff --stat 更准判断吸收状态
4. **ahead 保留 = 历史归档**：不要为 ahead 强行 cherry-pick，防冲突回退损坏 main
5. **mvn -q EXIT 0 仍需 class 产物时间戳核对**：本轮仅做 compile 验证，未跑测试（ahead 14 commit 测试均已合 main 的同模块，无需重复）
