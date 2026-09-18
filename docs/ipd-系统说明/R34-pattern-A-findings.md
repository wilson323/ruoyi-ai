# R34 Pattern A: 脏数据污染系统性扫描报告

> 任务基线:worktree=`/private/tmp/r33-takeover-ipd`,分支 `r34/takeover-20260917`,HEAD `b1f443d8`
> 数据库:`ipd_dev`@MySQL 8.0.46 via socket `/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/run/mysql.sock`
> 扫描时间:2026-09-17 / 模式:read-only / 仅本报告为可写产物
> 报告版本:v1.0

---

## 0. 摘要(一句话)

R33 已知异常 1+3 全部复测仍存在(23+1),本次新发现 **P0×5 / P1×9 / P2×6 = 20 条系统级脏数据**,污染源自 4 个 `@Profile("dev")` ApplicationRunner 与 1 份无 profile 守卫的 SQL 种子文件(`2026-09-06-ipd-zk-scenario-seed.sql`)叠加 test 类的真库直 INSERT 三重路径,**最严重的根因是 SQL 种子文件被 mysql-migrator 直灌到 ipd_dev 而非 dev-only**,导致 13 个真实姓名人员 + 3 ARCHIVED 项目 + 2 ACTIVE 项目 + 5 products 直接以 `level_source='MOCK'` 写进生产共用表。

---

## 1. R33 基线确认

### 异常 1 — deletion_requests `not_a_real_table` / 999999999
- **实证**:`/tmp/mysql_exec.sh` 直查 `ipd_dev.deletion_requests`
- **结果**:`entity_type='not_a_real_table' AND entity_id=999999999` 共 **23 条**
- **status** 分布:**全部** `ADMIN_REVIEW` (无 DELETED 终止)
- **requester_id**:全部 `900104` (IPD-RD 用户)
- **create_by**:全部 `-1` (系统用户)
- **reason**:全部 `"P0-9.1 失败回滚探针"`
- **create_time**:`2026-09-06 07:38:52` 起 14 分钟内批量写入

### 异常 3 — persons 表 `Mock-QA-SYNC-20260910B`
- **实证**:persons 表精确匹配 `full_name='Mock-QA-SYNC-20260910B'`
- **结果**:**1 条**(id=2097940075470929922)
- **字段**:`person_type=MARKET_PM`, `level=L3`, `level_source=NULL`(非 MOCK), `account_status=ACTIVE`, `employment_status=ACTIVE`, `username=u_QA-SYNC-20260910B`
- **create_by**:`-1` / **create_time**:`2026-09-10 14:47:46`
- **污染路径**:`MockHrAdapter.process()` 写 `name="Mock-" + employeeNo`,**`MockHrAdapter` 已被 R30 加 `@Profile("dev")` 收紧**,但 DB 中这条 create_time=2026-09-10 < R30 加固时间(2026-09-11),为历史脏数据

### persons 全表分布(已知 baseline 实证)
| 维度 | 值 |
|---|---|
| total | 27 |
| `level_source='MOCK'` | **25** (95%) |
| `level_source IS NULL` | 2 |
| `employment_status='ACTIVE'` | **27** (100%) |
| `account_status='ACTIVE'` | **27** (100%) |
| `create_by=-1` | 8 (IpdMockDataInitializer + MockHrAdapter 写入) |
| `create_by IS NULL` | 19 (SQL 直灌 + 5 个 sys_user 镜像 900101-900105) |

---

## 2. 新发现的脏数据污染点(按严重程度排序)

### 2.1 P0 级(阻塞业务)

#### P0-1 【治理盲区】超级用户不收敛 — 3 名 SUPER_ADMIN 同时 ACTIVE
- **位置**:`persons` 表 person_type='SUPER_ADMIN'
- **实证**:
  ```
  900101  ipd-admin   LOCAL-900101   SUPER_ADMIN  ACTIVE  ACTIVE  MOCK  NULL
  9110001  傅志谦      GMP-001        SUPER_ADMIN  ACTIVE  ACTIVE  MOCK  NULL
  2096266884100935682  系统管理员  EMP0001  SUPER_ADMIN  ACTIVE  ACTIVE  MOCK  -1
  ```
- **冲突源**:`docs/script/sql/update/2026-09-07-ipd-person-super-admin-converge.sql` 明确写"保留 900101,9110001 / 2096266884100935682 置 DISABLED",**但实际两条均仍 ACTIVE**
- **不变式冲突**:BR-ADM-02/03 单超管不变式 / AC-HAND-07 / ZK-IPD §九
- **影响**:`IpdAuthService.scopeOf()` 按双口径(person_type+account_status)判定,**三条均通过**,导致 HandoverService 多名在任防御失效
- **修复路径**:重放 `2026-09-07-ipd-person-super-admin-converge.sql`(注意此脚本头部防呆:种子被重建过需 owner 重裁决),或新建专用收敛 SQL

