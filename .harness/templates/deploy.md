# deploy.md — Deploy 阶段产物（发布清单 + 授权记录）
<!--
  Anthropic AI Native SDLC Playbook · Deploy 阶段标准产物
  AI 可以一路准备到上线之前，但不能自己决定发布到生产。
  hook 拦截发布命令（kubectl/npm publish/docker push/mvn deploy/…），
  直到本文件由具名负责人签字。这是 Anthropic 保留给人的最后一道闸门。
-->

## 元信息

```yaml
deploy_id: DEPLOY-<YYYYMMDD>-<NNN>
plan_ref: PLAN-<YYYYMMDD>-<NNN>
review_ref: REVIEW-<YYYYMMDD>-<NNN>   # 必须已 APPROVED
target_env: dev | staging | production
status: preparing | authorized | deployed | rolled-back
```

## 1. 发布内容（变了什么）

| 项 | 内容 |
|---|---|
| 变更摘要 | <对应 plan.md 的任务清单结果> |
| 涉及服务/模块 | <列表> |
| 接口契约变更 | <有/无；有则附 OpenAPI diff 链接> |
| 数据迁移 | <有/无；有则必须先在 staging 验证过> |

## 2. 发布前检查（全绿才允许进入授权）

- [ ] `.harness/verify.sh` 退出码 0（证据已贴 plan.md）
- [ ] REVIEW.md 结论 = APPROVED（首个审查者为独立会话）
- [ ] 四道机器扫描通过（密钥/静态/依赖/基础设施）
- [ ] 回滚方案已确认可执行（spec.md §6）
- [ ] 发布窗口/通知相关方已确认

## 3. 发布步骤（AI 准备，人执行或人在场执行）

```bash
# <命令 1>   ← 这些命令会被 Layer 3 hook 拦截，直到本文件完成签字
# <命令 2>
```

每步的预期结果与验证命令：

| 步骤 | 命令 | 预期 | 验证 |
|---|---|---|---|
| 1 | <cmd> | <预期> | <验证命令> |

## 4. 授权（具名，不许「老板说可以」）

| 项 | 值 |
|---|---|
| 授权人 | <真实姓名 / github handle> |
| 授权时间 | <YYYY-MM-DD HH:MM> |
| 授权方式 | <本文件签字 / 变更单号> |

## 5. 发布后观察（30 分钟内）

| 指标 | 正常区间 | 实际 |
|---|---|---|
| 错误率 | <x> | <读数> |
| 延迟 P99 | <x> | <读数> |
| 核心业务信号 | <x> | <读数> |

**越界处置**：执行回滚方案 → 生成 `incident.md` → 诊断后回灌。

## 6. 回滚记录（若发生）

- 触发时间 / 触发人 / 回滚命令 / 结果：

---
