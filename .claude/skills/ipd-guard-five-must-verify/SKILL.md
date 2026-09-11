---
name: ipd-guard-five-must-verify
description: 引用别人结论或自己之前的笔记时，五类事实源必须现查现写，禁止凭记忆写。封装 hash / 端口字段 / 段号 / 看板回读 / 跨仓 cd 五必现查规约，避免连环失真。
---

# ipd-guard-five-must-verify

引用事实源时**禁止凭记忆**。R13 立的五必现查规约。

## 何时使用

- 引用别人结论里的 hash / commit 号
- 引用配置文件里的端口 / 字段名 / 路径
- 引用代码里的行号 / 段号 / 注解位置
- 引用看板数据（卡数 / 状态 / marker）
- 跨仓执行 shell 命令

## 五必现查（缺一不可）

### 1. Hash / commit 号必现查

**禁止**凭记忆写 commit hash——已实测 R12.1 marker 凭记忆写 R11 hash，导致后续验证全错位。

**做法**：

```bash
# 引用别人的 hash 时，从 git 直接拿
git log --oneline -1 <branch>
git rev-parse HEAD

# 引用自己之前的 commit
git log --all --oneline | grep "<关键字>"
```

### 2. 端口字段必现查

**禁止**凭记忆写端口——兄弟会话可能改了 `application.yml` / `vite.config.ts`，你记忆里的端口已过期。

**做法**：

```bash
# 后端端口：直接 grep 配置
grep -n "port" ruoyi-admin/src/main/resources/application*.yml

# 前端端口
grep -n "port\|server" apps/web-antd/vite.config.ts apps/web-antd/package.json

# 已运行端口（实测用）
lsof -nP -iTCP:<port> -sTCP:LISTEN
```

### 3. 段号 / 行号必现查

**禁止**引用文档里写的行号——已实测同一个 `application.yml` 的 `demo:` 段，20:56 实测在 :396、21:10 已到 :400（成因：多会话共工同一工作树，兄弟在途未提交编辑就会推号）。

**做法**：

```bash
# 引用行号时用键名定位 + 段名锚定，不直接写绝对行号
grep -n "demo:" ruoyi-admin/src/main/resources/application.yml
grep -n "tenant:" ruoyi-admin/src/main/resources/application.yml
grep -n "excludes:" ruoyi-admin/src/main/resources/application.yml

# 如必须引用行号（评审纪要 / 复盘文档），写"键名 + 当时的值"
# 而非"段号:X-Y"——键名稳定，行号漂移
```

**示例**：

```markdown
# 错（行号漂移）：
"demo 段在 application.yml:296-308"

# 对（键名稳定）：
"demo.excludes 在 application.yml 的 demo.excludes 数组下"
```

### 4. 看板回读必 fresh 拉

**禁止**用推算或缓存的总账数字——见 `ipd-guard-fresh-verify`。

**做法**：

```bash
# 写"已完成 N 张"之前 fresh 拉一次
curl -s "http://127.0.0.1:62250/api/projects/<project_id>/tasks" \
  | jq 'group_by(.status) | map({status: .[0].status, count: length})'
```

### 5. 跨仓 cd 必用绝对路径

**禁止**用相对路径 `cd ..` 跨仓——已实测 shell cwd 漂到前端仓导致 `git log` 显示错误 hash。

**做法**：

```bash
# 跨仓命令必 "cd 绝对路径 &&" 开头
cd /Users/mac/Documents/ruoyi-ai && git log --oneline -1
cd /Users/mac/Documents/ruoyi-ipd-web && git log --oneline -1

# 不要依赖当前 shell 路径
git log --oneline -1   # ❌ 可能在前端仓，后端仓的 hash 错了
```

## 五类事实源的特征

| 类型 | 漂移速度 | 引用风险 |
|---|---|---|
| hash / commit 号 | 慢（commit 后稳定） | 凭记忆写错 hash |
| 端口字段 | 中（配置可能改） | 凭记忆写错端口，HTTP 验证落空 |
| 段号 / 行号 | 快（兄弟会话在途编辑推号） | 引用行号文档全错 |
| 看板数据 | 极快（每分钟可能变） | 推算数字一定不准 |
| 跨仓 cd 路径 | 取决于 shell state | 写错仓导致所有 git 命令读错仓 |

## 必做检查清单

任何引用他人结论 / 自己之前笔记的文档 / 提交前自检：

- [ ] 引用了 hash → 已 `git log` 现查
- [ ] 引用了端口 → 已 grep 配置现查
- [ ] 引用了段号 → 已改成"键名 + 当时值"或 grep 现查
- [ ] 引用了看板数据 → 已 fresh 拉 API 复核
- [ ] 跨仓 shell → 已 `cd 绝对路径 &&` 开头
- [ ] 文档里写的行号型断言 → 已复核"键名 + 值"而非"段号"

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| 文档写的行号对不上实际配置 | 行号漂移（兄弟在途编辑） | 环境错 | 改用键名引用 |
| 引用了 R11 hash 但实际是 R12 | 凭记忆写 | 知识错 | git log 现查 |
| shell 跑到错仓 git log 错 hash | cwd 漂移 | 知识错（违反规约 5） | 改用 `cd 绝对路径 &&` |
| 总账数字与其他会话对不上 | 推算未 fresh 拉 | 知识错（违反规约 4） | fresh 拉 API 重核 |
| HTTP 验证连不上 | 凭记忆写错端口 | 知识错 | grep 配置现查 |

## 禁止清单

- ❌ 凭记忆写 commit hash
- ❌ 凭记忆写端口号
- ❌ 在文档 / 复盘里写绝对行号（必须用键名或段名）
- ❌ 推算看板总账数字
- ❌ 跨仓命令不写 `cd 绝对路径 &&`
- ❌ 评审纪要 / 复盘文档引用段号型断言不复核

## 版本指纹

- 验证时规约：R13（2026-09-08 立）
- 验证时依据文档：`docs/ipd-系统说明/事实源五必现查规约-20260908.md`
- 验证时教训样本：R12.1 marker hash / application.yml 行号漂移 / shell cwd 漂移
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：规约版本升级 / 漂移教训类型扩展