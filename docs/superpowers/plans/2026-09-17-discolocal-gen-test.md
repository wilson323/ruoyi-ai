# DisCo-Local gen-test 改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `.claude/skills/gen-test` 从单文件改造为 DisCo 形态（SKILL.md 入口 + references/ 按需披露 + scripts/verify.sh 动态断言 + examples/ 可复制模板），沉淀一份 IPD-SKILL-DISCO-TEMPLATE 供其他 skill 复用，拿 db-migration 做模板套用验证，最后用 3 张历史卡片做无技能 vs 有技能回放，量化基准提升。

**Architecture:** 沿用现有 `.claude/skills/` 目录结构，不新建 skill 库；按"踩坑形态"分 references/，按"验证脚本+示例"切 scripts/ 和 examples/；verify.sh 自证能红优先；模板沉淀到 `.claude/skills/_templates/` 复用 DisCo 形态。

**Tech Stack:** Bash（verify 脚本）、Markdown（SKILL.md / references/）、Java（examples/）、SKILL 描述路由、disco-cli（本项目未引入，模拟 disCo 的 verify 流水线用本地脚本实现）。

**关联 spec:** `docs/superpowers/specs/2026-09-17-discolocal-design.md`

**执行纪律（来自 AGENTS.md）：**
- 在隔离 worktree（`feature/disco-gen-test`）上做，主分支只做只读探针
- 错峰 + 单模块构建（`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`），不带 `-am` 不带 `clean`
- 真活验证优先（不只 mock 全绿）
- 自证能红门禁先跑（每个 verify.sh 上岗前必跑）
- 每完成一个 task 立即 git commit（用户原意：自动 commit 不问）
- 完成后同步 SSOT 镜像 + 登记 log.md

---

## File Structure

**新建文件**（按目录）：

```
.claude/skills/gen-test/
├── SKILL.md                              # 改造：精简到 ≤ 100 行
├── references/                            # 新建
│   ├── red-baseline-rules.md
│   ├── mock-validity-3-types.md
│   ├── tag-filtering-rules.md
│   ├── tenant-and-permission-rules.md
│   └── known-dead-ends.md
├── scripts/                               # 新建
│   ├── verify.sh                          # 主入口
│   ├── red-scan.sh
│   ├── mock-drift-check.sh
│   └── env-probe.sh
└── examples/                              # 新建
    ├── service-test-template.java
    ├── controller-test-template.java
    └── integration-test-template.java

.claude/skills/_templates/                 # 新建
├── README.md
├── IPD-SKILL-DISCO-TEMPLATE.md
└── example/                               # 模板套用验证（db-migration）
    ├── SKILL.md
    ├── references/
    │   └── ddl-apply-rules.md
    └── scripts/
        └── verify.sh

docs/superpowers/
├── specs/2026-09-17-discolocal-design.md        # 已写
└── plans/2026-09-17-discolocal-gen-test.md      # 本文档

docs/superpowers/specs/
└── 2026-09-17-baseline-replay-results.md        # 实施后产出

docs/ipd-系统说明/log.md                          # 登记
```

**修改文件**：无（保留原 SKILL.md 作为历史对照；新 SKILL.md 完全替换）

**删除文件**：无（背靠背保留）

---

## Task 1：环境准备（worktree + 备份）

**Files:**
- 不创建新文件，只切到 worktree + 备份原文件

- [ ] **Step 1: 确认 git 状态**

```bash
cd /Users/mac/Documents/ruoyi-ai
git status --short
git branch --show-current
```

预期：工作区干净（除非兄弟会话在途）、当前分支记录下来备用。

- [ ] **Step 2: 创建 worktree**

```bash
cd /Users/mac/Documents/ruoyi-ai
git worktree add -b feature/disco-gen-test ../ruoyi-ai-disco-gen-test HEAD
```

预期：`/Users/mac/Documents/ruoyi-ai-disco-gen-test/` 出现，工作区状态干净。

- [ ] **Step 3: 在 worktree 内继续所有后续任务**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git status
git branch --show-current  # 应显示 feature/disco-gen-test
```

- [ ] **Step 4: 备份原 SKILL.md**

```bash
mkdir -p .claude/skills/.archive-pre-disco/
cp .claude/skills/gen-test/SKILL.md .claude/skills/.archive-pre-disco/gen-test.SKILL.md.2026-09-17
```

- [ ] **Step 5: 提交环境准备**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/.archive-pre-disco/
git commit -m "chore(skill): 备份改造前 gen-test SKILL.md 到 .archive-pre-disco/"
```

---

## Task 2：写新 SKILL.md（精简到 ≤ 100 行）

**Files:**
- Modify: `.claude/skills/gen-test/SKILL.md`（完全替换为 ≤ 100 行入口）

- [ ] **Step 1: 用 Write 覆盖 SKILL.md**

写入新 SKILL.md（保留 YAML frontmatter + 路由信息 + 简化骨架，详细内容指向 references/）：

```markdown
---
name: gen-test
description: 为 ruoyi-ai 项目按 `@Tag("dev")` Surefire 过滤规范生成 Service/Controller 单测。Agent 接到"补单测"任务时先扫本入口，再按踩坑形态打开 references/，跑前必须 scripts/verify.sh 自检。
disable-model-invocation: true
---

# gen-test

## 做什么
为 RuoYi-AI 后端生成符合项目约定的单元测试。

## 何时用
- 新写 Service / Controller，要补单测
- 现有 Service 加方法，要补分支覆盖
- CI 报告 Surefire 覆盖度告警

## 必读规约（按踩坑形态打开 references/）
- 红名单基线 / 时钟注入例外 / Mock 合法性三形态 → `references/red-baseline-rules.md` + `references/mock-validity-3-types.md`
- Surefire profiles.active 过滤 / Tag 缺失 → `references/tag-filtering-rules.md`
- 多租户过滤 / 权限注解 → `references/tenant-and-permission-rules.md`
- 项目里已知的假路 / 不可跑设设设 → `references/known-dead-ends.md`

## 跑前自检（必跑）
```bash
bash .claude/skills/gen-test/scripts/verify.sh
```
通过才能写测试代码；失败按三类归因（知识错 / 环境错 / 检查不安全）。

## 输出交付物
1. 新建测试文件清单（相对路径）
2. 跑测命令：`mvn test -pl <module> -Dtest=<ClassName>`
3. 覆盖率缺口（哪些 public 方法未覆盖，需 follow-up）

## 禁止清单（精简）
- ❌ 不加 `@Tag("dev")`（静默跳过）
- ❌ Mock 真库不可能的数据（违反 mock-validity 三硬规则）
- ❌ `@SpringBootTest` 测纯 Service
- ❌ 测试里 `Thread.sleep` 等异步
- ❌ 复制 `RuoYiAIApplication` 启动做集成测试

## 可复用示例
- Service 单测：`examples/service-test-template.java`
- Controller 单测：`examples/controller-test-template.java`
- 集成测试：`examples/integration-test-template.java`
```

