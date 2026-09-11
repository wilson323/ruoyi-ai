---
name: ipd-guard-multi-session-handoff
description: ruoyi-ai / ruoyi-ipd-web 两仓多会话共工时的在途工作处理、隔离 worktree 落地、commit / 分支 / PR 规范。封装 R25 OPS-09 软化后"完整接手兄弟会话在途"三步法 + 工作树改动互吃教训 + maven 增量缓存假错规避。
---

# ipd-guard-multi-session-handoff

多智能体协同下的在途工作处理与 commit 规范。

## 何时使用

- 看到 main / 当前分支有非自己改动的文件（`git status` 显示 M 来自兄弟会话）
- 准备切分支 / commit / 推 PR
- 接手兄弟会话未完成的工作
- 多会话共工同一后端 / 前端工程
- 怀疑 build / test 假红（"一会绿一会红"）

## 三条铁律

### 铁律 1：主工作树不 commit

**禁止**在主工作树（`/Users/mac/Documents/ruoyi-ai/` 或 `/Users/mac/Documents/ruoyi-ipd-web/`）直接 `git checkout -b` / `git commit` / `git push`。

原因：
1. 分支基于本地 main，与 origin/main 分叉，PR 会卷入无关分叉
2. owner 已在 main 上有还原过的脚本与在途改动，工作树状态不归本会话管
3. 兄弟会话可能在主工作树写盘，commit 会互吃

**强制做法**：

```bash
# 1. fetch 远端 main
git fetch origin main

# 2. 从 origin/main 起 worktree（绝对隔离）
git worktree add -b <branch> /tmp/wt-<session-id> origin/main

# 3. 在 worktree 内 cherry-pick / commit / push / PR
cd /tmp/wt-<session-id>
# ... 改动 ...
git add -A
git commit -m "..."
git push origin <branch>

# 4. PR 创建
gh pr create --base main --head <branch> --title "..." --body "..."

# 5. 完事清理
cd /Users/mac/Documents/ruoyi-ai
git worktree remove /tmp/wt-<session-id>
```

**例外**：纯只读探针 + 证据交付任务可以在主树执行（不动 commit 就行）。

### 铁律 2：接手兄弟会话在途——R25 软化三步法

R25 起，owner 已授权"完整接手兄弟会话在途"。**但**有硬要求：

#### 第 1 步：逐一评审处置

```bash
# 列出兄弟会话改过的所有文件
cd /Users/mac/Documents/ruoyi-ai
git status --short | grep -v "^??"

# 对每个 M 文件，分类处置：
# - 原样入库：兄弟改得对，直接纳入
# - 修改后入库：兄弟改得对但格式 / 风格需统一
# - 还原：兄弟改得不对（违反规约 / 引入新坑）
```

**每个文件必须有处置结论**，记录到 SSOT 镜像 `docs/ipd-系统说明/开发计划-看板镜像.md` 的对应卡描述里。

#### 第 2 步：SSOT 镜像 + log.md 登记

```markdown
## 接手事实登记

- 接手时间：2026-09-XX HH:MM
- 兄弟会话：claude-code / codex / ruflo swarm
- 接手范围：M 文件清单（按上述三分类）
- 处置结论：原样 N / 修改 N / 还原 N
- 关联 commit：本会话即将产生的 commit hash（commit 后回填）
```

#### 第 3 步：兄弟自有编号体系保留史实

兄弟会话可能有自己一套编号（如"轮次号撞号"）。**用 `ORIGIN-<原编号>` 前缀保留史实**，不覆盖删除。

例：兄弟用了 `R12.1`，本会话继续用 `R12.1` 会有撞号——改为 `ORIGIN-R12.1` + 本会话另起编号体系。

### 铁律 3：build / test 必须错峰 + 单模块

**禁止**：
- `mvn -am`（跨模块构建，并发时重写 `target/`，制造大面积假红）
- `mvn clean`（同上）
- 多个会话同时跑同一模块测试

**强制**：

