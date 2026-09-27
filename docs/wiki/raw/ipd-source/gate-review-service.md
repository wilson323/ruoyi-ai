---
source: file:///Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java
collected: 2026-09-27
published: 2026-09-27
topic: ipd-source
---

# GateReviewService.java（IPD Gate 评审主服务）

源文件：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java`
接口：`IGateReviewService.java`（同包）
体量：约 1028 行实现 + 108 行接口
状态：核心 11 个关键方法已落地，对应 ZK-IPD 原型 5 个 Gate 评审要素

## 关键方法行号索引

| 方法 | 行号 | 业务职责 |
|---|---|---|
| `sign` | L207 | 评审签发（双签盲签 MARKET_PM/RD_PM） |
| `view` | L236 | 查看签发详情 |
| `reopen` | L442 | 重开 Gate |
| `scanTimeout` | L497 | 扫描超时 Gate |
| `scanRemind` | L550 | 扫描到期提醒 |
| `arbitrate` | L587 | 仲裁裁决 |
| `finalRuling` | L628 | 最终裁定 |
| `inviteObservers` | L683 | 邀请观察员 |
| `recordOpinion` | L770 | 记录意见 |
| `listObservers` | L799 | 列出观察员 |
| `extendDeadline` | L812 | 延长截止时间 |

## 关键常量

```java
// 评审角色（双签）
SIGNER_ROLES = Set.of("MARKET_PM", "RD_PM");

// 观察员角色（5 类）
OBSERVER_ROLES = ...;

// 决策值
DECISIONS = Set.of("APPROVE", "REJECT");

// 默认最大延期次数
DEFAULT_MAX_SIGN_EXTENSIONS = 3;
```

## 业务规则矩阵（与 ZK-IPD 原型对齐）

| 原型规则 ID | 规则名称 | 实现位置 | 状态 |
|---|---|---|---|
| BR-GATE-03 | 双签盲签（MARKET_PM/RD_PM） | sign L207 | ✅ |
| BR-GATE-04 | 仲裁裁定 | arbitrate L587 | ✅ |
| BR-GATE-05 | 最终裁定（owner 拍板） | finalRuling L628 | ✅ |
| BR-GATE-06 | 重开 Gate | reopen L442 | ✅ |
| BR-GATE-08 | 延期 | extendDeadline L812 | ✅ |

## 关联契约测试

- `AiCardBlindSignContractTest.java`（同模块测试目录）
- 6 个后端契约测试集中在 `ruoyi-modules/ruoyi-ipd/src/test/java/...`

## 引用来源

- 上游：ZK-IPD 49 页原型 `_ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/IPD系统_五大Gate评审要素_v1.md`
- 业务决策：`docs/ipd-系统说明/工程合同/业务决策确认-20260905.md`
- 治理报告：`docs/ipd-系统说明/工作流系统性梳理-20260927.md` §3 异常清单 P0-N3