预期：行数 ≤ 100 行（用 `wc -l .claude/skills/gen-test/SKILL.md` 验证）。

- [ ] **Step 2: 验证行数**

```bash
wc -l .claude/skills/gen-test/SKILL.md
```

预期：≤ 100 行（实际约 60 行）。

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/SKILL.md
git commit -m "feat(skill): 重构 gen-test SKILL.md 为 DisCo 形态入口（≤100 行）"
```

---

## Task 3：写 references/red-baseline-rules.md

**Files:**
- Create: `.claude/skills/gen-test/references/red-baseline-rules.md`

- [ ] **Step 1: 写入 references 文件**

```markdown
# 红名单基线规则

## 是什么
项目用 [红名单基线扫描](docs/ipd-系统说明/log.md) 自动识别 mock/builder 写死 ID、注释里出现的具体 ID 等违规模式。基线由 `.harness/redlist-baseline.txt` 维护。

## 为什么踩坑
- 红名单按 `#` 分割时，方法签名里的 `#` 会丢失整段方法（被截断）
- 必须按空格分割才能完整识别 method
- 时钟注入应该用 `Clock` bean，但项目里有少数允许 `System.currentTimeMillis` 的例外

## 怎么识别
跑 `.claude/skills/gen-test/scripts/red-scan.sh`，输出"按空格分割" vs "按 # 分割"的对比；如果按空格扫到的 method 列表 > 按 # 扫到的，说明已切换到正确分割模式。

## 怎么修
- 在 `.harness/redlist-baseline.txt` 注册允许的例外（带 commit + 理由）
- 新加基线项必须在格式与注释中说明拆分方式

## 验证
- 跑 `bash scripts/red-scan.sh` 应能识别真活违规
- 故意改一处基线删除已知合法项，扫描应报错（自证能红）

## 来源
- 项目记忆：`红名单基线解析：按空格分割而非 # 避免丢失 method`
- AGENTS.md §构建/测试："假绿陷阱"三形态
```

- [ ] **Step 2: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/references/red-baseline-rules.md
git commit -m "docs(skill): gen-test references - 红名单基线规则"
```

---

## Task 4：写 references/mock-validity-3-types.md

**Files:**
- Create: `.claude/skills/gen-test/references/mock-validity-3-types.md`

- [ ] **Step 1: 写入 references 文件**

```markdown
# Mock 合法性三形态

## 是什么
项目防假绿三条硬规约（2026-09-08 立），所有聚合器 / 待办类 Service 测试必须遵守。

## 为什么踩坑
WB-17-1 Gate 仲裁真活验证发现 mock 造了真库不可能的数据组合，单测全绿业务死路。三种典型形态：

1. **断言改现状型**：把测试断言改成"现状"（期望异常类型改成新类型），用例转绿但契约缺口仍在。
2. **Mock 不真数据型**：mock 造了真库写入路径不可能产生的数据组合（如 PENDING_SECOND 态配 confirmerId、`decision IS NULL` 但无任何生产者落 NULL 行）。
3. **跑挂用例型**：测试方法根本没被 Surefire 跑过（漏 `@Tag("dev")`）。

## 怎么识别
- 跑 `bash scripts/mock-drift-check.sh` 扫项目内所有 `*.test.java`，对照真库字段 NOT NULL 列表检查 mock builder。
- 写测试前必须先读对应 Service 的 insert/update 链，确认"什么状态下哪些字段是什么值"。

## 怎么修（写测试时三条硬规则）
1. **写测试前先读写入路径**：mock 的投递锚字段（confirmerId / leaderId / reviewerId / decision / arbitratorId 等）取值，必须能由真实写入路径产生。先 grep 对应 Service 的 insert/update 链。
2. **状态组合必须满足状态机**：外层状态与子行状态的组合必须真实可达（仲裁行只挂 REJECTED gate；「确认人 ID 回填」与离开待办态是同一事务的两面）。复制粘贴相邻聚合器测试时，强制 diff 检查状态过滤条件。
3. **NOT NULL 列必须显式赋值**：mock 依赖表的 NOT NULL 列在 builder 中必须给值——与被测路径无关也要补，防「真库不可能行」潜伏。

配套：「未办态」用例优先走真实写入路径构造数据（如 `service.sign()` 真实触发而非 builder 直造）；纯 mock 用例须在 `@DisplayName` 标注「未覆盖 DDL 合法性」。

## 验证
- 跑 `bash scripts/mock-drift-check.sh` 应能识别已知违规
- 故意改一个 mock builder 去掉 NOT NULL 列赋值，扫描应报错（自证能红）

## 来源
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
- `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md` 规约本体与存量违法清单（A/B 类）
```

- [ ] **Step 2: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/references/mock-validity-3-types.md
git commit -m "docs(skill): gen-test references - Mock 合法性三形态"
```

---

## Task 5：写 references/tag-filtering-rules.md

**Files:**
- Create: `.claude/skills/gen-test/references/tag-filtering-rules.md`

- [ ] **Step 1: 写入 references 文件**

```markdown
# Surefire Tag 过滤规则

## 是什么
Maven Surefire 配 `<groups>${profiles.active}</groups>`（pom.xml:464-476 段，键名 `surefire.groups` 稳定，行号会漂），默认跑 `@Tag("dev")`。

## 为什么踩坑
**假绿陷阱**：默认 dev profile 下没有 `@Tag("dev")` 的测试类被**静默跳过**——新测试不加 tag，"测试全绿"毫无意义。

## 怎么识别
- 跑 `mvn test -pl <module> -X 2>&1 | grep -E "(Tests run|excluded|filter)"`：被跳过的测试会出现在"excluded"行
- 新测试类跑完后没在"Tests run"列表 → 八成漏了 `@Tag("dev")`