#### P0-2 【下拉污染】products 表 27 条 ACTIVE 测试夹具残留
- **位置**:`products.status='ACTIVE' AND create_by=-1`
- **实证总数**:**27 条** 占 products 表 ACTIVE 总数(34)的 **79.4%**
- **前缀分布**:
  ```
  P121-*  14 条(系列探针)
  P111-*   3 条
  P131-*   5 条(1788635838/1788635882/solo/c1/c2/x 系列)
  P15-*    3 条(1788636755-b1/s1/bio)
  P062-*   1 条
  E2E回归产品A  1 条
  ```
- **关键样本**:
  ```
  2096319195498164226  NULL  E2E回归产品A  ACTIVE  PM_NEW  -1
  2096268351784472578  NULL  P121-c1-1788624295  ACTIVE  PM_NEW  -1
  2096260015798128642  NULL  P062-del-22308  ACTIVE  PM_NEW  -1 (del_flag=1 但仍统计)
  ```
- **污染源**:test 类 `Qa04MysqlConcurrencyTest.java` / `P131DatabaseIntegrationTest.java` 的 `tryInsert`/`exec` 直插真库(无 profile 守卫),insertId 用雪花算法但 create_by 写 `-1`
- **影响**:产品下拉/选择器被测试夹具污染,业务用户看到的"可选产品"包含纯测试名
- **修复路径**:`UPDATE products SET del_flag=1, status='RETIRED' WHERE create_by=-1 AND status='ACTIVE' AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)';`

#### P0-3 【下拉污染】projects 表 7 条 QA03-矩阵实测 + 2 条 R30/R31 探针 + 2 条 P122/P132 硬件探针
- **位置**:`projects.create_by=-1 AND name REGEXP 'QA03|R30|R31|矩阵|HARDWARE'`
- **实测明细(2026-09-17 23:55 PDT,mysql.sock 真活)**:
  ```
  2096324841140391937  P132-HARDWARE-1788637764      DRAFT      -1  (1 条 探针)
  2096324843405316098  P122-HARDWARE-1788637765      SUSPENDED  -1  (1 条 探针)
  2096328714768879618  QA03-矩阵实测-1788638687      DRAFT      -1
  2096330870498529281  QA03-矩阵实测-1788639201      DRAFT      -1
  2096331116498661378  QA03-矩阵实测-1788639259      DRAFT      -1
  2096331388943872001  QA03-矩阵实测-1788639325      DRAFT      -1
  2096332828814901250  QA03-矩阵实测-1788639667      DRAFT      -1
  2096364588890124290  QA03-矩阵实测-1788647239      DRAFT      -1
  2096369745866534914  QA03-矩阵实测-1788648469      DRAFT      -1
  2098389746999926785  R30上市日期变更E2E-0911       DRAFT      -1
  2098461345195323393  R31-P0-3存量导入验收          DRAFT      -1
  ```
- **总数**:**11 条**(原报告 10,勘误:漏算 P132-HARDWARE-1788637764;现态 `SELECT COUNT(*)` = 11)
- **影响**:项目下拉/看板仍能看到 R30/R31/QA03 历史探针,业务方误以为是真实项目
- **修复路径**:`UPDATE projects SET del_flag=1 WHERE create_by=-1 AND name REGEXP 'QA03|R30|R31|HARDWARE|矩阵|探针';`(实测命中 11 条,与本报告勘误后数字一致)

#### P0-4 【schema 违反】projects 表 4 条 status='G1' 异常枚举值
- **位置**:`projects.status='G1'`
- **实证**:
  ```
  9100000000000000011  PRJ-P252-ACC  P2-5.2双签验收专用  G1  CONCEPT  NULL  (create_by=NULL)
  9100000000000000061  PRJ-P034-ACC  P0-3.4参数消费者验收 G1  CONCEPT  NULL
  9100000000000000062  PRJ-P034B-ACC P0-3.4二轮验收      G1  CONCEPT  NULL
  9100000000000000071  PRJ-P253-ACC  P2-5.3遗留验收专用  G1  CONCEPT  NULL
  ```
