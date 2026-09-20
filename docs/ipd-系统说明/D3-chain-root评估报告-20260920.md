# D3-B chain root 评估报告（基于真库 fresh 健康快照）

> 报告时间：2026-09-20 00:14 (Sun)
> 编写者：ioedream-pm
> 数据源：`docs/ipd-系统说明/真库fresh健康快照-20260919.md §三`
> 关联：R122 治理轮 D3-B 候选 → SQL chain root 排程
> 拍板目标：是否接受当前单链头（不重置） + 是否新增 cron 监控

---

## 一、当前链头健康度分析

### 1.1 链头快照（基线 = 2026-09-19 23:09 / 真库 fresh）

| chain_key | last_seq | last_hash | next_seq | initialized_at |
|---|---|---|---|---|
| GLOBAL | 2990 | 919d47b0866517a2a5908e4339d5075be3457629bc9b4f04edcfee97b14890b9 | 2991 | 2026-09-05 18:38:09 |

**距今 14 天**（2026-09-05 → 2026-09-19）—— R113 chain 治理后初始化。

### 1.2 推进速率测算

```
推进速率 = last_seq / 周期天数
        = 2990 / 14
        = ~213 seq/day
```

**对照 bigint 上限**：

```
bigint 上限 = 2^63 - 1 = 9,223,372,036,854,775,807 ≈ 9.22e18

距离耗尽年限 = 9.22e18 / 213 / 365
             = 1.18e14 年
             = 118,000,000,000,000 年
             ≈ 1.18e14 年
```

**结论**：以当前 ~213 seq/day 速率，bigint 上限足够支撑 **约 1.18e14 年（≈ 118 万亿年）**，远超宇宙年龄（≈ 1.38e10 年）。**bigint 上限维度无限安全**。

### 1.3 健康度判定

| 维度 | 实测 | 期望 | 状态 |
|---|---|---|---|
| chain_key | GLOBAL | GLOBAL | ✅ 单链头稳定 |
| last_seq | 2990 | 持续递增 | ✅ 14 天推进 2990 |
| last_hash 长度 | 64 字符 hex | 64 字符（SHA-256） | ✅ |
| next_seq = last_seq + 1 | 2991 = 2990 + 1 | 自动推进 | ✅ |
| initialized_at | 2026-09-05 18:38:09 | R113 chain 治理后 | ✅ 已稳定 14 天 |
| 推进速率 | 213 seq/day | > 0 | ✅ 健康 |
| bigint 耗尽年限 | ~1.18e14 年 | > 100 年 | ✅ 远超安全 |

**当前链头健康度：✅ 健康** —— 14 天稳定推进 2990 条 audit_logs，无断链、无重置风险。

---

## 二、重置风险评估

### 2.1 重置 GLOBAL chain 风险

**场景假设**：owner 决定重置 `audit_log_chain_heads` GLOBAL 行，last_seq 归零、last_hash 重算。

**风险评估**：

| 风险维度 | 等级 | 影响 |
|---|---|---|
| **审计历史断链** | 🔴 **阻断** | 已写入 `audit_logs` 表的 **2990 条审计记录无法回溯**——业务侧"审计追溯"功能瘫痪；R113 chain 治理 14 天成果归零 |
| **法律合规风险** | 🔴 **阻断** | 审计日志断链可能导致合规审计失败（如等保/SOX） |
| **应用层侵入** | 🟡 跟进 | 需删表重建 + 应用层重新初始化 chain root 写入逻辑 |
| **数据迁移工作量** | 🟡 跟进 | 需保留 `audit_logs` 历史 2990 条但链根关系失效（"孤儿"记录） |
| **回滚窗口** | 🟢 通过 | 备份 `audit_logs` + `audit_log_chain_heads` 可回滚 |

**核心结论**：**重置 GLOBAL chain 是 P0 阻断级风险**，业务影响远超技术收益。**当前无任何业务需求要求重置 chain root**。

### 2.2 新增 per-tenant 分片风险

**场景假设**：owner 决定从单链头 GLOBAL 改为 per-tenant 分片（如 `tenant_${tenant_id}` 多链头）。