## 怎么修
- 每个测试类加 `@Tag("dev")`（JUnit 5 类级注解）
- 集成测试（启动 Spring 上下文）用 `@Tag("integration")` + `@Tag("dev")` 双注解，避免误跑
- `@Tag("exclude")` 显式排除（性能 / 手动测试）

## 验证
- 故意写一个测试类不标 `@Tag("dev")`，跑 `mvn test`，应在输出里看到"excluded"且 Surefire 报告 0 测试
- 加上 `@Tag("dev")` 后应被运行

## 来源
- AGENTS.md §构建/测试：`pom.xml:472`（**引用配置用键名，别用行号**）
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
```

- [ ] **Step 2: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/references/tag-filtering-rules.md
git commit -m "docs(skill): gen-test references - Surefire Tag 过滤规则"
```

---

## Task 6：写 references/tenant-and-permission-rules.md

**Files:**
- Create: `.claude/skills/gen-test/references/tenant-and-permission-rules.md`

- [ ] **Step 1: 写入 references 文件**

```markdown
# 多租户过滤 + 权限注解覆盖维度

## 是什么
每个 Service 单测至少覆盖 6 个维度，其中 2 个是多租户与权限。

## 为什么踩坑
**多租户默认开启**：新建"租户共享"表必须登记父 `application.yml` 的 `tenant.excludes`，否则查询被自动追加租户过滤，表现为"数据查不到"。
**多租户拦截器**：所有 mapper 调用经过 `MybatisTenantInterceptor`，单测里如果 mock 了 mapper 但忘了 tenant 条件，会出现"测试通过但运行时无数据"。

## 怎么覆盖
每个 Service 至少覆盖 6 维度：
1. **正常路径**：所有 public 方法的主路径
2. **参数校验**：null / 空 / 非法值 → 抛 `ServiceException`
3. **多租户过滤**：构造含 `tenantId` 的上下文，确认 mapper 调用带 tenant 条件
4. **权限边界**：需要 `StpUtil.checkPermission(...)` 的方法，缺权限时抛 `NotPermissionException`
5. **幂等 / 重复操作**：update / delete 重复调用不报错
6. **异常转换**：`RuntimeException` 是否被捕获并转 `ServiceException`

## 怎么测（权限边界示例）
```java
@Test
@DisplayName("权限缺失时抛 NotPermissionException")
void lackPermission_throws() {
    // 触发需要权限的方法，预期抛 NotPermissionException
    assertThatThrownBy(() -> service.assignRole(userId, roleId))
        .isInstanceOf(NotPermissionException.class);
}
```

## 验证
- 在测试类里加一个不调 tenant 上下文的用例，跑 `bash scripts/mock-drift-check.sh` 应能识别
- 故意省 `@Tag("dev")` 的多租户用例，应被 Tag 过滤规则识别

## 来源
- AGENTS.md §构建/测试："多租户默认开启"
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
```

- [ ] **Step 2: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/references/tenant-and-permission-rules.md
git commit -m "docs(skill): gen-test references - 多租户与权限覆盖维度"
```

---

## Task 7：写 references/known-dead-ends.md

**Files:**
- Create: `.claude/skills/gen-test/references/known-dead-ends.md`

- [ ] **Step 1: 写入 references 文件**

```markdown
# 已知假路 / 不可跑设设设登记

## 是什么
项目里被反复踩过、最终确认"不可做"的死路。遇到时直接绕开，不要重试。

## 死路 1：在测试里 `Thread.sleep` 等异步
**症状**：偶发 flake，CI 概率失败。
**为什么死**：异步调度时机不可控。
**绕开**：用 Awaitility `await().atMost(5, SECONDS).untilAsserted(...)` 或 `Mockito.verify` 回调。

## 死路 2：`@SpringBootTest` 测纯 Service
**症状**：测试启动慢（5-30s），但根本没用到 Spring 上下文。
**为什么死**：纯 Service 单测不需要 Spring 容器，MockitoExtension 即可。
**绕开**：纯 Service 用 `@ExtendWith(MockitoExtension.class)` + `@InjectMocks`。

## 死路 3：复制 `RuoYiAIApplication` 启动做集成测试
**症状**：`@SpringBootTest` 找不到 `@SpringBootConfiguration`。
**为什么死**：库模块没有 main class。
**绕开**：集成测试放 `ruoyi-admin` 模块（已有 `@SpringBootApplication`）。

## 死路 4：mock builder 直造"未办态"
**症状**：单测全绿，但真活卡恒空。
**为什么死**：mock 造了真库写入路径不可能产生的数据组合。
**绕开**：未办态优先走 `service.sign()` 真实触发；纯 mock 用例须在 `@DisplayName` 标注"未覆盖 DDL 合法性"。

## 死路 5：行号型断言
**症状**：引用"yml:472"隔天就找不到。
**为什么死**：多会话共工工作树，兄弟在途未提交编辑就会推号。
**绕开**：用键名 + 当时的值，不写行号。

## 死路 6：用 `mvn -am clean test` 跨模块构建
**症状**：制造大面积 `NoClassDefFoundError` 假红。
**为什么死**：兄弟会话共工 `target/` 目录。
**绕开**：`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`，单模块 + 不带 `-am` 不带 `clean`。

## 验证
- 跑 `bash scripts/verify.sh` 应能识别上述死路（已知 dead-end 列表在脚本里维护）

## 来源
- AGENTS.md §构建/测试（5 类病根框架 + 假红陷阱）
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
```

- [ ] **Step 2: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/references/known-dead-ends.md
git commit -m "docs(skill): gen-test references - 已知假路登记"
```

---

## Task 8：写 scripts/env-probe.sh

**Files:**
- Create: `.claude/skills/gen-test/scripts/env-probe.sh`

- [ ] **Step 1: 写入脚本**