```bash
# 单模块 + 不带 -am + 不带 clean + 错峰
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test

# 如必须跨模块，先 -rf 断点续跑
mvn -o -pl ruoyi-modules/ruoyi-ipd -am -DskipTests -Dmaven.javadoc.skip=true install
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test
```

**判责流程**：
- 测试红 → 先看 `git status` 文件是否干净
- 干净 = 已提交真红，本会话责任
- M = 兄弟 WIP 摇摆不介入，等兄弟收口再跑

## maven 增量缓存假错

并发多会话同时 `mvn install -DskipTests`，会交叉重写 `target/` 目录，导致后续测试出现 `NoClassDefFoundError` 等假红。

**已实测**：85 跑 71 Error 中 ≥66 为假红。

**防御**：
1. 错峰跑 mvn（前后间隔 > 30 秒）
2. 不带 `clean`（保留已编译产物）
3. 出现 NoClassDefFoundError → `rm -rf target/classes/<package>` 单文件清掉重编译，不全局 clean

## 共享工作树改动互吃

兄弟会话在你 `git add` 前改了你也要改的文件，会导致：
- 你的改动被覆盖（兄弟 `git checkout .` / 误操作）
- 兄弟的改动丢失（你 `git add -A` + commit）

**防御**：
1. 始终在隔离 worktree 干活（铁律 1）
2. commit 前 `git status` 确认 M 文件都是预期的
3. 出现意料之外的 M，先 stash 再确认归属

## OPS-09 软化要点

并发写单一写入者规则（OPS-09）在 R25 软化：

- **不变**：Java 源码 / SSOT 看板镜像 / log.md 默认主协调会话串行写
- **可破**：其他会话可做只读探针 + 证据交付
- **可破但要登记**：完整接手兄弟会话在途（按三步法）
- **不可破**：绕过 `manage.py` 的 PLAN 哈希校验

## 必做检查清单

任何 commit / 切分支 / 推 PR 前自检：

- [ ] 当前在隔离 worktree（不在主工作树）
- [ ] `git status` 显示的 M 文件都是自己改的
- [ ] 接手的兄弟在途工作已按三步法处置 + 登记
- [ ] 不带 `-am` 不带 `clean` 跑测试（如需 build）
- [ ] 多会话共工时已错峰（间隔 > 30 秒）
- [ ] push 前已与 origin/main 对齐（`git fetch && git rebase origin/main`）
- [ ] 不绕过 manage.py 的 PLAN 哈希校验

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| commit 后兄弟会话的改动消失 | 共享工作树改动互吃 | 知识错（违反铁律 1） | 隔离 worktree 重做 |
| PR 包含无关分叉 | 基于本地 main 起分支 | 知识错 | 基于 origin/main 起 worktree |
| mvn test 跑出 NoClassDefFoundError | 并发 -am / clean 制造假红 | 环境错 | 不带 -am / clean 错峰重跑 |
| 测试一会绿一会红 | 兄弟会话在 DDL/数据 | 环境错 | git status 判责，等兄弟收口 |
| manage.py set 失败 | 绕过 PLAN 哈希校验 | 知识错 | 让主协调会话跑，或重新对齐 PLAN |

## 禁止清单

- ❌ 在主工作树直接 `git checkout -b` / `git commit` / `git push`
- ❌ 用 `mvn -am` / `mvn clean` 跑测试（并发假红制造机）
- ❌ 静默接手兄弟会话在途（必须按三步法登记）
- ❌ 覆盖兄弟的自有编号体系（用 `ORIGIN-` 前缀保留）
- ❌ 不看 `git status` 就 `git add -A` 提交
- ❌ push 前不复用 origin/main 基线
- ❌ 绕过 manage.py 的 PLAN 哈希校验

## 版本指纹

- 验证时 worktree 实践：从 origin/main 起，路径 `/tmp/wt-<session-id>`
- 验证时 OPS-09 软化版本：R25（2026-09-09 owner 授权）
- 验证时 SSOT 镜像：`docs/ipd-系统说明/开发计划-看板镜像.md`
- 验证时 manage.py：当前版本（现查）
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：worktree 实践变更 / manage.py 规则升级 / OPS-09 状态变更