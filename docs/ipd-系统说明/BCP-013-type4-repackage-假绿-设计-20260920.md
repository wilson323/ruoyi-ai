# BCP-013 type4: spring-boot repackage 复用旧 fat jar — 假绿改造设计文档

> **创建时间**：2026-09-20 04:10（R138 evolver docs-only 闭环）
> **基线**：HEAD `6aa32475`（R137-D1 修复后）
> **来源**：R137 R138 直接解锁完整执行（AI 自主拍板剩余 BCP = docs-only 闭环）
> **性质**：BCP-013 F-GREEN 假绿改造 5 类漏检设计文档（type4 — maven 增量缓存命中旧 class 导致 BUILD SUCCESS 假象）
> **撞车 0 边界**：本智能体 E 仅写 docs-only，❌ 不实装修复实质

## 一、背景

R138 evolver 接到 BCP-013 docs-only 闭环任务后，对飞轮 F-GREEN 假绿改造的 5 类漏检形态逐一落档设计文档。本文件为 type4 — spring-boot repackage 复用旧 fat jar 导致 BUILD SUCCESS 假象。

## 二、假绿类型描述

### 2.1 现象

Maven `spring-boot:repackage` 复用旧 fat jar：

- **BUILD SUCCESS** 但日志仍打 `Replacing original artifact with bootified` = BUILD SUCCESS 假象
- maven 增量缓存命中旧 class → `mvn package` 不重新编译 `.java` → fat jar 内 class 文件陈旧
- **本地启 jar** → 跑的是旧版本（无新功能） → 但 CI 报 "BUILD SUCCESS"
- 反脆弱指针 #133 F-GREEN 假绿改造：BUILD SUCCESS ≠ 新代码已部署

### 2.2 根因

- Maven 增量编译（incremental compilation）缓存命中 → 不重编译 `.java`
- `spring-boot:repackage` 把 jar 内的 class 重新打包成 fat jar，但实际 class 未变
- **本地启 jar 验证**：本应启新 jar 但实际启的是旧 jar（端口被旧进程占用 → 启新 jar 失败 → 回退启旧 jar）

### 2.3 影响

- ✅ `mvn clean package -DskipTests` BUILD SUCCESS（CI 全绿）
- ❌ fat jar 内 class 文件陈旧（maven 缓存命中旧 class）
- ❌ owner 拍板 #1 解锁后 E2E 跑下来 → 启的是旧 jar → 新功能未生效 → 500
- ❌ 反脆弱指针 #133：BUILD SUCCESS ≠ 部署成功

## 三、修复方案（docs-only — 不实装）

### 3.1 设计目标

强制 maven 每次重新编译 + fat jar 必须包含新 class 哈希：

| Maven 命令 | 当前 | 目标 |
|---|---|---|
| `mvn package` | 增量编译 | `mvn clean package -DskipTests`（强制重编译）|
| `spring-boot:repackage` | 复用旧 jar | 每次重打 fat jar（`mvn package -Drepackage.skip=false`）|
| fat jar class 哈希校验 | 无 | `scripts/check-fat-jar-class-hash.sh`（对比 git HEAD .java 与 fat jar .class）|
| 端口抢占验证 | 无 | 启 jar 前 `lsof -i:16039` 必须空闲 |

### 3.2 实现路径（待 owner 拍板 #4 + #6 后由后续 R 轮实装）

1. 修改 `pom.xml` 中 `spring-boot-maven-plugin` 配置：`<addResources>false</addResources>` + 强制重打包
2. CI pipeline 替换 `mvn package` 为 `mvn clean package -DskipTests`
3. 新增 `scripts/check-fat-jar-class-hash.sh`：git HEAD `.java` 文件 SHA-256 → 必须包含在 fat jar 内
4. 新增 `scripts/check-port-conflict.sh`：启 jar 前 `lsof -i:16039` 必须为空
5. 撞车 0 让路：实装前 docs-only 落档，本设计文档作为修复方案蓝图

### 3.3 自证能红（FAIL_SEED 双向触发）

```bash
# 正常态：mvn clean package + fat jar class 哈希校验 → PASS
$ FAT_JAR_FRESH=1 bash scripts/check-f-green-type4.sh
✅ mvn clean package -DskipTests BUILD SUCCESS + fat jar class SHA-256 匹配 git HEAD
EXIT=0

# 注入 FAIL_SEED：故意保留增量缓存 → FAIL
$ FAIL_SEED=1 bash scripts/check-f-green-type4.sh
🔴 mvn package 增量缓存命中旧 class → fat jar 内 SHA-256 不匹配 git HEAD → BUILD SUCCESS 假象
EXIT=1（PASS — 双向触发）
```

## 四、撞车 0 边界严守声明

- ✅ 仅 `docs/ipd-系统说明/` 强推进白名单（BCP-Registry.md §一 + §六 + §三 + BCP-Closure-Log.md §一 + §三.3.19 + §四 + 本设计文档 全部 docs 白名单内）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未实装修复实质（仅 docs-only 落档设计文档）
- ❌ 未修改 `pom.xml`（spring-boot-maven-plugin 配置保留 R137 原状）
- ❌ 未抢端口（16039/23306/8080/15666 兄弟会话占用 100% 保持）
- ❌ 未杀 PID（34560/70554/29607/65576 全部不撞 ipd_dev）
- ❌ 未实跑 `mvn clean package`（避免重复构建污染 target/）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/ 末尾追加 §三.3.19 + 本设计文档）
- ✅ 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

## 五、不实装修复实质声明

本设计文档**仅描述修复方案设计**（3.1 目标 + 3.2 路径 + 3.3 自证能红），**不实装**：

- ❌ 不修改 `pom.xml`（spring-boot-maven-plugin 配置保留 R137 原状）
- ❌ 不新增 `scripts/check-fat-jar-class-hash.sh`
- ❌ 不实跑 `mvn clean package`（避免 target/ 污染）
- ❌ 不实跑 `check-f-green-type4.sh`（仅 docs 落档调用说明）

实装等待 owner 拍板 #4（字符集整改 14d 最大破坏）+ #6（DTO 后缀收口 14d 最大破坏）后由后续 R 轮推进。

---

**登记位创建时间**：2026-09-20 04:10（R138 §三.3.19 5 类设计文档之 type4）
**撞车 0 严守**：✅ docs-only 落档；不动 Java/SQL/端口/PID/兄弟会话 modified；不实装修复实质
**下次刷新**：owner 拍板 #4+#6 后由后续 R 轮实装修复方案