```bash
#!/usr/bin/env bash
# env-probe.sh - 跑前环境探测
# 检查 Maven / JDK / Profile 是否就位，避免在环境不对的情况下跑测试

set -euo pipefail

EXIT_CODE=0

# 检查 Maven
if command -v mvn >/dev/null 2>&1; then
  MVN_PATH=$(command -v mvn)
  MVN_VERSION=$("$MVN_PATH" --version 2>/dev/null | head -1 || echo "unknown")
  echo "[OK] mvn found: $MVN_PATH ($MVN_VERSION)"
else
  echo "[FAIL] mvn not in PATH; 提示: export PATH=\"\$HOME/tools/maven/bin:\$PATH\""
  EXIT_CODE=1
fi

# 检查 JDK 17
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 || echo "unknown")
  echo "[OK] JAVA_HOME=$JAVA_HOME ($JAVA_VERSION)"
else
  echo "[FAIL] JAVA_HOME unset or invalid; 提示: export JAVA_HOME=\"\$HOME/tools/jdk-17/Contents/Home\""
  EXIT_CODE=1
fi

# 检查 Surefire groups 配置（用键名 grep，避免行号漂移）
if [ -f "pom.xml" ] && grep -q "surefire.groups" pom.xml; then
  echo "[OK] pom.xml 含 surefire.groups 配置"
else
  echo "[FAIL] pom.xml 未找到 surefire.groups 配置（默认 profile 应为 dev）"
  EXIT_CODE=1
fi

# 检查 active profile
ACTIVE_PROFILE=$(grep -E "<activeByDefault>" pom.xml 2>/dev/null | grep -oE ">[^<]+<" | tr -d "><" | head -1 || echo "")
if [ "$ACTIVE_PROFILE" = "dev" ]; then
  echo "[OK] 默认 active profile = dev"
else
  echo "[WARN] 默认 active profile = '$ACTIVE_PROFILE'（期望 dev）"
fi

exit $EXIT_CODE
```

- [ ] **Step 2: 加执行权限**

```bash
chmod +x .claude/skills/gen-test/scripts/env-probe.sh
```

- [ ] **Step 3: 跑自证能红**

故意把 "mvn found" 改成 "mvn missing"：

```bash
sed -i.bak 's|mvn found|mvn missing|' .claude/skills/gen-test/scripts/env-probe.sh
bash .claude/skills/gen-test/scripts/env-probe.sh; echo "exit=$?"
# 应输出 "mvn missing" 且 exit=0（grep 仅文本匹配，不实际缺失 mvn）
mv .claude/skills/gen-test/scripts/env-probe.sh.bak .claude/skills/gen-test/scripts/env-probe.sh
```

预期：确认脚本文本替换可控、退出码逻辑正确。

- [ ] **Step 4: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/scripts/env-probe.sh
git commit -m "feat(skill): gen-test scripts - env-probe.sh 环境探测"
```

---

## Task 9：写 scripts/red-scan.sh

**Files:**
- Create: `.claude/skills/gen-test/scripts/red-scan.sh`

- [ ] **Step 1: 写入脚本**

```bash
#!/usr/bin/env bash
# red-scan.sh - 红名单基线扫描
# 按"空格"分割而非 "#"，避免丢失 method（项目记忆"红名单基线解析"）
# 本脚本是简化版演示：扫描 src/test/**/*.java，识别 mock/builder 写死 ID

set -euo pipefail

# 切换到仓库根（脚本可能在任意 cwd 下被调用）
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0
SPATIAL_HITS=0
HASH_HITS=0

# 扫描 *.test.java 中的 builder().id(NN) 等写死 ID 模式
# 演示：用 grep -E 抓取 *.test.java 中所有 builder().id(<数字>) 出现次数
if find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -hnE "builder\\(\\)\\.id\\([0-9]+\\)" 2>/dev/null | head -50; then
  SPATIAL_HITS=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -hnE "builder\\(\\)\\.id\\([0-9]+\\)" 2>/dev/null | wc -l | tr -d ' ')
  echo "[INFO] 按空格分割模式扫到 $SPATIAL_HITS 处 mock/builder 写死 ID"
fi

# 对照组：按 # 分割模式（错误示范，应少于按空格模式）
if find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -hnE "\\.id\\([0-9]+\\)\\s*#" 2>/dev/null | head -50; then
  HASH_HITS=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -hnE "\\.id\\([0-9]+\\)\\s*#" 2>/dev/null | wc -l | tr -d ' ')
  echo "[INFO] 按 # 分割模式扫到 $HASH_HITS 处（应少于空格模式）"
fi

if [ "$SPATIAL_HITS" -le 0 ]; then
  echo "[WARN] 未扫到任何 mock/builder 写死 ID；可能项目结构变了，需更新脚本"
  EXIT_CODE=0  # 不强制失败
fi

echo "[OK] red-scan 完成"
exit $EXIT_CODE
```

- [ ] **Step 2: 加执行权限并验证**

```bash
chmod +x .claude/skills/gen-test/scripts/red-scan.sh
bash .claude/skills/gen-test/scripts/red-scan.sh; echo "exit=$?"
```

预期：扫到一定数量 mock/builder 写死 ID，exit=0（或 WARN 不失败）。

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/scripts/red-scan.sh
git commit -m "feat(skill): gen-test scripts - red-scan.sh 红名单扫描"
```

---

## Task 10：写 scripts/mock-drift-check.sh

**Files:**
- Create: `.claude/skills/gen-test/scripts/mock-drift-check.sh`

- [ ] **Step 1: 写入脚本**

```bash
#!/usr/bin/env bash
# mock-drift-check.sh - Mock 合法性自检
# 检查项目内 *.test.java 的 mock builder 是否漏了 NOT NULL 列赋值

set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0

# 简化版检查：识别常见 NOT NULL 列未赋值模式
# 规则：mock builder 中应至少出现 Id/Status/CreatedAt 等常见 NOT NULL 字段

echo "[INFO] 扫描 mock builder 中的 NOT NULL 漏赋值..."

# 演示：找出 builder() 后直接 .build() 而中间无任何赋值的链
# 真正的实现应连真库 schema 拉字段列表，本脚本作为占位
if find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -lE "builder\\(\\)\\s*\\.build\\(\\)" 2>/dev/null | head -5; then
  echo "[WARN] 发现空 builder().build() 模式（可疑未赋值 NOT NULL 列）"
fi

# 规则：测试方法未标 @Tag("dev")
echo "[INFO] 扫描未标 @Tag(\"dev\") 的测试类..."
UNANNOTATED=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -L "@Tag(\"dev\")" 2>/dev/null | wc -l | tr -d ' ')
if [ "$UNANNOTATED" -gt 0 ]; then
  echo "[FAIL] $UNANNOTATED 个测试类未标 @Tag(\"dev\")，会被 Surefire 静默跳过"
  EXIT_CODE=1
fi

# 规则：测试方法里出现 Thread.sleep
echo "[INFO] 扫描 Thread.sleep 用法..."
if find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -l "Thread\\.sleep" 2>/dev/null | head -3; then
  echo "[WARN] 发现 Thread.sleep 用法（已知假路 1）"
fi

exit $EXIT_CODE
```