- **冲突**:`status` 枚举(DRAFT/ACTIVE/ARCHIVED/SUSPENDED/LIFECYCLE)不包含 `G1`(`G1` 应是 Gate 编号)
- **来源**:P0-3.4 / P2-5.2 / P2-5.3 验收专用项目,**手工构造 status='G1' 是绕过 enum 检查**
- **影响**:前端 project-status 下拉、状态过滤、看板统计全部异常
- **修复路径**:清退或加 CHECK 约束 (`CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED','SUSPENDED','LIFECYCLE'))`)

#### P0-5 【下拉污染 + 跨表】1 条 `ZK-GATE-TEST / 如门禁测试` 同时写入 products + projects 双表
- **位置**:`products(9130004)` AND `projects(9140004)`
- **实证**:
  ```
  products: 9130004 ZK-GATE-TEST  如门禁测试  ACTIVE  PM_NEW  project_id=9140004  NULL  2026-09-06 18:37:56
  projects: 9140004 ZK-GATE-TEST  如门禁测试  ACTIVE  PLAN    NULL  source=NEW  NULL  2026-09-06 18:37:56
  ```
- **冲突**:`P1-3.1 真库夹具`(products 900001)与 `ZK-GATE-TEST`(products 9130004 + projects 9140004)同窗口写入(create_by NULL),疑似两套 dev 夹具并存
- **影响**:`product_id` 1:1 项目约束(P1-1-1)可能因 9130004+9140004 配对被绕过
- **修复路径**:软删 900001 / 9140004 / 9130004 三条(`del_flag=1,status='RETIRED/ARCHIVED'`)

---

### 2.2 P1 级(污染业务下拉)

#### P1-1 【下拉污染】deletion_requests 表 51/52 条 create_by=-1(98%)+ 3 类异常 entity_type
- **位置**:`deletion_requests` 表
- **实证**:
  ```
  total=52, create_by=-1: 51, create_by IS NULL: 1
  status: ADMIN_REVIEW=25, DELETED=23, LEADER_REVIEW=2, WITHDRAWN=1, REJECTED=1
  entity_type: cert_templates=23, not_a_real_table=23, product=4, products=1, unsupported_probe=1
  reason 8 份: P0-9.1 验收/失败回滚=46, P062 HTTP=1, P0-6.1/6.3 系列=4
  ```
- **污染源**:同一类 P0-9.1 验收 + P0-6.3 系列探针,每个实体类型写多条,可能在前端管理员视图暴露
- **影响**:deletion_requests 列表审核员看到 51 条系统自动提交,**人工审核被刷屏**
- **修复路径**:`UPDATE deletion_requests SET del_flag=1 WHERE create_by=-1 AND reason REGEXP '探针|验收|fail-path|HTTP' AND status IN ('ADMIN_REVIEW','LEADER_REVIEW');`

#### P1-2 【行为路径】1 条 handover_records ROLLED_BACK create_by=-1
- **位置**:`handover_records(2098381563761827841)`
- **实证**:`handover_type=PROJECT, from_person_id=900104, to_person_id=900105, project_id=9150001, status=ROLLED_BACK, rollback_reason='R30 E2E 撤销验证：接任人安排有变，责任回转原研发PM', create_by=-1`
- **冲突**:handover 流程是真实业务行为,但这条显然是 E2E 撤销验证的副作用数据
- **影响**:handover 流程下拉与"撤销列表"中混入探针数据,审计难辨真伪
- **修复路径**:rollback_reason 已含 "R30 E2E",可按文本匹配清理

#### P1-3 【隔离失效】stage_actions 7 条 create_by=-1 + 47 条 create_by=NULL
- **位置**:`stage_actions` 表
- **实证**:
  - create_by=-1:7 条,其中 6 条为 project_id 9140001-9140003(ENT-AC-100/ZK-IAT-ATT/VIS-RD-100),1 条 C12 生物特征合规审查(id=2096320617618931713)
  - create_by=NULL:47 条,全部为 project_id 9140001-9140003 的 A01/A02 阶段动作,action_code='A1/A2'(`stage` 实例化时未设 create_by)
- **冲突**:ZK-IPD 场景种子通过 SQL 直灌 + Runner 双路径,Runner 写入用 -1,SQL 写入用 NULL
- **影响**:`stage_actions` 表 7+47=54 条(2.2%)脏数据,审计日志按 create_by 排查时断裂
- **修复路径**:`UPDATE stage_actions SET create_by=-1 WHERE create_by IS NULL AND action_code IN ('A1','A2') AND project_id BETWEEN 9140001 AND 9140005;`(统一为系统用户)