**风险评估**：

| 风险维度 | 等级 | 影响 |
|---|---|---|
| **schema 迁移** | 🟡 跟进 | 需 `ALTER TABLE audit_log_chain_heads` 加 `tenant_id` 列 + 改主键 |
| **应用层改造** | 🔴 **阻断** | 写入逻辑需改为按 tenant 路由 chain；查询逻辑需支持多链头 |
| **历史数据分片** | 🟡 跟进 | 已写入 2990 条 GLOBAL 链记录如何分配到 per-tenant？需 owner 拍板 |
| **审计追溯接口** | 🟡 跟进 | 跨租户审计查询接口需重写 |
| **当前是否有租户隔离需求** | 🟢 通过 | 当前 `tenant_id` 是软字段（非硬隔离）——**无迫切分片需求** |

**核心结论**：per-tenant 分片是 P1 跟进级需求，**当前无迫切业务驱动**。未来如引入硬租户隔离（如独立数据库 / 多租户数据隔离）才需评估。

### 2.3 重置 vs 分片对比

| 维度 | 重置 GLOBAL | per-tenant 分片 |
|---|---|---|
| 业务收益 | 🟢 无（无重置需求） | 🟡 中（未来硬隔离） |
| 历史断链 | 🔴 **断 2990 条** | 🟢 保留 GLOBAL 历史 |
| schema 改动 | 🟡 简单（删+重建） | 🔴 **复杂（加 tenant_id + 改 PK）** |
| 应用层改动 | 🟡 中（重新初始化） | 🔴 **复杂（写入 + 查询双改）** |
| 撞车 0 风险 | 🟡 中 | 🔴 高（多人协作 schema 迁移） |
| **推荐** | **❌ 不重置** | **⏳ 暂不分片** |

---

## 三、排程建议

### 3.1 现状基线

| 指标 | 值 |
|---|---|
| 推进速率 | 213 seq/day |
| 24h 推进量 | ~213 条 |
| 14 天累计 | 2990 条 |
| 距 bigint 上限 | ~1.18e14 年 |

### 3.2 cron 监控建议

**核心策略**：**24h 内 last_seq 未推进 → 告警**（写卡 + 邮件 root）。

#### 3.2.1 监控脚本模板：`scripts/check-audit-chain-progress.sh`

```bash
#!/usr/bin/env bash
# check-audit-chain-progress.sh
# R125 D3-B 落地 — audit_log_chain_heads 推进速率监控
# 24h 内 last_seq 未推进 → 告警 (写卡 + 邮件 root)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MYSQL_CNF="${REPO_ROOT}/.codex/ipd-dev/config/mysql-client.cnf"

if [[ ! -e "$MYSQL_CNF" ]]; then
    echo "[check-audit-chain] ❌ mysql client config missing: $MYSQL_CNF" >&2
    exit 2
fi

# 拉当前 last_seq
CURRENT_SEQ=$(mysql --defaults-file="$MYSQL_CNF" ipd_dev -N -B -e "
    SELECT last_seq FROM audit_log_chain_heads WHERE chain_key='GLOBAL';
" 2>/dev/null)

if [[ -z "$CURRENT_SEQ" ]]; then
    echo "[check-audit-chain] ❌ failed to read last_seq (db unreachable or chain empty)" >&2
    exit 2
fi

# 状态文件: 记录上次 last_seq + 时间戳
STATE_FILE="/tmp/audit-chain-progress-state"
NOW=$(date +%s)

if [[ -f "$STATE_FILE" ]]; then
    LAST_RECORD=$(cat "$STATE_FILE")
    LAST_SEQ=$(echo "$LAST_RECORD" | cut -d: -f1)
    LAST_TIME=$(echo "$LAST_RECORD" | cut -d: -f2)

    ELAPSED_HOURS=$(( (NOW - LAST_TIME) / 3600 ))
    SEQ_DELTA=$((CURRENT_SEQ - LAST_SEQ))

    if [[ $ELAPSED_HOURS -ge 24 && $SEQ_DELTA -eq 0 ]]; then
        echo "[check-audit-chain] ❌ ALERT: last_seq $CURRENT_SEQ 已 24h+ 未推进 (delta=$SEQ_DELTA, elapsed=${ELAPSED_HOURS}h)"
        # TODO: 写卡 + 邮件 root (后续 R126 治理轮加)
        exit 1
    fi

    if [[ $SEQ_DELTA -gt 0 ]]; then
        RATE_PER_DAY=$((SEQ_DELTA * 86400 / (NOW - LAST_TIME)))
        echo "[check-audit-chain] ✅ last_seq=$CURRENT_SEQ (推进 $SEQ_DELTA 条 / ${ELAPSED_HOURS}h, ~$RATE_PER_DAY seq/day)"
    fi
fi

# 更新状态
echo "${CURRENT_SEQ}:${NOW}" > "$STATE_FILE"
echo "[check-audit-chain] 📝 状态已记录: last_seq=$CURRENT_SEQ time=$NOW"
exit 0
```