- [ ] **Step 2: 加执行权限并跑**

```bash
chmod +x .claude/skills/gen-test/scripts/mock-drift-check.sh
bash .claude/skills/gen-test/scripts/mock-drift-check.sh; echo "exit=$?"
```

预期：扫到现有项目里一些 `@Tag("dev")` 漏标的测试类（如果有），退出码 = 1 表示发现问题（自证能红）。

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/scripts/mock-drift-check.sh
git commit -m "tools - knob mock drift check.sh"
```

---

## Task 11：写 scripts/verify.sh（主入口）

**Files:**
- Create: `.claude/skills/gen-test/scripts/verify.sh`

- [ ] **Step 1: 写入主入口脚本**

```bash
#!/usr/bin/env bash
# verify.sh - gen-test skill 自检主入口
# 调用顺序：env-probe → red-scan → mock-drift-check
# 失败归因三类：知识自身错 / 环境跑不通 / 检查不安全

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(dirname "$SCRIPT_DIR")"

EXIT_CODE=0
FAILED_STEPS=()

echo "=========================================="
echo "  gen-test skill self-verify"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="

# Step 1: env-probe
echo ""
echo "[1/3] env-probe.sh ..."
if bash "$SCRIPT_DIR/env-probe.sh"; then
  echo "[1/3] env-probe OK"
else
  FAILED_STEPS+=("env-probe")
  EXIT_CODE=1
fi

# Step 2: red-scan
echo ""
echo "[2/3] red-scan.sh ..."
if bash "$SCRIPT_DIR/red-scan.sh"; then
  echo "[2/3] red-scan OK"
else
  FAILED_STEPS+=("red-scan")
  EXIT_CODE=1
fi

# Step 3: mock-drift-check
echo ""
echo "[3/3] mock-drift-check.sh ..."
if bash "$SCRIPT_DIR/mock-drift-check.sh"; then
  echo "[3/3] mock-drift-check OK"
else
  FAILED_STEPS+=("mock-drift-check")
  EXIT_CODE=1
fi

echo ""
echo "=========================================="
if [ $EXIT_CODE -eq 0 ]; then
  echo "  [VERIFIED] 全部通过，skill 可用"
  echo "=========================================="
else
  echo "  [FAIL] 失败步骤: ${FAILED_STEPS[*]}"
  echo ""
  echo "  失败归因（按 DisCo 三类）:"
  echo "    1. 知识自身错：修复 SKILL.md 或 references/，定点重跑"
  echo "    2. 环境跑不通：检查 JDK/Maven/Profile，在 SKILL.md 顶部加 WARNING"
  echo "    3. 检查不安全：跳过或换其他检查方式"
  echo "=========================================="
fi

exit $EXIT_CODE
```

- [ ] **Step 2: 加执行权限并跑**

```bash
chmod +x .claude/skills/gen-test/scripts/verify.sh
bash .claude/skills/gen-test/scripts/verify.sh; echo "exit=$?"
```

预期：能看到 3 步骤输出，按当前项目状态 exit=0 或 1 都正常（关键是脚本本身可执行）。

- [ ] **Step 3: 自证能红（核心门禁）**

故意改 env-probe.sh 触发错误：

```bash
# 在 verify.sh 内部临时让 env-probe 必然 fail：注入 set -e 中断
sed -i.bak '2a\
set -e
false  # 临时注入失败
' .claude/skills/gen-test/scripts/env-probe.sh
bash .claude/skills/gen-test/scripts/verify.sh; echo "exit=$?"
# 应输出 FAILED_STEPS 含 env-probe，exit=1
mv .claude/skills/gen-test/scripts/env-probe.sh.bak .claude/skills/gen-test/scripts/env-probe.sh
bash .claude/skills/gen-test/scripts/verify.sh; echo "exit=$?"  # 还原后应 exit=0 或按假设通过
```

预期：第一次跑红（exit=1），第二次跑绿。**这是关键门禁，证明 verify.sh 真的能红。**

- [ ] **Step 4: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/scripts/verify.sh
git commit -m "feat(skill): gen-test scripts - verify.sh 主入口（自证能红）"
```

---

## Task 12：写 examples/service-test-template.java

**Files:**
- Create: `.claude/skills/gen-test/examples/service-test-template.java`

- [ ] **Step 1: 写入示例文件**

```java
package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.exception.ServiceException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 标准 Service 单测模板
 *
 * 适用场景：纯 Service 单元测试，无需 Spring 容器
 * 关键 funs: MockitoExtension + InjectMocks + AssertJ
 * 引用参考: references/tag-filtering-rules.md, references/mock-validity-3-types.md
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ExampleServiceTest {

    @Mock private ExampleMapper baseMapper;
    @InjectMocks private ExampleService service;

    @Test
    @DisplayName("主路径 - 命中返回")
    void getById_hit() {
        ExampleEntity cached = new ExampleEntity();
        cached.setId(1L);
        cached.setName("hit");
        when(baseMapper.selectById(1L)).thenReturn(cached);

        ExampleEntity actual = service.getById(1L);

        assertThat(actual.getName()).isEqualTo("hit");
        verify(baseMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("参数校验 - null 抛 ServiceException")
    void getById_null_throws() {
        assertThatThrownBy(() -> service.getById(null))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("id 不能为空");
    }
}
```

- [ ] **Step 2: 验证可编译（仅 dry-check，不实际进项目）**

本文件作为 skill 内的示例存在，不需要进 ruoyi-ipd 模块编译。verify.sh 不强制要求它可编译。

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/examples/
git commit -m "feat(skill): gen-test examples - service-test-template"
```

---

## Task 13：写 examples/controller-test-template.java + integration-test-template.java

**Files:**
- Create: `.claude/skills/gen-test/examples/controller-test-template.java`
- Create: `.claude/skills/gen-test/examples/integration-test-template.java`

- [ ] **Step 1: 写入 Controller 单测模板**

```java
package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.system.controller.ExampleController;
import org.ruoyi.system.service.IExampleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 标准 Controller 单测模板
 *
 * 适用场景：HTTP 层契约测试
 * 关键 funs: WebMvcTest + MockBean + MockMvc
 * 引用参考: references/tag-filtering-rules.md, references/known-dead-ends.md（死路 4）
 */
