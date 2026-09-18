# IPD-SKILL-DISCO-TEMPLATE

> 把单文件 SKILL.md 改造为 DisCo 形态（SKILL.md 入口 + references/ 按需披露 + scripts/verify.sh 动态断言 + examples/ 可复制模板）的标准模板。

## 适用范围

- `.claude/skills/{ai-module-add,api-contract,db-migration,gen-test}/SKILL.md`
- `.claude/skills/ipd-guard-*/SKILL.md`（形态略有不同，可参考 verify.sh 模式）

## 不适用范围

- 与 ai-native-sdlc 链接的 skill（已有 references/ 子目录，不要破坏）
- 第三方维护的 skill（symlink 到 `~/.claude/ai-native-sdlc/skills/`）

## 改造步骤

### Step 1: 拆分入口（SKILL.md → ≤ 100 行）

**保留**：
- YAML frontmatter（name / description / disable-model-invocation）
- "做什么 / 何时用"
- references/ 路由（每条踩坑形态指向哪个 reference）
- scripts/verify.sh 入口命令
- 输出交付物清单
- 禁止清单（精简到 5 条以内）

**下沉到 references/**：
- 详细规则（每条踩坑形态一篇）
- 长代码示例（除非 ≤ 5 行可放入入口）

### Step 2: 按踩坑形态拆 references/ 目录

每篇 reference 文件结构：

```
## 是什么（一句话）
## 为什么踩坑（根因）
## 怎么识别（症状）
## 怎么修（fix pattern）
## 验证（跑哪个 verify 脚本）
## 来源（哪条记录/记忆/文档指出的）
```

### Step 3: 写 scripts/verify.sh

至少包含：
- `env-probe.sh`：跑前环境探测（mvn / JDK / profile / 关键配置存在）
- 一个核心验证（如 red-scan / mock-drift-check / contract-check）
- 主入口 `verify.sh`：按顺序调用所有子脚本，失败归因三类

**关键门禁：自证能红**

每个 verify.sh 上岗前必跑：

```bash
# 故意改一处错 → 跑出红
cp .claude/skills/<skill>/scripts/env-probe.sh /tmp/env-probe.sh.bak
sed -i.bak 's|^exit \$EXIT_CODE$|exit 1  # self-red override|' .claude/skills/<skill>/scripts/env-probe.sh
bash .claude/skills/<skill>/scripts/verify.sh; echo "red EXIT=$?（期望 != 0）"

# 还原 → 跑绿
cp /tmp/env-probe.sh.bak .claude/skills/<skill>/scripts/env-probe.sh
rm .claude/skills/<skill>/scripts/env-probe.sh.bak
bash .claude/skills/<skill>/scripts/verify.sh; echo "green EXIT=$?（期望 0）"
```

注意：注入 `false` 在 `set -e` 脚本末尾不会改变最终退出码（被末尾 `exit $EXIT_CODE` 覆盖），必须用 `sed` 替换 exit 行。

### Step 4: 写 examples/（如适用）

- 每个示例可直接复制到目标位置
- 示例文件 frontmatter 标注：适用场景 / 关键 funs / 引用哪个 reference
- 至少 1 个示例在 verify.sh 中跑通

### Step 5: 自检清单

- [ ] SKILL.md 行数 ≤ 100
- [ ] references/ 每篇都有 "是什么/为什么/识别/修/验证/来源" 6 节
- [ ] scripts/verify.sh 通过自证能红
- [ ] examples/ 至少 1 个可在目标模块复制
- [ ] git commit 信息格式：`feat(skill): <skill-name> DisCo 形态改造`

## 失败归因三类（DisCo 同款）

1. **知识自身错**：修复 SKILL.md/references/，定点重跑
2. **环境跑不通**：写明环境约束，标记 WARNING
3. **检查不安全**：跳过或换其他检查方式

## 隔离纪律

- 在 worktree 上做（`git worktree add -b feature/disco-<skill> ../ruoyi-ai-disco-<skill> HEAD`）
- 主分支只做只读探针
- 多会话共工：错峰 + 单模块 + 不带 `-am` 不带 `clean`
- 提交后**立刻**核 `git show --name-only --format="" HEAD | grep -c .` 必须 == 声明条数

## 已知陷阱（按踩坑形态）

| 陷阱 | 表现 | 解法 |
|------|------|------|
| macOS BSD awk 不支持 gawk 数组 | `awk 'match($0, /pat/, m); m[1]'` 返回空 | 用 `substr($0, RSTART+4, RLENGTH-9)` 兼容写法 |
| bash 双引号内 `${...}` 被提前展开 | `grep -q "<groups>${profiles.active}</groups>"` 找不到 | 用 `grep -qF` 固定字符串，或 `\\$` 转义 |
| 注入 `false` 在 set -e 脚本末尾不生效 | 自证能红失效 | 用 `sed` 替换 exit 行 |
| 共享索引下裸 commit 扫入他人暂存 | 不该提交的文件被带入 | worktree 隔离 + 提交后 `git show --name-only` 核验 |

## 来源

- DisCo 论文：`https://arxiv.org/abs/2609.02749`
- 示范样本：`.claude/skills/gen-test/`（已改造）
- 本项目 spec：`docs/superpowers/specs/2026-09-17-discolocal-design.md`
- 项目记忆：`DisCo Repo-to-Skill 方法论：蒸馏仓库为可验证技能并应用`（2026-09-11）
- 项目记忆：`共享索引下裸 git commit 扫入他人暂存：精确 stage ≠ 精确 commit`
- 项目记忆：`多会话共工 main 直提被 reset 孤儿化：隔离worktree+PR落地+尊重owner还原`