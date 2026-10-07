# Handover: SESSION-20261007-ai-governance — AI 偏差治理 + 文档治理四条

```
task_id:      SESSION-20261007-ai-governance
closed_at:    2026-10-07T06:57-07:00
closed_by:    claude (session-1d4fb09a, 会话名 ruoyi-ai-fe)
next_owner:   next-session
spec_ref:     owner 2026-10-07 四条指示原文（见下方 changes_made）
diff_ref:     分支 baseline/pre-teardown，HEAD 0992c795，**未推提交 0**（兄弟会话已推）
```

## changes_made

owner 四条指示 → 四组产物。**注意：只有一半已进 git，另一半还在工作树。**

| 指示 | 产物 | git 状态 |
|---|---|---|
| **① 统一成一个** | `docs/ipd-系统说明/项目现状说明书.md`（196 行，「系统现在是什么」唯一入口） | ❌ **未跟踪** |
| **② 禁止行数过长、渐进引入** | `AI偏差-根因分析与根除方案-20261007.md` 360 行 → **25 行速查卡**；证据移到 `-详情-`（348 行）<br>`CLAUDE.md` 新增段 26 行 → 17 行 | ❌ 均未跟踪/未提交 |
| **③ 需求整合、禁止散落** | 只完成**盘点**，方案在 `/tmp/REQ-SCATTER.md`（385 行），**未实施** | — |
| **④ 彻底清理** | 只完成**清单**，在 `/tmp/CLEANUP-LIST.md`（304 行），**未删任何文件** | — |

**已进 git 的**（兄弟会话提交时带进去了）：
`scripts/evidenced-count.sh`、`scripts/gen-doc-index.sh`、`scripts/check-hook-wiring-live.sh` 前者、
`.claude/helpers/output-shape-guard.cjs` 外的 `.claude/hooks/block-dangerous-git.sh`(+123/-12) 与
`.claude/hooks/test-block-dangerous-git.sh`（22 用例）

**必须重跑确认的**（我做了但没验证交接后的状态）：
`.claude/settings.json`（已改未提，挂载 output-shape-guard）、`CLAUDE.md`（已改未提）、
`docs/ipd-系统说明/log.md`（**已暂存**——`git status` 显示 `M `，接手时注意它已经在索引里）

## verified_evidence

```
bash .claude/hooks/test-block-dangerous-git.sh   → PASS=22 FAIL=0，退出 0
bash /tmp/final-verify.sh                       → PASS=17 FAIL=0，退出 0
node docs/wiki/wiki-lint.cjs                    → 通过: 91 | 失败: 0 | 孤立 raw: 0
grep -cE '"LC01"|"LC03"' <AC>                  → 0（阳性对照 "LC02"=3 证明尺子有效）
grep -c 'new ActionDef(".*",".*",".*",".*","DEEP"' <AC>   → 40
grep -c 'new ActionDef(".*",".*",".*",".*","LIGHT"' <AC> → 27   → 有效动作 67
grep -n 'G2-6' .../IpdGateElementSeedInitializer.java       → veto 列 = "N"
```
其中 `<AC>` = `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java`

**基准值文档**：`docs/ipd-系统说明/治理/退役口径基准值-20261007.md`（未跟踪）
所有勘误都引用它。**它已被我修正过一次**（§二 BonusPool 表述、§4.1 尺子与读数不配套）。

**6 路扫描 + 6 路勘误的判定结论**：见 `docs/ipd-系统说明/存疑文档逐份判定-20261007.md`（未跟踪）

## open_issues

**blocker（owner 未拍板，做不了）**
1. **lint-reports 148 份怎么清** — 三个选项：只删旧批次(132份/4.4MB) / 先修 H-15 判据再删(**我建议**) / 全删(6.9MB)。
   **关键**：H-15 判据是 `delta > 10`（**单向**），删 148 份 → `-137>10` 为假 → **静默通过**，门禁被永久废掉无提示。
2. **9 月前 141 份旧文档** — 混着现役锚点（log.md / 看板镜像 / BCP-Registry），需 owner 点名。

**non-blocker（我该做没做完）**
3. **指示③「需求整合」完全没实施**，只有盘点。方案在 `/tmp/REQ-SCATTER.md`，核心是：新建需求唯一出处目录，
   每条必须带 `source`/`implementation` 的 **file:line**（行号变了自然暴露）；正文禁写统计数字；
   数字由 `scripts/gen-baseline-*.sh` 从代码生成 + `check-baseline-consistency.sh` 守门（手改 67→69 必须 FAIL）。