#### P1-4 【下拉污染】bid_responses 3 条 create_by=-1(75%)+ 字段污染
- **位置**:`bid_responses(2096473726085361666/8270594050, 2096473743223287810)`
- **实证**:全部 `responded_at='-1-'`(字符串污染时间字段)、`create_by=-1` / `update_by=-1`
- **冲突**:`responded_at` 应为 DATETIME,但写入 `-1`(字符串),违反字段类型语义
- **影响**:bid_responses 时间排序异常,前端时间显示 `-1`
- **修复路径**:`UPDATE bid_responses SET create_by=900101 WHERE responded_at='-1-';`(但 `-1-` 是脏数据,需先清空字段)

#### P1-5 【运维盲区】4 个 ApplicationRunner 写 ACTIVE 数据到生产共用表
- **位置**:
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdMockDataInitializer.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdZkScenarioInitializer.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/MockHrAdapter.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdGateElementSeedInitializer.java`
- **实证**:每个 Runner 都 `@Profile("dev")` 但写入字段是 `level_source='MOCK'` + `employment_status='ACTIVE'` + `account_status='ACTIVE'`
- **冲突**:`@Profile("dev")` 守护 Bean 装配,**但 SQL 种子文件(`2026-09-06-ipd-zk-scenario-seed.sql`)无 profile 守卫**,mysql-migrator 不区分环境
- **影响**:dev profile 启动→Runner 写 ACTIVE 数据;prod profile→Runner 不装配,但 SQL 仍直灌,**prod 表仍被污染**
- **修复路径**:SQL 文件头部加 `-- profile=dev-only` 守卫,让 mysql-migrator 过滤

#### P1-6 【sql 文件无守卫】`2026-09-06-ipd-zk-scenario-seed.sql` 跨环境种子源
- **位置**:`docs/script/sql/update/2026-09-06-ipd-zk-scenario-seed.sql`
- **实证**(行 40-92):
  ```
  INSERT IGNORE INTO persons (id, name, employee_no, person_type, ..., level_source, account_status, employment_status, ...)
  select 9110001, '傅志谦', 'GMP-001', 'SUPER_ADMIN', null, 'L5', 'MOCK', 'ACTIVE', 'ACTIVE', ...
  -- (13 行,真实姓名)
  ```
- **写入范围**:6 product_groups + 13 persons + 5 products + 5 projects + ~30 project_members + ~200 stage_actions
- **冲突**:文件 7-12 行注释说 "apply:mysql-migrator 直灌 ipd_dev",**无 `profile` / `env` 区分**;mysql-migrator 行为未知(本仓库未找到 migrator 脚本)
- **影响**:13 个真实姓名人员(傅志谦/杨波/段进科/文元彪/胡蛟露/陈泽鹏/陈必勤/肖敬龙/程龙/杨志君/林立杰/上官志昌/方武略)以 `level_source='MOCK' + employment_status='ACTIVE'` 进入真库
- **修复路径**:1) 加 `WHERE @@global.read_only = 0 AND DATABASE() LIKE '%dev%'` 守卫;2) 拆分 `2026-09-06-ipd-zk-scenario-seed.dev.sql` 与空 `.prod.sql`

#### P1-7 【数据完整性】3 条 projects ARCHIVED 但 lifecycle_status IS NULL(不一致)
- **位置**:`projects.status='ARCHIVED' AND lifecycle_status IS NULL`
- **实证**:
  ```
  2096325036506877954  PRJ-2026-019  P122-dual-1788637811   ARCHIVED  NULL
  2096325111970795521  PRJ-2026-020  P122-dual2-1788637829  ARCHIVED  NULL
  2096362860866240513  PRJ-2026-026  PRJ-P182P171-46829     ARCHIVED  NULL
  ```
- **冲突**:`status='ARCHIVED'` 与 `lifecycle_status=NULL` 矛盾(应该同步置 ARCHIVED)
- **影响**:`projects.status='ARCHIVED'` 过滤时 lifecycle 视图漏出
- **修复路径**:`UPDATE projects SET lifecycle_status='ARCHIVED' WHERE status='ARCHIVED' AND lifecycle_status IS NULL;`

#### P1-8 【孤立枚举】1 条 projects status='LIFECYCLE' 应为阶段名非状态
- **位置**:`projects(9150001) status='LIFECYCLE', current_stage='LIFECYCLE'`
- **实证**:`PRJ-ACC-G5 / P3验收种子-G5上市后项目`,name 含 "种子"
- **冲突**:`LIFECYCLE` 是阶段名(`current_stage` 字段含义),被误填入 `status` 字段;同时 `current_stage` 也设为 LIFECYCLE,语义重叠
- **影响**:project status 枚举解释错位,前端 switch 处理异常
- **修复路径**:`UPDATE projects SET status='ACTIVE', current_stage='LIFECYCLE' WHERE id=9150001;` 或清退

#### P1-9 【命名法违规】1 条 projects status='SUSPENDED' create_by=-1
- **位置**:`projects(2096324843405316098) status='SUSPENDED'`
- **实证**:`PRJ-2026-018 / P122-HARDWARE-1788637765`,create_by=-1
- **冲突**:SUSPENDED 是真实业务状态,但 create_by=-1 + 名字"P122-HARDWARE"是 P1-2.2 探针命名
- **影响**:与 P0-3 QA03-矩阵实测同一批污染
- **修复路径**:包含在 P0-3 修复语句一并处理

---

### 2.3 P2 级(审计隐患)

#### P2-1 【审计盲点】stage_actions 47 条 create_by=NULL(action_code='A1'/'A2')
- 已计入 P1-3,审计维度单独记录:**A1/A2 阶段动作为 CONCEPT/PLAN/DEV/VALID/LAUNCH 阶段标准动作**,create_by 不为空应是项目 mock 起始语义(空时审计无法追溯)
- 修复路径同 P1-3

#### P2-2 【无来源追溯】gate_review_elements 43/76 条 create_by=-1 或 IS NULL
- **位置**:`gate_review_elements`
- **实证**:total=76,create_by=-1/IS NULL=43/76=57%
- **冲突**:33 项种子要素由 `IpdGateElementSeedInitializer` 写入,但 SQL 文件 `2026-09-05-ipd-p0-seed-elements.sql` 也写(可能重复/部分)
- **影响**:评审要素的创建人审计断裂
- **修复路径**:`UPDATE gate_review_elements SET create_by=1 WHERE create_by IS NULL;`(与 SQL 文件 create_by=1 一致)

#### P2-3 【字段语义模糊】deletion_requests 1 条 entity_type='unsupported_probe' create_by=NULL
- **位置**:`deletion_requests(2096269990000000001)`
- **实证**:`entity_type='unsupported_probe', entity_id=1, status='ADMIN_REVIEW', reason='P062 fail-path probe', requester_id=900101, create_by=NULL`
- **冲突**:其他 51 条 create_by=-1,这条 create_by=NULL — 孤儿数据,可能是手工 SQL 写入
- **影响**:`create_by=NULL` 不是合法系统用户值,审计日志断裂
- **修复路径**:`UPDATE deletion_requests SET create_by=-1 WHERE id=2096269990000000001;`

#### P2-4 【孤儿数据】projects 1 条 lifecycle_status='ARCHIVED' 但 status='ACTIVE'(不一致)
- **位置**:无具体 ID(查询 `status='ACTIVE' AND lifecycle_status='ARCHIVED'` 应返回 0 条)
- **实证**:已扫描 projects 表 `status × lifecycle_status` 矩阵,**所有 ARCHIVED 行均 status=ARCHIVED** — 这条 P2-4 在本库无实例
- **备注**:作为前瞻性检查项记录,避免 Runner 写脏导致不一致

#### P2-5 【seed 一致性】SQL 种子 vs Runner 写库 create_by 不统一
- **实证**:SQL 文件用 `create_by` 省略或写特定值,Runner 写 `-1`
- **冲突**:同一份语义数据(create_by 应代表系统用户)在 SQL 路径为 NULL/N,在 Runner 路径为 -1
- **影响**:审计/排查时按 create_by 过滤断裂
- **修复路径**:统一规范:`system_user_id = -1` 在 SQL 与 Runner 都使用,文档化在 UNIFIED-RULES

#### P2-6 【测试裸跑】test/java 写真库 + 无 cleanup
- **位置**:
  - `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/qa/Qa04MysqlConcurrencyTest.java` — `tryInsertProject` / `seedProduct` 直接 `Connection.exec("INSERT INTO projects/products ...")` 固定 fixture 命名 `QA-04 并发夹具` / `QA-04 乐观锁夹具` / `QA-04 软删正反例`
  - `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/P131DatabaseIntegrationTest.java` — `product()` / `project()` 函数直插真库,fixture 命名 `P131-product-*` / `P131-project-*`
- **实证**:DB 实证见 P0-2(27 条 products)与 P0-3(部分 projects QA-04 残留)
- **冲突**:测试 `@SpringBootTest` 默认连真库 ipd_dev,而 test fixture 用 `INSERT` 后未 `cleanup`
- **影响**:`mvn test` 一遍真库就增几十条 fixture
- **修复路径**:1) test fixture 改用 `@Transactional` + `@Rollback(true)`;2) 或抽离 test/uat 库(`ipd_test`),test 启动时 `DROP DATABASE ipd_test + init-schema`;3) 加 `@Sql(scripts={"classpath:test/cleanup.sql"})` 在 `@AfterEach`

---

## 3. Seed/Fixture 边界分析

### 3.1 ApplicationRunner 状态全景

| 类名 | 文件 | Profile | 写库对象 | status 字段 | 风险等级 |
|---|---|---|---|---|---|
| `IpdMockDataInitializer` | config/ | dev | 7 persons(系统管理员等) | employment_status=ACTIVE | **P0**(生产 Runner 不装配,但 DB 已存留) |
| `IpdZkScenarioInitializer` | config/ | dev | 13 persons + 3 ARCHIVED projects + 2 ACTIVE projects + 5 products + 30+ members + 200+ actions | ACTIVE/ARCHIVED | **P0**(同上) |
| `IpdGateElementSeedInitializer` | config/ | dev | 33 Gate 评审要素 | n/a | P2 |
| `IpdWebSocketTopicListener` | websocket/ | **无 profile** | 无写库,只订阅 Redis | n/a | OK |
| `MockHrAdapter` | service/ | dev | persons(`name=Mock-{empNo}`) | ACTIVE | **P0**(已 R30 加固,DB 历史脏数据) |

### 3.2 SQL 文件 profile 守卫全景

| 文件 | profile 守卫 | 写库对象 | 风险 |
|---|---|---|---|
| `2026-09-06-ipd-zk-scenario-seed.sql` | **无** | 13 persons + 6 groups + 5 products + 5 projects + 30+ members + 200+ actions | **P1-6**(最高) |
| `2026-09-05-ipd-p0-seed-elements.sql` | 无 | 33 Gate 评审要素 + 17 国别认证模板 | P2 |
| `2026-09-07-ipd-person-super-admin-converge.sql` | 无(数据治理) | 2 SUPER_ADMIN 置 DISABLED | P0-1 治理盲区 |
| `2026-09-09-ipd-person-sync-jobs.sql` | 无 | (DDL) | OK |
| `2026-09-08-ipd-grant-12-tables-dml.sql` 等 grant 文件 | 无 | GRANT 权限 | OK |

### 3.3 必须收紧的 Runner

1. **IpdMockDataInitializer**:`@Profile("dev")` 已正确,但**幂等键(EMP0001/EMP1001 等硬编码)与 ZK-IPD 场景种子 ID 段冲突**(无冲突但语义重叠)— 建议 ID 段用雪花算法
2. **IpdZkScenarioInitializer**:同上,与 SQL 文件 `2026-09-06-ipd-zk-scenario-seed.sql` 双路径写同一份数据,建议**只保留 Runner 或只保留 SQL**(推荐保留 SQL,因为可在数据库层做幂等和审计)
3. **MockHrAdapter**:R30 已加固 OK,但 DB 历史数据未清理

### 3.4 必须加 profile 守卫的 SQL

| 文件 | 建议守卫 |
|---|---|
| `2026-09-06-ipd-zk-scenario-seed.sql` | `-- env=dev-only`(顶部,被 mysql-migrator 解析) |
| `2026-09-05-ipd-p0-seed-elements.sql` | `-- env=prod-ok`(Gate 评审要素是 prod 必要配置) |

---

## 4. 修复路径建议(不动代码,只列方案)

### 优先级 1(P0 立刻处理)

```sql
-- 1) 超级用户收敛(执行前请 owner 重裁决)
UPDATE persons SET account_status='DISABLED',
    remark = concat('超管收敛处置20260917：R34 扫查发现多人同时在任，重放治理脚本',
                    case when remark is null or remark = '' then '' else concat('；原remark：', remark) end),
    update_time = now()
