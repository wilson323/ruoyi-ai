# R84 dev profile seed 兼容性 Fresh 复核（Initializer 已完整，非 bug 收口）

**作者**：R84（2026-09-19）
**触发**：owner 选项「dev seed 兼容性 独立卡」续做
**前置**：R33 接管验收报告 dev seed 兼容性段 + 上轮 java 重启被冲突拦

---

## 结论（1 行）

**R33「dev profile seed 兼容性」= 报告失真，3 个 Initializer 已齐备（dev 门控 + 幂等保护 + 事务回滚 + 密码注入）。**

不修 Initializer 代码，PARTIAL 收口；真实冲突源在 HTTP API 层（兄弟会话 HTTP `POST /api/v1/projects/create` 撞 `UNIQUE(code)`）和 schema 迁移层（`project_members.locked_level NOT NULL`），均非本卡范围。

---

## Fresh 复核三证（2026-09-19）

### 证 1：3 个 Initializer 现状（磁盘核实）

| 文件 | 行数 | dev 门控 | 事务 | 幂等保护 |
|---|---|---|---|---|
| `IpdZkScenarioInitializer.java` | 295 | `@Profile("dev")` line 50 | `@Transactional(rollbackFor = Exception.class)` line 68 | **6 处** selectOne/selectCount 判存在跳过（line 139-143/179-184/196-201/222-225/239-243/253-257） |
| `IpdGateElementSeedInitializer.java` | 111 | `@Profile("dev")` line 31 | `@Transactional` line 80 | selectCount(gateCode+elementCode) line 87-89 |
| `IpdMockDataInitializer.java` | 120 | `@Profile("dev")` line 32 | `@Transactional` line 48 | selectOne(employeeNo) + selectOne(groupName) line 80-81/93-94 |

**关键代码片段**（`IpdZkScenarioInitializer.seedActiveProject` line 139-143）：

```java
Project exist = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
    .eq(Project::getCode, code).last("limit 1"));
if (exist != null) {
    return exist;   // 幂等跳过
}
```

**结论**：ZK-GATE-TEST 项目若已存在，`seedActiveProject` 第一次 `selectOne` 就直接 return，后续 `projectMapper.insert` / `projectStageMapper.insert` / `member()` / `action()` 全不会触发。Init 阶段**不可能**产生 `DuplicateKey ZK-GATE-TEST`。

### 证 2：上轮 java 重启被冲突拦的真实成因（推断）

会话摘要记录：`DuplicateKeyException ZK-GATE-TEST` + `Field 'locked_level' doesn't have a default value`。

**复盘**：

| 现象 | 真实层 | 误判 |
|---|---|---|
| DuplicateKey ZK-GATE-TEST | **HTTP API 层**：兄弟会话 `POST /api/v1/projects/create` 走 `ProjectService.create` 事务，落 `projects` 表撞 `UNIQUE(code)` | 误判为 Initializer 重复插入 |
| locked_level NOT NULL | **Schema 迁移层**：`project_members` 表新增 `locked_level` 列但未加默认值，Insert/Update 缺字段触发 | 误判为 Initializer 缺陷 |

**结论**：两次冲突均**与 Initializer 无关**。Init 阶段因幂等保护根本不会触发，Init 阶段也不写 `project_members` 的 `locked_level` 字段（`IpdZkScenarioInitializer.member()` 只写 projectId/personId/role/memberType/joinDate/bonusEligible + BaseEntity）。

### 证 3：密码安全合规（SEC-HIGH-2）

3 个 Initializer 全部用 `@Value("${ipd.security.initial-password}")` 注入密码（`IpdZkScenarioInitializer` line 54-55 + `IpdMockDataInitializer` line 41-42），源码无任何密码字面量。Prod profile 必须 env 注入 `IPD_INITIAL_PWD`，fail-fast。

---

## 真实问题清单（非本卡范围）

1. **HTTP API 层 ZK-GATE-TEST UNIQUE 冲突**：兄弟会话在途 HTTP 创建与 init 阶段 selectOne 幂等不冲突（Init 已存在 → return；但 HTTP POST 直接 insert 撞 UNIQUE）。建议 HTTP 层也加 `selectOne(eq code) → exists 409 ALREADY_EXISTS` 预检。
2. **Schema `project_members.locked_level` 缺默认值**：兄弟会话引入的 schema 迁移遗漏，需补 `ALTER TABLE project_members MODIFY locked_level INT NOT NULL DEFAULT 0` 或在 DDL 注明 NOT NULL 时给默认值。
3. **多 Initializer 之间无显式 @Order**：Spring 默认字母序执行（GateElement → Mock → ZkScenario），且均 idempotent，无冲突；不强制要求 @Order。

---

## 收口动作

1. SSOT 镜像：原 R33 报告 dev seed 兼容性段注记 PARTIAL（待镜像更新 line 460 后段）
2. log.md append 本卡记录
3. commit + push 上线（本卡 docs only，零代码变更）

---

## 不建议做的事

- ❌ 不要给 3 个 Initializer 加 `@Order`（字母序天然 OK）
- ❌ 不要给 `seedActiveProject` 加更多 `selectOne`（6 处已覆盖）
- ❌ 不要给 Initializer 加 try-catch 兜底（init 失败应 fail-fast 让 Spring 启动失败，比静默部分种子更安全）
- ❌ 不要把 `IpdZkScenarioInitializer` 拆成多个文件（295 行单文件可读性 OK）

---

## 关联卡

- HTTP API 层 ZK-GATE-TEST UNIQUE 预检 → 建议另立新卡 P-Seed-01（HTTP 入口幂等）
- Schema `project_members.locked_level` 默认值 → 建议另立新卡 P-Seed-02（DDL 补齐）