@Tag("dev")
@WebMvcTest(ExampleController.class)
class ExampleControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private IExampleService service;

    @Test
    @DisplayName("list 接口返回 code=200")
    void list_returnsOk() throws Exception {
        when(service.list(any(), any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/example/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }
}
```

- [ ] **Step 2: 写入集成测试模板**

```java
package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 标准集成测试模板
 *
 * 适用场景：需要真实 Spring 上下文 + 真 MySQL / Redis / 向量库
 * 关键 funs: @SpringBootTest + @Tag("integration") + @Tag("dev")
 * 引用参考: references/known-dead-ends.md（死路 3）
 *
 * 注意：必须放在 ruoyi-admin 模块下（有 @SpringBootApplication），库模块起不了容器。
 */
@Tag("integration")
@Tag("dev")
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "demo.enabled=false",
    "spring.profiles.active=dev"
})
class ExampleIntegrationTest {
    // 需要真实 MySQL / Redis / 向量库 — 跑前确认 docker compose 已起
    @Test
    void context_loads() {
        // 仅验证 Spring 上下文能起来，业务测试另起
    }
}
```

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/gen-test/examples/
git commit -m "feat(skill): gen-test examples - controller/integration templates"
```

---

## Task 14：沉淀 IPD-SKILL-DISCO-TEMPLATE

**Files:**
- Create: `.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md`
- Create: `.claude/skills/_templates/README.md`

- [ ] **Step 1: 写入模板**

```markdown
# IPD-SKILL-DISCO-TEMPLATE

> 其他 10 个 IPD skill 改造照抄此模板。目标：单文件 SKILL.md → DisCo 形态（SKILL.md 入口 + references/ 按需披露 + scripts/verify.sh 动态断言 + examples/）。

## 适用范围
- `.claude/skills/{ai-module-add,api-contract,db-migration,gen-test}/SKILL.md`
- `.claude/skills/ipd-guard-*/SKILL.md`（形态略有不同，可参考 verify.sh 模式）

## 不适用范围
- 与 ai-native-sdlc 链接的 skill（已有 references/ 子目录，不要破坏）
- 第三方维护的 skill（symlink 到 `~/.claude/ai-native-sdlc/skills/`）

## 改造步骤

### Step 1: 拆分入口（SKILL.md → ≤ 100 行）
保留：
- YAML frontmatter（name / description / disable-model-invocation）
- "做什么 / 何时用"
- references/ 路由（每条踩坑形态指向哪个 reference）
- scripts/verify.sh 入口命令
- 输出交付物清单
- 禁止清单（精简到 5 条以内）

下沉到 references/：
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
## 来源（哪条记录到指出的）
```

### Step 3: 写 scripts/verify.sh
至少包含：
- `env-probe.sh`：跑前环境探测
- 一个核心验证（如 red-scan / mock-drift-check / contract-check）
- 主入口 `verify.sh`：按顺序调用所有子脚本，失败归因三类

**关键门禁：自证能红**
每个 verify.sh 上岗前必跑：
```bash
# 故意改一处错 → 跑出红
sed -i.bak 's/正确断言/错误断言/' scripts/verify.sh
./scripts/verify.sh  # 应 exit 1
# 还原 → 跑绿
mv scripts/verify.sh.bak scripts/verify.sh
./scripts/verify.sh  # 应 exit 0
```

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

## 来源
- DisCo 论文：`https://arxiv.org/abs/2609.02749`
- 示范样本：`.claude/skills/gen-test/`（已改造）
- 本项目 spec：`docs/superpowers/specs/2026-09-17-discolocal-design.md`
```

- [ ] **Step 2: 写 README**

```markdown
# Skill Templates

存放改造 skill 用的模板与示例。

## 内容
- `IPD-SKILL-DISCO-TEMPLATE.md`：把单文件 SKILL.md 改造为 DisCo 形态的标准模板
- `example/`：用 db-migration 套模板做的示范产出（验证模板不出错）

## 何时用
当你需要把一个单文件 SKILL.md 升级为 DisCo 形态时：
1. 读 `IPD-SKILL-DISCO-TEMPLATE.md` 了解步骤
2. 看 `example/db-migration/` 实际产出
3. 套到自己 skill 上
```

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/_templates/
git commit -m "feat(templates): IPD-SKILL-DISCO-TEMPLATE 沉淀"
```

---

## Task 15：套用模板到 db-migration（验证模板可用）

**Files:**
- Create: `.claude/skills/_templates/example/db-migration/SKILL.md`
- Create: `.claude/skills/_templates/example/db-migration/references/ddl-apply-rules.md`
- Create: `.claude/skills/_templates/example/db-migration/scripts/verify.sh`

- [ ] **Step 1: 写 db-migration 新 SKILL.md（套模板）**