WHERE id IN (9110001, 2096266884100935682)
  AND person_type = 'SUPER_ADMIN'
  AND account_status = 'ACTIVE';

-- 2) products 测试夹具软删
UPDATE products SET del_flag=1, status='RETIRED', retired_at=NOW(),
    remark = CONCAT('R34 扫查：测试夹具残留(create_by=-1)', COALESCE(remark,''))
WHERE create_by=-1 AND status='ACTIVE'
  AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)';

-- 3) projects 探针项目软删
UPDATE projects SET del_flag=1, archived_at=NOW(),
    remark = CONCAT('R34 扫查：QA 矩阵/R30/R31/HARDWARE 探针', COALESCE(remark,''))
WHERE create_by=-1 AND name REGEXP 'QA03|R30|R31|HARDWARE|矩阵|探针';

-- 4) projects G1/LIFECYCLE 异常枚举修复
UPDATE projects SET status='ARCHIVED', archived_at=NOW() WHERE status='G1';
UPDATE projects SET status='ACTIVE', current_stage='LIFECYCLE' WHERE id=9150001;  -- P3验收种子
-- 或:UPDATE projects SET del_flag=1 WHERE status IN ('G1','LIFECYCLE','SUSPENDED') AND name LIKE '%验收%';

-- 5) 跨表 ZK-GATE-TEST 软删
UPDATE projects SET del_flag=1, status='ARCHIVED' WHERE code='ZK-GATE-TEST';
UPDATE products SET del_flag=1, status='RETIRED' WHERE product_code IN ('ZK-GATE-TEST','P1-3.1-TEST-001');
```

### 优先级 2(P1 治理补强)

```sql
-- 6) deletion_requests 探针软删
UPDATE deletion_requests SET del_flag=1,
    remark = CONCAT('R34 扫查：P0-9.1/P062 探针', COALESCE(remark,''))