#### 3.2.2 cron 配置建议（参考，owner 拍板落地）

```cron
# 每日 02:30 检查 chain 推进速率
30 2 * * * cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-audit-chain-progress.sh >> /var/log/audit-chain-progress.log 2>&1
```

**注意**：
- cron 路径以生产服务器为准，本地开发环境 `.codex/` 软链可能在生产不可用
- 状态文件 `/tmp/audit-chain-progress-state` 在 cron 重启后会丢——需改 `/var/lib/audit-chain/state` 持久化
- 告警通道（写卡 + 邮件 root）需 R126 治理轮单独拍板落地

#### 3.2.3 阈值矩阵

| elapsed | seq_delta | 判定 | 动作 |
|---|---|---|---|
| < 1h | ≥ 0 | ✅ 正常 | 写状态文件 |
| 1h ~ 24h | = 0 | 🟡 关注 | 写状态文件 + 日志 WARN |
| ≥ 24h | = 0 | 🔴 **告警** | 写状态文件 + 触发告警通道（写卡 + 邮件 root） |
| 任意 | < 0 | 🔴 **严重告警** | last_seq 回退（不可能但需检测），人工介入 |
| 任意 | > 0 | ✅ 正常 | 写状态文件 + 统计速率 |

---

## 四、owner 拍板项清单

| # | 拍板项 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| **1** | **是否接受当前单链头（不重置）** | (a) 接受单链头 ✅ (b) 重置 GLOBAL ❌ (c) per-tenant 分片 ⏳ | **(a) 接受单链头 ✅** | 无业务需求要求重置；重置会断 2990 条审计链；分片无迫切驱动。**14 天稳定 + 1.18e14 年 bigint 安全** 是充分保留理由。 |
| **2** | **是否新增 cron 监控** | (a) 立即落地 ✅ (b) R126 排期 ⏳ (c) 不监控 ❌ | **(a) 立即落地 ✅** | 14 天 2990 条已证明 chain 在跑；监控脚本简单（~30 行）低成本高收益；24h 未推进告警可提前发现写卡逻辑异常 / db 不可达等真问题。 |
| **3** | **告警通道** | (a) 仅写日志 (b) 写卡 + 邮件 (c) 写卡 + 邮件 + 钉钉/企微 | **(b) 写卡 + 邮件 root** | 24h 告警属严重事件但非 P0 阻塞，写卡 + 邮件足够；钉钉/企微需后续运维投入，R126 视情况升级。 |
| **4** | **监控频率** | (a) 每小时 (b) 每日 02:30 (c) 每周 | **(b) 每日 02:30** | 24h 窗口足够；每日一次低成本；避开业务高峰；02:30 与 R123 reconcile-multi-source.sh 02:00 cron 错开 30 分钟。 |
| **5** | **历史快照保留** | (a) 状态文件 `/tmp` (b) `/var/lib/audit-chain/state` (c) SSOT 报告 | **(c) SSOT 报告 + (b) 状态文件** | 状态文件 cron 重启会丢，需持久化路径；SSOT 报告每周聚合便于历史趋势分析（与 R123 reconcile 风格一致）。 |
| **6** | **D1-B 字符集发现关联** | (a) D3-B 独立治理 (b) 与 D1-B 联合治理（修字符集时同步评估 chain 重置风险） | **(b) 联合治理** | D1-B 真活扫发现全库 571 处字符集不一致（含 audit_log_chain_heads）；如果 D1-B 触发 audit_logs 大规模 schema 迁移，需评估 chain root 是否需重置。**当前 D3-B 建议 D1-B 字符集修复走 in-place ALTER TABLE 不重置 chain**。 |

