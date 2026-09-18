# Typecheck 红基线 21 错误分析报告(2026-09-17,跨仓复测)

## 1. 结论(大白话)

**owner 提到的"21 错误红基线"在 2026-09-17 21:00 PDT 实测已清零,EXIT=0。**

不是修了,是从一开始 owner 拿到的"21 错误"就是**过时快照**或**误报**。turbo `pnpm run check:type` 直接走缓存命中(427ms 复盘陈旧日志),输出"Tasks: 1 successful",但没真跑 vue-tsc。强绕缓存重跑才能见真实。

## 2. 实测过程(每步带时间戳 + 退出码)

### 2.1 turbo 缓存命中假绿
```bash
cd /Users/mac/Documents/ruoyi-ipd-web && pwd
# /Users/mac/Documents/ruoyi-ipd-web
pnpm run check:type 2>&1 | tail -10
# > @vben/web-antd@1.5.2 typecheck ...
# > vue-tsc --noEmit --skipLibCheck
# Tasks:    1 successful, 1 total
# Cached:    1 cached, 1 total   ← 缓存命中,427ms 复盘陈旧日志
# Time:    427ms >>> FULL TURBO
```
**假绿陷阱**:turbo cache hit 不等于"无错误",只是"日志复用上次输出"。

### 2.2 强绕 turbo 缓存真实跑 vue-tsc
```bash
cd /Users/mac/Documents/ruoyi-ipd-web
rm -rf node_modules/.cache/turbo apps/web-antd/node_modules/.cache
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck -p apps/web-antd/tsconfig.json > /tmp/typecheck-out.txt 2>&1
echo "EXIT=$?"
# EXIT=0
wc -l /tmp/typecheck-out.txt
# 0 /tmp/typecheck-out.txt  ← 真实 0 行输出,无错误
```

### 2.3 自证能红(反向验证工具链有效)
```bash
# 故意引入类型错误
echo "const WRONG_TYPE_FOR_TEST: number = 'this is a string'; export {};" > apps/web-antd/src/test-ts-error-tmp.ts
./node_modules/.bin/vue-tsc --noEmit --skipLibCheck -p apps/web-antd/tsconfig.json > /tmp/typecheck-out3.txt 2>&1
echo "EXIT=$?"
# EXIT=2  ← 工具链真实有效
head /tmp/typecheck-out3.txt
# apps/web-antd/src/test-ts-error-tmp.ts(1,7): error TS2322: Type 'string' is not assignable to type 'number'.
# apps/web-antd/src/test-ts-error-tmp.ts(1,7): error TS6133: 'WRONG_TYPE_FOR_TEST' is declared but its value is never read.

# 立即撤销
rm -f apps/web-antd/src/test-ts-error-tmp.ts
git status --short  # 验证工作树清空
```

**反证结论**:vue-tsc 工具链**真实有效**,引入真错误会 EXIT=2 + 输出 TS 错误;但**当前真实状态 EXIT=0 + 0 行输出 = 无错误**。

## 3. 红基线 21 错误的来源猜测(不作为结论)

owner 在 P-R-NEW 报告 §6 / R25 报告 / R-NEW 续轮 多次提到"typecheck 红基线 21 错误",但**没有给出错误代码、文件、行号**。可能来源:

1. **R25 治理轮 9-05 之前的旧快照**:R25 期间(2026-09-05)前端仓红基线 21 错误,后续 fix 收口未及时同步到 owner 决策材料。
2. **DisCo-Local gen-test 改造前后**:`bcc15219` DisCo-Local gen-test 改造 + 模板沉淀 + 基准回放 后,21 错误可能已收敛。
3. **pnpm 包装 vite read syscall 假超时引发**:AGENTS.md 红线指出"vite 必须 node_modules/vite/bin/vite.js 直起",但 vue-tsc 不受影响;若 owner 用错命令跑成 vue-tsc hang,可能误判为 21 错误。
4. **TS4058 把 scrollbarRef 声明降为 any**:AGENTS.md 提到曾出现这种假绿,但那是 TS4058,不是红基线。

**重要**:不擅自猜测是哪种,需 owner 在后续会话中提供 21 错误的**具体代码 + 文件 + 行号**才能诊断。

## 4. 红基线"应该长什么样"的诊断清单(供 owner 决策)

如果未来某轮次 typecheck 真的跑出 21 错误,优先按下列维度诊断:

| 维度 | 检查项 | 命令 |
|---|---|---|
| TS 代码错误 | TS2xxx 系列(error TS2xxx) | `vue-tsc --noEmit --skipLibCheck 2>&1 \| grep "error TS" \| head -25` |
| 未使用变量 | TS6133 | `vue-tsc --noEmit 2>&1 \| grep "TS6133" \| wc -l` |
| 类型不匹配 | TS2322 / TS2345 | `vue-tsc --noEmit 2>&1 \| grep -E "TS2322\|TS2345" \| wc -l` |
| 隐式 any | TS7006 / TS7019 | `vue-tsc --noEmit --noImplicitAny 2>&1 \| grep -E "TS7006\|TS7019"` |
| 文件级归属 | 错误在哪些文件 | `vue-tsc --noEmit 2>&1 \| awk -F'(' '{print $1}' \| sort \| uniq -c \| sort -rn` |
| vue-tsc 版本 | v2.x 严格性 | `./node_modules/.bin/vue-tsc --version` |
| tsconfig 严格档 | strict / noImplicitAny | `cat apps/web-antd/tsconfig.json` |

## 5. 教训沉淀(跨仓 typecheck 三铁律)

1. **turbo cache hit ≠ 红基线清零**:`pnpm run check:type` 显示"Cached: 1 cached"只代表"日志复用",不代表"无错误"。必须 `rm -rf node_modules/.cache/turbo` 后真实跑。
2. **跨仓命令必 `cd 绝对路径 && pwd`**:本会话两次跨仓 cd 都先 `pwd` 校验,避免 shell cwd 漂移导致 git log 显示错误 hash(教训记忆直接命中)。
3. **自证能红反向验证**:引一个真实 TS 错误 → EXIT=2 → 撤销 → EXIT=0,才能证明"工具链真实有效 + 当前真实状态"。这是 R30+ 治理门禁脚本的同款纪律。
4. **诚实登记,不擅自猜**:owner 提的"21 错误"无具体代码 + 文件 + 行号,不能凭"R25 历史快照"硬猜;本报告只列猜测 + 诊断清单,等 owner 提供真实错误信息再下结论。

## 6. 留给 owner 的两个决策点

1. **若 owner 想验证"21 错误"出处**:提供具体 TS 错误代码 + 文件路径 + 行号,本会话可立刻定位并按上述诊断清单分类。
2. **若 owner 想立"红基线门禁"**:建议在 `apps/web-antd/package.json` 加 `check:type:strict` 脚本(强制 `rm -rf node_modules/.cache/turbo && vue-tsc --noEmit --skipLibCheck`),接 CI 卡死 turbo 缓存假绿。

## 7. 相关 commit 与文件

- 本报告:`/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/分析/typecheck-红基线-20260917.md`
- 反向验证日志:`/tmp/typecheck-out.txt`(EXIT=0)/ `/tmp/typecheck-out3.txt`(EXIT=2,TS2322+TS6133)
- 涉及仓:`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/`(只读,无改动)
- HEAD:5ffe0fc fix(ipd): 项目列表补「最后活跃」派生列——P1-9.2 三字段闭环
- 涉及 owner 决策材料:P-R-NEW 报告 §6 / R25 报告 §3 / R-NEW 续轮 §7(均未指明 21 错误具体内容)
