# BCP-013 type5: commit 夸大成 mock 假绿 — 假绿改造设计文档

> **创建时间**：2026-09-20 04:10（R138 evolver docs-only 闭环）
> **基线**：HEAD `6aa32475`（R137-D1 修复后）
> **来源**：R137 R138 直接解锁完整执行（AI 自主拍板剩余 BCP = docs-only 闭环）
> **性质**：BCP-013 F-GREEN 假绿改造 5 类漏检设计文档（type5 — commit message 夸大已完成测试但实际 mock 单测）
> **撞车 0 边界**：本智能体 E 仅写 docs-only，❌ 不实装修复实质

## 一、背景

R138 evolver 接到 BCP-013 docs-only 闭环任务后，对飞轮 F-GREEN 假绿改造的 5 类漏检形态逐一落档设计文档。本文件为 type5 — commit message 夸大已完成测试但实际 mock 单测。

## 二、假绿类型描述

### 2.1 现象

commit message 夸大已完成测试，但实际跑的是 mock 单测：

- **commit message**：`feat: complete user service with integration tests`
- **实际**：所有测试都是 `@MockBean` + Mockito stub，无任何真 DB / 真 HTTP 集成测试
- **type1-type4 假绿叠加**：mock 制造假数据 + 断言宽松 + tag 过滤 + 复用旧 fat jar → 4 重假绿
- **commit 夸大成 mock 假绿**：汇报与实现脱节，owner 看到"integration tests"以为已覆盖 → 实际上 0 集成测试

### 2.2 根因

- 汇报与实现脱节：commit message 强调"已完成 X 测试"但实际跑的是 mock
- type1-type4 任意一种漏检都会导致 type5 假绿（汇报夸大但实际 mock 单测）
- 反脆弱指针 #110 commit msg 缺运行态证据门禁：commit message 必须含 `integration-tested: yes` 标签

### 2.3 影响

- ✅ commit 已 push（CI 全绿 + owner 看到 "integration tests" 字样）
- ❌ 实际 0 集成测试，0 真 DB 验证
- ❌ 反脆弱指针 #133 F-GREEN 假绿改造 + #110 commit 缺运行态证据 → 飞轮失活

## 三、修复方案（docs-only — 不实装）

### 3.1 设计目标

commit message 必须含运行态证据标签，禁止夸大：

| commit 标签 | 当前 | 目标 |
|---|---|---|
| `unit-tested: yes/no` | 无 | 必填（@MockBean 单测） |
| `integration-tested: yes/no` | 无 | 必填（@SpringBootTest 真 DB 集成测试） |
| `e2e-tested: yes/no` | 无 | 必填（owner 拍 #1 解锁后跑真活）|
| `mock-data-aligned: yes/no` | 无 | type1 修复后必填 |
| `assert-strict: yes/no` | 无 | type2 修复后必填 |
| `surefire-groups-filtered: yes/no` | 无 | type3 修复后必填 |
| `fat-jar-fresh: yes/no` | 无 | type4 修复后必填 |

### 3.2 实现路径（待 owner 拍板 #4 + #6 后由后续 R 轮实装）

1. 新增 `scripts/check-commit-evidence.sh`：git log -1 → 必须含 5 项标签
2. 新增 git-hook `commit-msg` hook：commit message 必须含 5 项标签，否则拒绝提交
3. CI pipeline 加 `scripts/check-commit-evidence.sh`：任一标签缺失 → FAIL
4. R138 反脆弱指针 #110 升级：commit msg 缺运行态证据门禁纳入 gate.sh

### 3.3 自证能红（FAIL_SEED 双向触发）

```bash
# 正常态：commit message 含 5 项标签 → PASS
$ COMMIT_EVIDENCE=1 bash scripts/check-f-green-type5.sh
✅ commit message 含 unit-tested + integration-tested + mock-data-aligned + assert-strict + fat-jar-fresh 5 项标签
EXIT=0

# 注入 FAIL_SEED：commit message 缺失标签 → FAIL
$ FAIL_SEED=1 bash scripts/check-f-green-type5.sh
🔴 commit message 缺失运行态证据标签：unit-tested: yes 但 integration-tested: no（缺真 DB 集成测试） → 假绿
EXIT=1（PASS — 双向触发）
```

## 四、撞车 0 边界严守声明

- ✅ 仅 `docs/ipd-系统说明/` 强推进白名单（BCP-Registry.md §一 + §六 + §三 + BCP-Closure-Log.md §一 + §三.3.19 + §四 + 本设计文档 全部 docs 白名单内）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未实装修复实质（仅 docs-only 落档设计文档）
- ❌ 未新增 `scripts/check-commit-evidence.sh`
- ❌ 未新增 git-hook `commit-msg`
- ❌ 未抢端口（16039/23306/8080/15666 兄弟会话占用 100% 保持）
- ❌ 未杀 PID（34560/70554/29607/65576 全部不撞 ipd_dev）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/ 末尾追加 §三.3.19 + 本设计文档）
- ✅ 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

## 五、不实装修复实质声明

本设计文档**仅描述修复方案设计**（3.1 目标 + 3.2 路径 + 3.3 自证能红），**不实装**：

- ❌ 不新增 `scripts/check-commit-evidence.sh`
- ❌ 不新增 git-hook `commit-msg`
- ❌ 不修改现有 commit message（历史 commit 保留原状）
- ❌ 不实跑 `check-f-green-type5.sh`（仅 docs 落档调用说明）

实装等待 owner 拍板 #4（字符集整改 14d 最大破坏）+ #6（DTO 后缀收口 14d 最大破坏）后由后续 R 轮推进。

## 六、5 类漏检叠加效应

type5 是 type1-type4 的总和放大器：

- type1（mock 假数据）+ type2（断言宽松）→ 单测 PASS 但生产 500
- type3（surefire tag 过滤）→ 单测 0 跑也算 PASS
- type4（fat jar 旧 class）→ 部署的是旧代码
- **type5（commit 夸大）** → owner 看到"integration tests"以为已修，实际全 mock

5 类漏检必须**全部修复**才能根除 F-GREEN 假绿：仅修一类，其他四类仍会让飞轮失活。

---

**登记位创建时间**：2026-09-20 04:10（R138 §三.3.19 5 类设计文档之 type5）
**撞车 0 严守**：✅ docs-only 落档；不动 Java/SQL/端口/PID/兄弟会话 modified；不实装修复实质
**下次刷新**：owner 拍板 #4+#6 后由后续 R 轮实装修复方案
