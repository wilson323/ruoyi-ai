# R92:★★★★★ + ★★★★ 5 项 owner 拍板派单决策包(2026-09-19,Loop 50)

**作者**:主协调(2026-09-19 04:25)
**触发**:主人选中 ★★★★★ + ★★★★ 共 5 项派单(R76 vite 守护 / R42-E archived_at / B1 bonus_allocations 真活 / application-prod.yml 硬编码 / bonus.poolRate 漂移)
**前置**:R91 §二 17-19 项派单清单 + R89 7 张 D 类汇总卡 owner 拍板模板

---

## 一句话大白话

主协调把主人选中的 5 项高优派单整理成派单决策包,自己只写不启动,等 owner 拍板后启动 worktree / DBA apply / 装 cron。

---

## 撞车 0 + 单会话能力边界严守(R92)

主协调当前**不擅自做**:
- 启动 5 项里的任何 1 项 worktree
- DBA apply SQL
- 装 crontab
- commit 代码
- push 跨仓
- kill PID / mvn 重启 / 翻 status

**OPS-09 + 4 fix-* worktree 严守不接管**(已知兄弟会话在 `/private/tmp/fix-a1-whitelist` / `fix-a3-mock-filter` / `fix-a4-audit-log` / `fix-a4-seed-repair` 工作,主协调不碰)。

---

## 5 项派单 worktree 清单

### 【1】★ R76 vite 守护(crontab -e)

| 字段 | 内容 |
|---|---|
| **主题** | vite dev server 自动守护(cron + vite-keepalive.sh) |
| **owner 拍板点** | owner 装 crontab(主协调撞车 0 不擅自) |
| **worktree 命名** | **N/A**(非派单 worktree,只 crontab -e) |
| **命令模板** | `*/5 * * * * /Users/mac/Documents/ruoyi-ipd-web/scripts/vite-keepalive.sh status >> /tmp/vite-keepalive.log 2>&1` |
| **阻塞关系** | 阻塞前端所有调试流程 |
| **优先级** | ★★★★★ |
| **现查 commit** | `cd98c40d` R85 vite cron 一键脚本 markdown |

**owner 操作(3 步)**:
1. `crontab -l > /tmp/cron.bak.$(date +%s)` 备份
2. `crontab -e` 添加上面那行
3. `crontab -l | grep vite-keepalive` 验证

---

### 【2】R42-E archived_at 批量回填(DBA apply)

| 字段 | 内容 |
|---|---|
| **主题** | archived_at 批量回填(6 行违规 ID:`9140001/9140002/9140003` + 3 雪玢 ID) |
| **owner 拍板点** | DBA apply(已现查 6 行违规 ID) |
| **worktree 命名** | **N/A**(SQL 草稿,非 worktree) |
| **命令模板** | 见 R91 §三 2.1 SQL 草稿 |
| **阻塞关系** | 不阻塞主路径 |
| **优先级** | ★★★★ |

**DBA 操作**:
- 在 `.codex/ipd-dev/config/mysql-client.cnf` 用 socket 连真库
- 跑 SQL 草稿前先 SELECT 6 行违规 ID 复核
- apply 后再 SELECT 验证 archived_at NOT NULL

---

### 【3】B1 bonus_allocations 真活 HTTP 验收

| 字段 | 内容 |
|---|---|
| **主题** | 津贴台账 AllowanceLedgerController 真活 HTTP 验收(B1 三端点补交付已 done) |
| **owner 拍板点** | 真活 HTTP 验证(P-DATA-gap-1 bonus_allocations 已在镜像缺口表 486 行维持) |
| **worktree 命名** | `agent-batchX-b1-bonus-allocations-http` |
| **命令模板** | `git worktree add /private/tmp/agent-batchX-b1-bonus-allocations-http -b agent/batchX-b1 main && cd $_ && mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=B1AcceptanceTest test` |
| **阻塞关系** | 阻塞 B1 翻 done + B2 派生 |
| **优先级** | ★★★★ |

**owner 启动前置**:
- 后端 jar 在 16039 运行(R84 PmDirectory 已加载新代码)
- 真库 ipd_dev 可连
- 单测基线 74 项绿(P33 PmDirectory 已落)

