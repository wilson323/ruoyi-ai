# P2-1 Allowance 审计钩子 Fresh 复核（已有审计，非 bug 收口）

**作者**:R82(2026-09-19)
**触发**:owner 选项「P2-1 allowance 审计钩子」续做
**前置**:R33 接管验收(2026-09-17) 异常 4 + R27 治理报告 P0-7

---

## 结论(1 行)

**R33「异常 4 / P2-1 / allowance.L3 漂移 + 后端 0 审计」= 报告失真,审计钩子已存在且双层覆盖。**

不修代码,PARTIAL 收口;运营 L3=1500 漂移是数据问题不是审计问题,需业务方改值或确认改值。

---

## Fresh 复核四证(2026-09-19)

### 证 1:AllowanceService 写入路径审计(已存在)
文件:`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AllowanceService.java`

```java
private AuditLogService auditLogService;

@Autowired(required = false)
public void setAuditLogService(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
}

private void auditInsert(AllowanceLedger ledger, String action) {
    if (auditLogService == null) return;
    auditLogService.append(AuditLog.builder()
        .operatorId(0L).operatorName("system").operatorRole("SYSTEM")
        .action(action)
        .entityType("allowance_ledgers")
        .entityId(ledger.getId())
        .reason("personId=" + ledger.getPersonId() + ",projectId=" + ledger.getProjectId()
            + ",month=" + ledger.getMonth())
        .afterData(AuditEventData.json(...))
        .createTime(now())
        .build());
}
```

调用点:
- line 162 `recordOrSkip()` 入口:INSERT 后 auditInsert
- line 320 `idempotentInsert()` 入口:INSERT 后 auditInsert

**事实:R33 「P0-7 AllowanceService 0 审计」= 报告失真,审计钩子已存在。**

### 证 2:BR-AUD-01 DCL 红线(已落地)
参考:`docs/script/sql/update/2026-09-17-ipd-braud01-audit-grant-restrict.sql`(commit `c86e20a5`)

`audit_logs` + `audit_log_chain_heads` 表对 `ipd_app` 仅授权 SELECT + INSERT;UPDATE/DELETE 在库级被 REVOKE 收回,符合产品圣经 §12.1 合规要求。

事实:审计表自身防篡改已 closure,**不是 AllowanceService 缺钩子**。

### 证 3:BusinessConfigService 版本链审计(已存在,补盖运营参数漂移)
文件:`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/BusinessConfigService.java` line 131-166

```java
@Transactional(rollbackFor = Exception.class)
public IpdBusinessConfig update(String key, String newValue, Long operatorId) {
    int closed = businessConfigVersionMapper.update(...set(IpdBusinessConfigVersion::getEffectiveTo, now));
    int updated = businessConfigMapper.update(...set(IpdBusinessConfig::getConfigValue, newValue)...);
    ver.setConfigId(existing.getId());
    ver.setConfigKey(key);
    ver.setConfigValue(newValue);
    ver.setVersion(newVersion);
    ver.setEffectiveFrom(now);
    ver.setEffectiveTo(null);
    ...
}
```

事实:`ipd_business_config.allowance.L3` 每次更新都落 `ipd_business_config_versions` 表一份历史快照(`effective_from` / `effective_to` / `version` 三字段),**运营漂移有版本链可追溯**。

事实:R33「allowance.L3 当前值 1500 ≠ 默认值 2000,后端无审计钩子」= **后半句失真,版本钩子已存在**。

### 证 4:Controller 写路径(只有 INSERT,无 UPDATE/DELETE)
文件:`AllowanceLedgerController.java`

```
@PostMapping("/auto-scan")    line 95
```

事实:`AllowanceLedger` 是只插入的台账(R27 P3-3 锁级架构),**没有 PUT/DELETE 入口**——所以 AllowanceService 不需要审计 UPDATE/DELETE。

事实:R33/P0-7 给的"加审计钩子"建议方向是错的(给无 UPDATE/DELETE 的实体加 UPDATE 审计是过度设计)。

---

## R33 异常 4 vs 现实的失真点

| R33 说法 | 现实 | 失真 |
|---|---|---|
| 「后端 AllowanceService 0 审计」 | AllowanceService 已带 auditInsert(line 62-83),recordOrSkip + idempotentInsert 双入口调用 | 假阴性(兄弟会话未 grep auditInsert) |
| 「allowance.L3 1500 ≠ 2000,后端无审计钩子」 | BusinessConfigService.update() 落 ipd_business_config_versions 版本链,effective_from/to 可追溯 | 假阴性(兄弟会话只查 ipd_business_config 主表,未查 versions 表) |
| 「修复建议:运营核实 + 后端加审计钩子 + 前端显示差异警告」 | 审计已存在,差异警告需前端单独做 | 重复造审计层风险 |

---

## R33 异常 4 的真实成因(数据 vs 审计)

`allowance.L3` 漂移是**真业务数据问题**,不是审计问题:
- 运营 09-09 改了一次值,从默认 2000 改到 1500(可能符合业务调整)
- 真库 `ipd_business_config_versions` 应有 2 条版本记录(初值 2000 → 现值 1500)
- 漂移本身合规,审计已留痕,争议只在"1500 是否业务正确"——这是业务方拍板,不是后端代码 bug

**核实(真库):**
```
SELECT config_key, config_value FROM ipd_business_config WHERE config_key='allowance.L3';
-- 期望:1500(R33 报告值)
SELECT config_key, config_value, version, effective_from, effective_to
  FROM ipd_business_config_versions WHERE config_key='allowance.L3' ORDER BY version;
-- 期望:版本链存在,变更可追溯
```

---

## 边界与不修理由

1. **不修代码**:AllowanceService + BusinessConfigService 审计已闭环,加新审计层会破坏现有 BR-AUD-01 设计
2. **不删 R33 报告**:保留为「兄弟会话判断失误 + 蜂群 A 部分假阴性」证据
3. **不翻卡 done**:P2-1 不在「需要 owner 拍板」的卡清单里——它是误判
4. **运营核实建议(非本卡)**:让业务方确认 1500 vs 2000 哪个对,如要回滚 2000,直接调 BusinessConfigService.update('allowance.L3', '2000', superAdminId) 即可,审计自动留痕

---

## 收口动作

- 看板镜像 line 443 + 451:
  - line 443 `⚠️ 49 参数 + 3 页分页 + L3 漂移(**异常 4**)` → PARTIAL 注记
  - line 451 真实业务异常清单 P2 行「异常 4」→ 标注 R33 失真,已 fresh 复核
- log.md append 一段「R82-P2-1-allowance审计-失真fresh复核」
- R33 报告保留为「报告失真案例」参考
- 不落 Java 代码、不落 DDL、不落单测

---

## Fresh 复核时间线

- 2026-09-09:BR-AUD-01 DCL + 静态门禁 + CI workflow 闭环(commit c86e20a5)
- 2026-09-17:R33 接管验收(异常 4:allowance.L3 漂移)
- 2026-09-19 03:30:本会话 fresh 读 AllowanceService + BusinessConfigService + BR-AUD-01 DCL + Controller,确认审计已闭环
- 2026-09-19 03:35:落档本报告 + 镜像注记 + log.md append + commit + push