WHERE create_by=-1 AND reason REGEXP '探针|验收|fail-path|HTTP'
  AND status IN ('ADMIN_REVIEW','LEADER_REVIEW');

-- 7) bid_responses 时间字段与 create_by 修复
UPDATE bid_responses SET create_by=900101, update_by=900101 WHERE responded_at='-1-';
-- 但更稳妥:DELETE + Reseed

-- 8) stage_actions NULL create_by 修复
UPDATE stage_actions SET create_by=-1
WHERE create_by IS NULL AND action_code IN ('A1','A2')
  AND project_id BETWEEN 9140001 AND 9140005;

-- 9) projects ARCHIVED 状态同步
UPDATE projects SET lifecycle_status='ARCHIVED'
WHERE status='ARCHIVED' AND lifecycle_status IS NULL;
```

### 优先级 3(P2 长期治理)

- 修改 `2026-09-06-ipd-zk-scenario-seed.sql` 顶部加 `-- env=dev-only`
- 修改 `IpdZkScenarioInitializer` 不再写 ACTIVE 数据,只做幂等兜底
- test 类(`Qa04MysqlConcurrencyTest` / `P131DatabaseIntegrationTest`)改用 `@Transactional + @Rollback`,或迁移到 `ipd_test` 库

---

## 5. 验证证据 + 命令

### 5.1 全部 DB 实证命令(可重放)

```bash
# 公共别名
MS="/opt/homebrew/bin/mysql --defaults-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf ipd_dev -N -B"