---

### 【4】application-prod.yml 硬编码内联值 🚨

| 字段 | 内容 |
|---|---|
| **主题** | application-prod.yml 多处硬编码内联值(snail-job token + justauth client-secret + mail/sms 占位符)🚨 |
| **owner 拍板点** | owner 拍板 + 派单 worktree |
| **worktree 命名** | `agent-batchX-app-prod-yaml-secrets` |
| **命令模板** | `git worktree add /private/tmp/agent-batchX-app-prod-yaml-secrets -b agent/batchX-app-prod-yaml-secrets main && cd $_ && # 把内联值替换为 ${ENV_VAR} 引用 + application.yml 模板注释` |
| **阻塞关系** | 阻塞 SEC-NEW-MED-3 翻 done + 生产部署 |
| **优先级** | ★★★★ |

**owner 启动前置**:
- 这是敏感字段,需要 owner 亲自拍板替换哪个 ENV_VAR
- 主协调不擅自 commit 包含具体 secret 字面量的代码

---

### 【5】bonus.poolRate 漂移(SQL 草稿)

| 字段 | 内容 |
|---|---|
| **主题** | bonus.poolRate 漂移(`config_value=0.0500` ≠ `default_value=0.05` + `update_by=-1` ⚠️) |
| **owner 拍板点** | A 改 default_value / B 改 config_value / C 维持 |
| **worktree 命名** | **N/A**(SQL 草稿,非 worktree) |
| **命令模板** | `UPDATE sys_config SET config_value='0.05' WHERE config_key='bonus.poolRate'`(走 SQL 草稿,不擅自 commit) |
| **阻塞关系** | 不阻塞主路径 |
| **优先级** | ★★★★ |

**owner 拍板三选一**:
- A 改 default_value → 影响所有未来新装环境的默认值
- B 改 config_value → 只改当前真库
- C 维持 → 接受漂移(可能不是 bug)

---

## 强约束依赖图(5 项派单)

```
R76 vite 守护 ──────── 阻塞前端所有调试流程(★★★★★)
  ↑
  └─ B1 bonus_allocations 真活 HTTP ──→ 阻塞 B1 翻 done(★★★★)
                                        └─→ B2 project_scores 加注释(★★★,派生)
application-prod.yml 硬编码 ──→ 阻塞 SEC-NEW-MED-3 + 生产部署(★★★★)
R42-E archived_at 回填 ──→ 不阻塞主路径(★★★★,DBA 拍板)
bonus.poolRate 漂移 ────→ 不阻塞主路径(★★★★,A/B/C 拍板)
```

**5 项互相无强阻塞**,可并行启动 5 个独立 worktree(但撞车 0 让路下不同时跑,串行执行更安全)。

---

## 派单执行顺序建议(给 owner 拍板)

1. **先做 R76** ★★★★★(1 行 crontab,30 秒搞定,前端所有调试立刻受益)
2. **再做 R42-E + bonus.poolRate**(纯 SQL,不动代码,各 5 分钟)
3. **再启 B1 bonus_allocations worktree**(单测为主,需 30-60 分钟)
4. **最后 application-prod.yml worktree**(敏感字段,owner 亲自拍板,可能跨天)

---

## 撞号透明 + 撞车 0 严守声明(R92)

- **撞号透明**:R92 主协调版与兄弟会话撞号风险低(5 项里没有兄弟会话在途项;4 fix-* worktree 严守不接管)
- **撞车 0**:不擅自启动 worktree / 不擅自 DBA apply / 不擅自 crontab -e / 不擅自 commit / 不擅自 push 跨仓
- **b1e8e713 红线**:5 项里任何 1 项 done 都需要真活 HTTP / 真库 SQL / crontab 验证,不凭单测翻 done
- **R13-hard §6**:5 项 worktree 命名 + 命令模板 + 阻塞关系 + 优先级 4 字段都已现查 R91 §二 / §三 / §五,凭记忆写 0 处
- **OPS-09 单写者**:5 项 worktree 不与已知 4 fix-* worktree(`fix-a1-whitelist` / `fix-a3-mock-filter` / `fix-a4-audit-log` / `fix-a4-seed-repair`)撞仓
