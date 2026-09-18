# 多租户过滤 + 权限注解覆盖维度

## 是什么
每个 Service 单测至少覆盖 6 个维度，其中 2 个是多租户与权限（参考原 SKILL.md §必须覆盖的维度）。

## 为什么踩坑
**多租户默认开启**：新建"租户共享"表必须登记父 `application.yml` 的 `tenant.excludes`，否则查询被自动追加租户过滤，表现为"数据查不到"。
**多租户拦截器**：所有 mapper 调用经过 `MybatisTenantInterceptor`，单测里如果 mock 了 mapper 但忘了 tenant 条件，会出现"测试通过但运行时无数据"。
**反例同样成立**：已登记的 `person_roles` 在任何库都还没建表（属"超前登记"，见 `docs/ipd-系统说明/验收/P1-项5-DDL-apply核验-20260905.md` §3）。

## 怎么覆盖
每个 Service 至少覆盖 6 维度：
1. **正常路径**：所有 public 方法的主路径
2. **参数校验**：null / 空 / 非法值 → 抛 `ServiceException`
3. **多租户过滤**：构造含 `tenantId` 的上下文，确认 mapper 调用带 tenant 条件
4. **权限边界**：需要 `StpUtil.checkPermission(...)` 的方法，缺权限时抛 `NotPermissionException`
5. **幂等 / 重复操作**：update / delete 重复调用不报错
6. **异常转换**：`RuntimeException` 是否被捕获并转 `ServiceException`

## 怎么测（权限边界示例）

```java
@Test
@DisplayName("权限缺失时抛 NotPermissionException")
void lackPermission_throws() {
    // 触发需要权限的方法，预期抛 NotPermissionException
    assertThatThrownBy(() -> service.assignRole(userId, roleId))
        .isInstanceOf(NotPermissionException.class);
}
```

## 验证
- 在测试类里加一个不调 tenant 上下文的用例，跑 `bash scripts/mock-drift-check.sh` 应能识别
- 故意省 `@Tag("dev")` 的多租户用例，应被 Tag 过滤规则识别

## 来源
- AGENTS.md §构建/测试："多租户默认开启"
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`