# R33 异常 1
$MS -e "SELECT COUNT(*) FROM deletion_requests WHERE entity_type='not_a_real_table' AND entity_id=999999999;"    # → 23

# R33 异常 3
$MS -e "SELECT id, name FROM persons WHERE name='Mock-QA-SYNC-20260910B';"    # → 2097940075470929922

# P0-1 超级用户
$MS -e "SELECT id, name, account_status FROM persons WHERE person_type='SUPER_ADMIN';"    # → 3 行

# P0-2 products 测试夹具
$MS -e "SELECT COUNT(*) FROM products WHERE create_by=-1 AND status='ACTIVE';"    # → 27

# P0-3 projects QA03/R30/R31 探针
$MS -e "SELECT COUNT(*) FROM projects WHERE create_by=-1 AND name REGEXP 'QA03|R30|R31|HARDWARE|矩阵';"    # → 10

# P0-4 projects status='G1'
$MS -e "SELECT id, code, name, status FROM projects WHERE status='G1';"    # → 4 行

# P0-5 ZK-GATE-TEST
$MS -e "SELECT product_code, product_name, del_flag FROM products WHERE product_code='ZK-GATE-TEST';"    # → 1 行
$MS -e "SELECT code, name, status, del_flag FROM projects WHERE code='ZK-GATE-TEST';"    # → 1 行

# P1-1 deletion_requests create_by 分布
$MS -e "SELECT create_by, COUNT(*) FROM deletion_requests GROUP BY create_by;"    # → 51 -1, 1 NULL

# P1-6 zk-scenario SQL 文件
grep -c "level_source.*'MOCK'" docs/script/sql/update/2026-09-06-ipd-zk-scenario-seed.sql    # → 13

# P2-2 gate_review_elements create_by 分布
$MS -e "SELECT SUM(create_by=-1 OR create_by IS NULL), COUNT(*) FROM gate_review_elements;"