4. **`gen-doc-index.sh` 分类判据不可靠**：按目录名把 `_probes/` 标「机器产物」，实际是人写的会话报告。
5. **`.claude/CLAUDE.md` 的 output-shape-guard 需重启会话才生效**（PostToolUse 配置只在会话启动时加载）。

**wontfix-this-task**
6. **测试夹具 BonusPool「代码债」是我虚报的**——实测 16 文件 20 处命中里 19 处只是注释，非注释仅 1 处。已勘误，不要再当活干。
7. **退役三域 17 份文档一份都不该删**（6 份含全仓唯一证据：真库 SQL 现场输出 / ZK-IPD 六条规则原文）。
   上一轮已用「加 ⛔ 退役指针」处理过，**不要重复删**。

## suggested_next_step

**先做这一件，别的不用管：**
把 4 个未跟踪文件加进来并提交——
```bash
git add docs/ipd-系统说明/项目现状说明书.md \
        docs/ipd-系统说明/文档总索引.md \
        docs/ipd-系统说明/存疑文档逐份判定-20261007.md \
        docs/ipd-系统说明/AI偏差-根因分析-详情-20261007.md \
        docs/ipd-系统说明/AI偏差-根因分析与根除方案-20261007.md \
        docs/ipd-系统说明/治理/退役口径基准值-20261007.md \
        .claude/helpers/output-shape-guard.cjs \
        scripts/gen-doc-index.sh scripts/check-hook-wiring-live.sh \
        .claude/settings.json CLAUDE.md
git commit -m "docs+governance: 文档单一出处/读数自证三件套/守卫测试集"
```
⚠️ **必须逐个 add，不许 `git add -A`** —— 兄弟会话 `ruoyi-ai-ab`（7h）与 `t-3` 仍在同树改动，
`git status` 当前有 24 改 + 10 新增，混提会卷进别人的在途文件。
⚠️ `log.md` 已暂存，先 `git diff --cached --stat` 看清楚它暂存了什么再决定。

**提交前必跑**（三条都要退出 0）：
`bash .claude/hooks/test-block-dangerous-git.sh` 与 `node docs/wiki/wiki-lint.cjs` 与
`bash scripts/evidenced-count.sh find . --name '*.java'`（应出 EC_COUNT，非空）

## do_NOT

- **不要删退役三域的 17 份文档**（已验证含唯一证据）——见 open_issues #7
- **不要 `git add -A` / `git checkout --` / `git restore`**——本仓共享工作树多会话并发，
  记忆 `shared-worktree-commit-only` 记着：`git add`+commit 会被并发会话改写索引顶掉，
  `git checkout --` 在未提交工作树上等于不可逆删除
- **不要信 `.claude/CLAUDE.md` 里的 repowise 索引**：本轮实测它曾落后 HEAD **669 个提交**，
  且曾长期「Confidence 99% + never re-read」却指向不存在的索引。已重建真索引（6403 文件/40820 符号）并裁掉过期段
- **不要报数不附命令**：本仓最致命的偏差形态是「读数来自一把没对准的尺子」。
  用 `scripts/evidenced-count.sh`，它内置三道自检（空计数→退3 / 零值未声明→退1 / 扫归档→标红）
- **不要写超过 30 行的规则文件**：owner 2026-10-07 明确「流程禁止行数过长应该渐进引入」。
  规则写三行必读 + 详情另放附件（`<details>` 或独立文件）
- **不要用黑话缩写**：用户 2026-10-07 明确开火——`~/.claude/CLAUDE.md` 第一条就是禁止
  R1/R2/R3、勘误、派单、SSOT 这类词，必须先说人话
- **不要凭关键词判定文档有效性**：关键词判据准确率 91.7% 但噪音率 36%（72 份里 26 份活文档被误标）。
  「关键词能标值得看，不能标是什么」
- **不要只收子智能体结果不读质疑**：本轮 5 路里 3 路拒绝盲从派单，其中 1 路推翻了我给的尺子
  （我写 service=150 但给的命令只能数出 30）、1 路推翻了我给的语义（把需求原文的 42 批量改成 40 会改错）。
  **子智能体是校验器不是执行器**

## 与 owner 的沟通约定

owner 2026-10-07 的原话比任何总结都准：
「统一成一个」「流程禁止行数过长应该渐进引入」「需求整合在一起禁止散落」「彻底清理」
