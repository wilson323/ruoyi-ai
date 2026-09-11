---
name: ipd-guard
description: IPD 项目高频踩坑防御技能包入口。按"我现在要做什么"路由到对应的子技能（fresh-verify / ddl-apply / mock-validity / frontend-drift / multi-session-handoff / five-must-verify）。每个子技能配可执行的验证命令，不靠记忆调用。
---

# ipd-guard

IPD 项目踩坑防御技能包的入口路由。

## 何时使用

任何会触发以下场景之一的动作，先打开本入口，按下方路由表跳到对应子技能，**再动手**：

- 看板翻卡 / 注记 / 更新状态
- 改 Controller / 加字段 / 改 DTO
- 加 DB 字段 / 改表结构 / 写迁移脚本
- 写单测 / 写 Mock / 跑测试套件
- 改前端 api/ipd/*.ts / 新增错误码文件
- 接兄弟会话在途工作 / 切分支 / 准备 commit
- 引用别人结论里的 hash / 端口 / 行号 / 段号

## 路由表（按"我要做什么"查）

| 我现在要做什么 | 跳到子技能 | 关键检查命令 |
|---|---|---|
| 看板 PUT / 翻卡 / 注记 | `ipd-guard-fresh-verify` | PUT 后独立 GET 一次；不放推算 |
| 加 DB 字段 / 改表 / 写迁移 | `ipd-guard-ddl-apply` | `check-entity-db-drift.py --docker ...` |
| 写单测 / 写 Mock | `ipd-guard-mock-validity` | 跑 `mvn test` 不靠 stub 替业务 |
| 改前端 API / 错误码文件 | `ipd-guard-frontend-drift` | `scripts/check-ipd-frontend-drift.sh` |
| 接兄弟会话在途 / 切分支 | `ipd-guard-multi-session-handoff` | 隔离 worktree，不在主树 commit |
| 引用别人结论（hash/端口/段号） | `ipd-guard-five-must-verify` | 五类事实源现查现写 |

注：vite 启动规约的权威在 ruoyi-ipd-web 仓 AGENTS.md（前端仓负责前端工具链），后端仓不再维护 vite-startup 子技能（owner 2026-09-11 删除路由行）。

## 设计原则（DisCo 派生）

1. **验证即门禁**：每条规则配一个会红的命令，不靠记忆调用。
2. **失败归因三分类**：知识错 / 环境错 / 检查错，定点修，不笼统说"又挂了"。
3. **版本指纹**：每条规则记录验证时依赖版本；升级即重验。
4. **可推翻的先验**：规则写成"通常这样做 + 例外条件"，不要写成铁律（PassNet conv3d 教训）。
5. **渐进披露**：本入口只讲路由，细节在子技能里展开，避免上下文塞爆。

## 禁止清单

- ❌ 凭记忆写 hash / 端口 / 行号 / 段号——五必现查会拦
- ❌ 看板 PUT 后用单卡 GET 复核——fresh-verify 子技能会拦
- ❌ 实体加字段但 DDL 不 apply 就 commit——ddl-apply 子技能会拦
- ❌ Mock 替业务后跑绿就翻卡——mock-validity 子技能会拦
- ❌ 在主工作树接兄弟会话在途——multi-session-handoff 子技能会拦
- ❌ 前端 api/ipd/*.ts 新增与既有冲突的 export——frontend-drift 子技能会拦

## 输出交付物

调用任意子技能完成后必须产出：

1. 子技能 SKILL.md 中的"必做检查清单"逐项自检结果。
2. 对应验证脚本的 exit code + 关键输出片段。
3. 若归因为"知识错"——明确写明更新到子技能 SKILL.md 的哪一节。
4. 若归因为"环境错"——记录环境指纹（版本、commit、配置）。
5. 若归因为"检查错"——明确检查脚本本身的修复位置。

## 与现有 IPD 技能的关系

- `db-migration`：DDL 脚本与回滚模板，本包 `ipd-guard-ddl-apply` 是其"提交前自检"配套
- `api-contract`：后端 → 前端契约同步，本包 `ipd-guard-frontend-drift` 是其"前端变更时"的反向配套
- `gen-test`：测试编写规范，本包 `ipd-guard-mock-validity` 是其"测试合法性"的硬卡口

三者一起用覆盖：变更 → 契约通知 → 前端落地 → 测试合法。