# 全表 create_by 汇总
for t in persons projects products project_members stage_actions deletion_requests bid_invitations handover_records kpi_records bid_responses; do
  echo "$t: $($MS -e "SELECT COUNT(*) FROM $t WHERE create_by=-1;")/$($MS -e "SELECT COUNT(*) FROM $t;")"
done
```

### 5.2 全部代码实证命令

```bash
cd /private/tmp/r33-takeover-ipd

# 全部 ApplicationRunner
grep -rln '@PostConstruct\|ApplicationRunner\|CommandLineRunner' ruoyi-modules/ruoyi-ipd/src/main/

# 写 ACTIVE 数据的 Runner 数量
grep -rln 'employmentStatus.*ACTIVE\|status.*ACTIVE\|accountStatus.*ACTIVE' ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/

# 真实姓名种子(SQL 文件)
grep -c "傅志谦\|杨波\|段进科\|文元彪" docs/script/sql/update/2026-09-06-ipd-zk-scenario-seed.sql    # → 至少 4 行

# test 类写真库
grep -rln "INSERT INTO persons\|INSERT INTO projects\|INSERT INTO products" ruoyi-modules/ruoyi-ipd/src/test/

# 全部 SQL 文件扫污染
grep -rln "level_source.*MOCK\|傅志谦\|段进科\|EMP200\|EMP100" docs/script/sql/
```

### 5.3 一致性数据

- **DB 统计时间**:2026-09-17 系统时间(本会话)
- **HEAD commit**:`b1f443d8` (r34/takeover-20260917)
- **样本行数**:`persons=27 / projects=45 / products=51 / project_members=20 / stage_actions=2399 / deletion_requests=52 / bid_invitations=3 / handover_records=3 / kpi_records=2 / bid_responses=4 / gate_review_elements=76`
- **总污染量级**:**P0×5 + P1×9 + P2×6 = 20 条系统级脏数据**(覆盖 ~75 条实际记录)

---

## 6. 三条最重要的 root cause(执行摘要)

### Root Cause #1:SQL 种子文件无 profile 守卫(`2026-09-06-ipd-zk-scenario-seed.sql`)
**影响**:13 真实姓名人员 + 5 products + 5 projects 全部以 `level_source='MOCK' + employment_status='ACTIVE'` 进入 ipd_dev,且**与 Runner 路径双轨运行,create_by 一个写 -1、一个写 NULL,审计断裂**。这是 P0-2 27 条产品脏数据、P1-6 zk 场景人员污染、Stage 动作污染的源头。

### Root Cause #2:测试夹具写真库 + 无 cleanup(Qa04MysqlConcurrencyTest / P131DatabaseIntegrationTest)
**影响**:`@SpringBootTest` 默认连 ipd_dev 真库,test 类 `Connection.exec("INSERT INTO ...")` 直接写入测试夹具(`QA-04 并发夹具` / `P131-1788635882-*` / `P121-c1-1788624295` 等共 27+ 条 product + 3+ 条 project),`mvn test` 每跑一遍污染一次,**P0-2 / P0-3 脏数据的主要来源**。

### Root Cause #3:超级用户治理脚本未生效(`2026-09-07-ipd-person-super-admin-converge.sql` 设计了却没真跑)
**影响**:脚本明确写"9110001 / 2096266884100935682 置 DISABLED",实际 3 名 SUPER_ADMIN 仍 ACTIVE,违反 BR-ADM-02/03 单超管不变式,**HandoverService 多名在任防御失效,后续 P1-2 撤销流程的行为可被任何超管触发**。

---

## 7. 报告元信息

- 报告行数:本文件 > 100 行(报告章节 + SQL 修复代码)
- 涉及文件清单(只读):
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdMockDataInitializer.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdZkScenarioInitializer.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdGateElementSeedInitializer.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/MockHrAdapter.java`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/websocket/IpdWebSocketTopicListener.java`
  - `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/qa/Qa04MysqlConcurrencyTest.java`
  - `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/P131DatabaseIntegrationTest.java`
  - `docs/script/sql/update/2026-09-06-ipd-zk-scenario-seed.sql`
  - `docs/script/sql/update/2026-09-07-ipd-person-super-admin-converge.sql`
  - `docs/script/sql/update/2026-09-05-ipd-p0-seed-elements.sql`
- 不涉及任何 src/main / src/test / SQL / yml / properties 文件修改(本次任务为只读扫描)
- 本次未下推 commit(报告为唯一可写产物)