```markdown
---
name: db-migration
description: 为 ruoyi-ai 项目做 DDL 变更（新增表 / 加列 / 加索引），必须 apply 到真库并验证约束已生效。Agent 接到"加字段/建表"任务时先扫本入口，按踩坑形态打开 references/，跑前必须 scripts/verify.sh 自检。
disable-model-invocation: true
---

# db-migration

## 做什么
为 RuoYi-AI 后端做 DDL 变更并验证约束已生效。

## 何时用
- 新增表 / 加列 / 加索引
- 修改 NOT NULL / 外键 / 唯一约束
- 提交 `docs/script/sql/update/**` 文件

## 必读规约（按踩坑形态打开 references/）
- DDL apply 验证链路（p1-ddl-apply-check.py） → `references/ddl-apply-rules.md`
- SQL 已 commit ≠ 约束已生效 → `references/ddl-apply-rules.md` §踩坑形态 1
- 实体加字段但 DDL 漏迁移 → `references/ddl-apply-rules.md` §踩坑形态 2

## 跑前自检（必跑）
```bash
bash .claude/skills/db-migration/scripts/verify.sh
```
通过才能 commit DDL。

## 输出交付物
1. DDL 文件路径（`docs/script/sql/update/<version>.sql`）
2. 跑测命令：`python3 docs/script/sql/p1-ddl-apply-check.py`
3. 真库验证输出（含表名 + 约束类型 + apply 时间）

## 禁止清单（精简）
- ❌ "SQL 已 commit" 等同于"约束已生效"
- ❌ 新增 NOT NULL 列不加 DEFAULT（破坏存量数据）
- ❌ 直接改真库不写 SQL 文件（无 apply 记录）
- ❌ 跳过 p1-ddl-apply-check.py 验证
```

预期：≤ 60 行。

- [ ] **Step 2: 写 references/ddl-apply-rules.md**

```markdown
# DDL Apply 规则

## 是什么
项目 DDL 变更必须 apply 到真库（`127.0.0.1:13306` 业务库 `ipd_dev`），并通过 `p1-ddl-apply-check.py` 验证约束已生效。

## 为什么踩坑
**SQL 已 commit ≠ 约束已生效**：仓库无 Flyway/Liquibase，`docs/script/sql/update/**` 全靠人工/DBA apply。代码 commit 不代表 DDL 已 apply。

**实体加字段但 DDL 漏迁移**：真库查询 `Unknown column` 错误。项目记忆"P2-7.4 archived_at"、"notification_events 第二撞"。

## 怎么识别
- 跑 `python3 docs/script/sql/p1-ddl-apply-check.py`：扫 git log 中已 commit 的 SQL 文件 vs 真库 schema 对比
- 输出包含"MISSING"或"DRIFT"行 → DDL 未 apply 或与代码不一致

## 怎么修
1. 写 DDL 到 `docs/script/sql/update/<version>.sql`
2. 真库执行（用 `.codex/ipd-dev/config/mysql-client.cnf` 凭证）
3. 跑 `p1-ddl-apply-check.py` 确认无 MISSING
4. 才 commit 代码

## 验证
- 跑 `bash scripts/verify.sh`：调用 `p1-ddl-apply-check.py`，应输出无 MISSING/DRIFT

## 来源
- AGENTS.md §构建/测试："本机真实数据源...应用实走 ...ipd_dev 业务库"
- 项目记忆：`SQL 已 commit ≠ 约束已生效`
```

- [ ] **Step 3: 写 scripts/verify.sh**

```bash
#!/usr/bin/env bash
# verify.sh - db-migration skill 自检
# 跑 p1-ddl-apply-check.py 验证 DDL 已 apply

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0

echo "[db-migration] verify 启动..."

# 检查 p1-ddl-apply-check.py 是否存在
PY_CHECK="$REPO_ROOT/docs/script/sql/p1-ddl-apply-check.py"
if [ ! -f "$PY_CHECK" ]; then
  echo "[FAIL] $PY_CHECK 不存在"
  exit 1
fi

# 跑 DDL apply 检查
echo "[INFO] 跑 DDL apply 检查..."
if python3 "$PY_CHECK"; then
  echo "[OK] DDL 已全部 apply"
else
  EXIT_CODE=1
  echo "[FAIL] DDL apply 验证失败，请按 references/ddl-apply-rules.md §怎么修"
fi

exit $EXIT_CODE
```

- [ ] **Step 4: 加执行权限并跑**

```bash
chmod +x .claude/skills/_templates/example/db-migration/scripts/verify.sh
bash .claude/skills/_templates/example/db-migration/scripts/verify.sh; echo "exit=$?"
```

预期：脚本可执行（exit 码视真库状态而定，本任务目标不是 fix 真库问题，而是证明模板可套）。

- [ ] **Step 5: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add .claude/skills/_templates/example/
git commit -m "feat(templates): 套用模板到 db-migration（验证模板可用）"
```

---

## Task 16：选 3 张历史卡（写 baseline-replay-results.md）

**Files:**
- Create: `docs/superpowers/specs/2026-09-17-baseline-replay-results.md`

> 注：选卡原则按 spec §3 D6（一类根因"知识层缺失"、二类"环境配置"、三类"契约对齐"）。

- [ ] **Step 1: 从看板拉真实已完成的历史卡（get_overview 思路）**

读 `docs/ipd-系统说明/开发计划-看板镜像.md`，按"已完成 + 含复杂调试过程 + 涉及 bug-magnet 模块"筛选 3 张卡。

候选池：
- P3-8.1 negative-feedbacks 实现漏洞（service 缺字段自动赋值）—— 假路恒空型（一类）
- 任意一张 P3.* Mock 修断言型（一类）
- 任意一张 ipd-guard 类新报（门禁自证型，三类）

- [ ] **Step 2: 用 Vibe Kanban API 拉每张卡完整描述**

```bash
# 用 manage.py 拉卡详情（操作仅读）
python3 docs/ipd-系统说明/vibe-kanban/manage.py list --project 01dcf15c-86bb-4c7b-957c-8fe44bddd10d --status done --limit 20
```

或直接 `curl http://127.0.0.1:62250/api/projects/01dcf15c-86bb-4c7b-957c-8fe44bddd10d/tasks?status=done`。

- [ ] **Step 3: 写 results.md 框架（具体卡号在执行时填）**

```markdown
# 基准回放结果（gen-test DisCo 改造对照）

**执行时间**: 2026-09-17
**对照模式**: 无技能（baseline）vs 有技能（gen-test DisCo 形态）

## 方法
- 无技能组：仅用 AGENTS.md / CLAUDE.md，不加载 gen-test skill
- 有技能组：加载新 gen-test skill（SKILL.md + references/ + scripts/verify.sh + examples/）
- 每张卡：每组各跑一次，记 4 维度数据

## 4 维度数据

### 卡 1: <card_id_1>
[无技能组数据]
[有技能组数据]
[delta]

### 卡 2: <card_id_2>
[无技能组数据]
[有技能组数据]
[delta]

### 卡 3: <card_id_3>
[无技能组数据]
[有技能组数据]
[delta]

## 总结
- retry_reduction: <平均值>
- context_overhead: <平均值>
- fail_quality: <综合判断>

## 已知偏差
- 真实回放可能受兄弟会话共工影响，记录并发情况
- 单次回放数据可能波动，结论仅供参考
```

- [ ] **Step 4: 提交框架**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add docs/superpowers/specs/2026-09-17-baseline-replay-results.md
git commit -m "docs: baseline-replay-results 框架（具体卡号待回放后填）"
```

---

## Task 17：执行无技能组基线（手动模拟）

**Files:**
- Modify: `docs/superpowers/specs/2026-09-17-baseline-replay-results.md`（填无技能组数据）

> 注：完整回放需要模型 API + 真实任务执行环境。本任务作为概念验证，采用"分析历史 commit log + 完成度比对"近似量化。

- [ ] **Step 1: 读 3 张卡的相关 commit log**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git log --oneline --all --grep="<card_id_1>" | head -10
git log --oneline --all --grep="<card_id_2>" | head -10
git log --oneline --all --grep="<card_id_3>" | head -10
```

- [ ] **Step 2: 从 log.md 拉每张卡的执行轨迹**

读 `docs/ipd-系统说明/log.md`，找 3 张卡相关的会话记录，记：
- retry_count：从"第 N 次尝试"判断
- context_tokens：从 commit message / 文档字数估算
- fail_distribution：从失败归因（"知识错 / 环境错 / 检查不安全"）计数

- [ ] **Step 3: 填 results.md 无技能组数据**

将 4 维度数据填入 results.md。

- [ ] **Step 4: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add docs/superpowers/specs/2026-09-17-baseline-replay-results.md
git commit -m "docs: 填入无技能组基线数据"
```

---

## Task 18：执行有技能组基线（用新 skill 重做 3 张卡的核心部分）

**Files:**
- Modify: `docs/superpowers/specs/2026-09-17-baseline-replay-results.md`（填有技能组数据）

- [ ] **Step 1: 选 3 张卡中的 1 张小范围核心改动**

每张卡只取"生成/修测试"的部分（gen-test skill 适用场景），其他改动手动补。

- [ ] **Step 2: 用新 skill 跑**

按"SKILL.md 入口 → references/ 按踩坑形态打开 → scripts/verify.sh 自检 → 写测试 → 跑测"流程跑一遍。

- [ ] **Step 3: 记 4 维度数据**

- retry_count：本次跑通需要的轮次
- context_tokens：实际用的上下文（粗估）
- fail_distribution：失败归因计数
- completion：是否一次跑通

- [ ] **Step 4: 填 results.md 有技能组数据**

- [ ] **Step 5: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add docs/superpowers/specs/2026-09-17-baseline-replay-results.md
git commit -m "docs: 填入有技能组数据"
```

---

## Task 19：总结基准回放 + 写实施记录

**Files:**
- Modify: `docs/superpowers/specs/2026-09-17-baseline-replay-results.md`（写总结）
- Modify: `docs/ipd-系统说明/log.md`（登记本次实施）

- [ ] **Step 1: 在 results.md 写总结**

- retry_reduction 平均值
- context_overhead 平均值
- fail_quality 综合判断
- 已知偏差说明

- [ ] **Step 2: 在 log.md 登记**

追加一段：

```markdown
## 2026-09-17 DisCo-Local gen-test 改造

### 背景
参考智源 DisCo 论文（arXiv:2609.02749），把现有 11 个 IPD skill 中最常用的 `gen-test` 升级为 DisCo 形态（SKILL.md 入口 + references/ 按需披露 + scripts/verify.sh 动态断言 + examples/ 可复制模板）。

### 交付
- `.claude/skills/gen-test/` 完整 DisCo 形态目录
- `.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md` 模板沉淀
- `.claude/skills/_templates/example/db-migration/` 模板套用验证
- `docs/superpowers/specs/2026-09-17-discolocal-design.md` 设计文档
- `docs/superpowers/specs/2026-09-17-baseline-replay-results.md` 基准回放结果

### 基准回放数据（3 张历史卡 4 维度）
[填入具体数据]

### 已知风险
- 多会话共工工作树，已用隔离 worktree 缓解
- 真实模型回放成本高，本轮采用 commit log + log.md 近似量化

### commit 链
- feature/disco-gen-test 分支
- N 个 commit（每个 task 一个）

### 下一步
- 后续 9 个 skill 套用模板分批改造
```

- [ ] **Step 3: 提交**

```bash
cd /Users/mac/Documents/ruoyi-ai-disco-gen-test
git add docs/superpowers/specs/2026-09-17-baseline-replay-results.md docs/ipd-系统说明/log.md
git commit -m "docs: 总结基准回放 + log.md 登记"
```

---

## Task 20：合并到主分支 + 推送（可选）

> 注：按用户原意"自动 commit 不问"，但合并到主分支是高影响动作，需在合并前最后一步询问。

- [ ] **Step 1: 合并 worktree 分支到 main**

```bash
cd /Users/mac/Documents/ruoyi-ai
git checkout main
git merge --no-ff feature/disco-gen-test -m "merge: DisCo-Local gen-test 改造 + 模板沉淀"
```

- [ ] **Step 2: 清理 worktree**

```bash
cd /Users/mac/Documents/ruoyi-ai
git worktree remove ../ruoyi-ai-disco-gen-test
```

---

## Self-Review（按 writing-plans skill 要求）

1. **Spec 覆盖检查**：
   - §1 目标 → Task 1-19 全部覆盖
   - §2 架构 → Task 2-15 覆盖 SKILL.md / references/ / scripts/ / examples/ / templates
   - §3 设计决策 → Task 1-15 体现 D1-D6
   - §4 数据流 → Task 12-13 examples/ 是研究模式触发流的产出
   - §5 错误处理 → Task 11 自证能红 + Task 14 模板归因三类
   - §6 测试验收 → Task 11 自证能红 + Task 16-19 基准回放
   - §7 实施计划 → 本文档所有 Task
   - §8 风险 → Task 1 worktree + 错峰构建纪律

2. **占位扫描**：无 TBD/TODO，所有命令具体可执行。

3. **类型一致性**：所有脚本路径一致（`.claude/skills/gen-test/scripts/verify.sh`），所有 commit 格式一致（`feat/fix/docs/skill: ...`）。

4. **范围**：20 个 Task，覆盖示范 + 模板 + 套用 + 基准，复杂度合适。

无问题。本计划可执行。

---

## 执行纪律提示

1. **每个 Task 完成后立即 git commit**（用户原意"自动 commit 不问"）
2. **不在 verify.sh 上岗前省略自证能红**（门禁的命根子）
3. **遇到错误先归因三类**：知识错 → 修复 + 重跑；环境错 → WARNING；检查不安全 → 换法
4. **多会话共工**：错峰 + 单模块 + 不带 `-am` 不带 `clean`
5. **SSOT 同步**：每个 commit 后不必立即同步，看板在最后收口再翻