---

## 五、风险与建议

### 5.1 即时风险（已识别）

| 风险 | 等级 | 说明 |
|---|---|---|
| audit_log_chain_heads 表字符集不一致 | 🟡 跟进 | D1-B 脚本扫出该表 `last_hash` 列 COLLATE=utf8mb4_bin ≠ utf8mb4_0900_ai_ci（已纳入 571 不一致项）。**建议字符集修复走 in-place ALTER COLUMN**（不重建表 → 不影响 chain root）。 |
| 状态文件丢失 | 🟢 通过 | cron 重启或机器重启会丢 `/tmp/audit-chain-progress-state` → 下次 cron 会重新建立基线（24h 告警会假阳性一次） |
| 告警通道未接入 | 🟡 跟进 | 当前 cron 仅写日志 + 状态文件，无写卡 / 邮件能力——**真正异常发生时人工需主动查日志**，不够实时 |

### 5.2 长期建议（>30 天）

| 建议 | 时间窗 | 说明 |
|---|---|---|
| per-tenant 分片评估 | R126-R130 | 如业务引入硬租户隔离（如 SaaS 多租户 / 数据合规要求），重新评估 per-tenant 分片 |
| chain root 备份策略 | R126 | 当前 `audit_log_chain_heads` 无独立备份策略，建议加每日 mysqldump 备份（与 R123 reconcile 风格一致） |
| 告警通道升级 | R130 | 写卡 + 邮件 + 钉钉/企微 webhook（运维侧投入） |
| SHA-256 → SHA-3 评估 | > R150 | 算法升级评估（不迫切） |

---

## 六、结论与下一步

### 6.1 D3-B 核心结论

1. **当前链头健康度 ✅** —— 14 天稳定推进 2990 条，bigint 足够支撑 ~1.18e14 年
2. **重置 GLOBAL chain ❌ P0 阻断** —— 会断 2990 条审计链，无业务收益
3. **per-tenant 分片 ⏳ 暂不评估** —— 无迫切业务驱动，硬隔离需求出现时再评估
4. **新增 cron 监控 ✅ 推荐立即落地** —— 30 行脚本低成本高收益，24h 未推进告警

### 6.2 下一步行动项

| # | 行动 | 负责 | 时限 |
|---|---|---|---|
| 1 | owner 拍板拍板项 #1-#6 | owner | R125 治理轮结束前 |
| 2 | 落地 `scripts/check-audit-chain-progress.sh`（含本报告 §三 模板） | D3-B 执行 | owner 拍板后立即 |
| 3 | 配 cron 每日 02:30 + 写日志路径 + 状态文件持久化路径 | ops | 拍板后 1 天内 |
| 4 | D1-B 字符集修复走 in-place ALTER（不重建表） | D1-B 执行 | D1-B 治理轮 |
| 5 | 写 SSOT 周报 `docs/ipd-系统说明/audit-chain-progress-YYYYMMDD.md` | D3-B 执行 | R126 周治理轮 |

---

## 七、报告元信息

- **报告路径**：`docs/ipd-系统说明/D3-chain-root评估报告-20260920.md`
- **数据快照**：`docs/ipd-系统说明/真库fresh健康快照-20260919.md §三`（2026-09-19 23:09 抓取）
- **关联 ADR**：无直接 ADR 关联（chain root 治理属 R122 D3-B 新增候选）
- **关联脚本模板**：本报告 §三 3.2.1（`scripts/check-audit-chain-progress.sh` 草稿）
- **owner 拍板输入**：本报告 §四 拍板项清单 + §五 风险评估
- **下次刷新建议**：D1-B 字符集修复完成后 或 R126 周治理轮开始前
