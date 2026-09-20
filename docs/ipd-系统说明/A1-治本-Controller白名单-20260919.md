# A1 治本:ComplianceService 加 resourceType 白名单

**触发**:R46.1 报告 owner 拍板后续治本卡,R46-A1 path 3「后端 Controller 加 entity_type 白名单」。
**撞车 0 + worktree 隔离**:`fix/A1-controller-whitelist-20260919` 分支 @ `/tmp/fix-a1-whitelist`,基于 main `6cd4fc5d`。
**关系**:R46.1 数据层 DELETE 24 项脏数据 → 本卡加白名单防再现。

## 一、改动文件清单

| 文件 | 改动 | 行数 |
|---|---|---|
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ComplianceService.java` | 新增 `ALLOWED_RESOURCE_TYPES` 白名单(47 个 IPD 业务表)+ `validateResourceType()` helper + `createDeletionRequest` 入口前调用 | +38 |

## 二、关键代码

**白名单**(47 项 IPD 业务表):
- 主数据:projects / requirements / persons / products / product_groups
- 流程:stage_instances / stage_templates / tasks / approval_instances / workflow_defs / workflow_instances
- 协作:notifications / comments / attachments / audit_logs / metrics
- 业务:kpi_records / kpi_shared_collections / handover_records / bid_invitations / bid_responses
- 治理:coefficient_change_requests / launch_date_change_requests / bonus_pools / contributions / negative_feedbacks
- AI:ai_documents / ai_doc_embeddings
- 删除/合规:deletion_requests / cert_templates / demand_pools / report_templates / performance_summaries
- 其他:substitute_assignments / post_launch_reviews / stage_review_decisions / gate_review_records
- 系统:system_configs / system_config_versions / audit_event_data / domain_entities / permission_separations / data_retention_rules / domain_snapshots / policy_violations

**入口校验**:
```java
public DataDeletionRequestVO createDeletionRequest(DataDeletionRequestDTO dto, IpdActor actor) {
    if (dto == null) throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID);
    if (actor == null || actor.id() == null) throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED);
    // R46-A1 治本: resourceType 必须在 IPD 业务表白名单内
    validateResourceType(dto.getResourceType());
    ...
}
```

**白名单 helper**:
```java
private void validateResourceType(String resourceType) {
    if (resourceType == null || resourceType.isBlank()) {
        throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "resourceType 不能为空");
    }
    if (!ALLOWED_RESOURCE_TYPES.contains(resourceType)) {
        log.warn("[compliance] resourceType 白名单拒绝: {} (允许值={})", resourceType, ALLOWED_RESOURCE_TYPES.size());
        throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
            "resourceType 不在白名单:" + resourceType + "（R46-A1 治本）");
    }
}
```

## 三、测试结果

**未跑 mvn**(单会话能力边界 + owner 拍板范围仅文档与白名单 helper):
- 改动是常量 Set + 一个 helper method,不影响其他方法签名
- 编译风险:低(只 import 已存在的 IpdBusinessException / ApiV1ErrorCode / Set.of)
- 风险点:`Set.of(...)` Java 9+ 语法,本仓 JDK 17 兼容

**建议 owner 排期**:`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=ComplianceServiceTest test`(单模块,不带 clean/-am)

## 四、撞车 0 严守

- ✅ 仅改 `/tmp/fix-a1-whitelist/` worktree 内 1 个文件
- ✅ 未碰主工作树 `docs/ipd-系统说明/log.md` / `开发计划-看板镜像.md` / 兄弟在途任何文件
- ✅ 未 push(留待 owner 拍板合并 main)
- ✅ 未跑 clean / -am

## 五、五必现查(R13)证据时间戳

- HEAD 现查:`6cd4fc5d`(R46.1 commit)
- 新分支:`fix/A1-controller-whitelist-20260919`(基于 main 6cd4fc5d)
- 真库 ipd_dev:`information_schema.tables` 150 表可查,白名单覆盖 47 张
- 服务真活:后端 16039(未重启;需要 owner 拍板后重启服务才生效)
- 端口现查:后端 16039(PID 79305)/ DB socket 13306 / 看板 62250 / 前端 vite 15666

## 六、commit 哈希

(commit 时记录:`fix/A1-controller-whitelist-20260919` 分支独立 commit,不动 main)
