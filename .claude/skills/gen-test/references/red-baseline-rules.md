# 红名单基线规则

## 是什么
项目用红名单基线扫描自动识别 mock/builder 写死 ID、注释里出现的具体 ID 等违规模式。基线由 `.harness/redlist-baseline.txt` 维护。

## 为什么踩坑
- 红名单按 `#` 分割时，方法签名里的 `#` 会丢失整段方法（被截断）
- 必须按空格分割才能完整识别 method
- 时钟注入应该用 `Clock` bean，但项目里有少数允许 `System.currentTimeMillis` 的例外（开发约定文档 `development_code_specification/时钟注入模式` 立）

## 怎么识别
跑 `bash .claude/skills/gen-test/scripts/red-scan.sh`，输出"按空格分割"扫到的数量。如果数量低于预期，说明已切换到错误模式。

## 怎么修
- 在 `.harness/redlist-baseline.txt` 注册允许的例外（带 commit + 理由）
- 新加基线项必须在格式与注释中说明拆分方式
- 时钟注入改用 `Clock` bean（避免 `System.currentTimeMillis` 硬编码到测试里）

## 验证
- 跑 `bash scripts/red-scan.sh` 应能识别真活违规
- 故意删除 `.harness/redlist-baseline.txt` 一行合法例外，扫描应报错（自证能红）

## 来源
- 项目记忆：`红名单基线解析：按空格分割而非 # 避免丢失 method`
- 项目记忆：`时钟注入模式：静态嵌套类例外保留 System.currentTimeMillis`
- AGENTS.md §构建/测试："假绿陷阱"三形态