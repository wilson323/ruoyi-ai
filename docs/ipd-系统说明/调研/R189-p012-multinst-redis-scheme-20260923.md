# R189 P0-12 多实例 Redis 切流方案调研

> 任务：DEF-9 审计 hash 链多实例断裂 + 多实例 Redis 缓存共享
> 日期：2026-09-23 | 模式：只读探针，撞车 0 严守

## 1) 现查基线
**HrTokenClient.java（ruoyi-ipd/hr/HrTokenClient.java:24）**
结构：`private volatile CachedToken cached;` 单实例字段，整方法 `synchronized`
TTL：`expiresIn − 300` 秒（默认 6900 秒），绝对时间戳
key：**无**（JVM 局部变量，不跨实例）
失效：仅 `isExpired()` 时间过期；HR API 错误抛 HrApiException

**BusinessConfigServiceImpl.java（ruoyi-ipd/service/BusinessConfigServiceImpl.java:44）**
结构：双层 `ConcurrentHashMap` — `CACHE`（GLOBAL）+ `SCOPE_CACHE`（scope+scopeId）
TTL：SCOPE_CACHE = 5 min（`SCOPE_CACHE_TTL_MILLIS`）
key：CACHE=业务参数键；SCOPE_CACHE=`GLOBAL|GROUP|PROJECT:scopeId:configKey`
失效：写穿透 `invalidate/update` 仅清本 JVM，无跨实例广播

**application.yml 现状**
`ruoyi-admin/application.yml`：**完全无 `redis:`/`redisson:` 块**；`ruoyi-ipd` 无独立 yml；Redis 依赖就位但未启用。

**pom 依赖现状（关键：无需新增）**
✅ 顶层 pom 已有 `redisson-spring-boot-starter` 3.51.0 + `lock4j-redisson-spring-boot-starter`
✅ `ruoyi-ipd/pom.xml` **已依赖 `ruoyi-common-redis`**（ROOT-R2-P0-2 NotificationDispatcher 在用）
✅ `RedisUtils` API 已完备：`setCacheObject/getCacheObject/deleteObject/publish/subscribe`
❌ **新增 pom 依赖 = 0 项**

## 2) HrTokenClient Redis 切流骨架（伪代码）
```java
private static final String KEY = "ipd:hr:token";
private record TokenPayload(String token, long expiresAt) {}
public synchronized String getToken() {
    TokenPayload c = RedisUtils.getCacheObject(KEY);
    if (c != null && System.currentTimeMillis() < c.expiresAt) return c.token();
    return refresh();
}
public synchronized String refresh() {
    // ... 现有 HR API 调用不变 ...
    long expiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
    RedisUtils.setCacheObject(KEY, new TokenPayload(token, expiresAt),
        Duration.ofMillis(expiresAt - System.currentTimeMillis()));
    return token;
}
// 失败语义保留；Redis 读异常 → fall through 走 refresh()
```

## 3) BusinessConfigServiceImpl pub/sub 切流骨架
```java
private static final String CHANNEL = "ipd:business-config:invalidate";
@PostConstruct void init() { RedisUtils.subscribe(CHANNEL, String.class, this::onInvalidate); }
private void onInvalidate(String key) {
    CACHE.remove(key);
    SCOPE_CACHE.keySet().removeIf(k -> k.startsWith(key + ":") || k.equals(key));
}
private void invalidate(String key) {
    onInvalidate(key);
    RedisUtils.publish(CHANNEL, key);   // 广播其他实例失效
}
private void invalidateAll() {
    CACHE.clear(); SCOPE_CACHE.clear();
    RedisUtils.publish(CHANNEL, "*");
}
```
要点：SCOPE_CACHE 的 5 min TTL 保留作兜底；pub/sub 是热失效通道。

## 4) 配置开关（application.yml 新增）
```yaml
spring.data.redis: { host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379}, password: ${REDIS_PWD:} }
redisson.config: |
  singleServerConfig: { address: "redis://${REDIS_HOST:127.0.0.1}:${REDIS_PORT:6379}", password: "${REDIS_PWD:}" }
```

## 5) 测试方案（多实例同步验证）
- 单实例回归：HrTokenClientTest / BusinessConfigServiceTest 全绿，0 退化
- Redis 命中：启 1 实例调 `getToken()`；`redis-cli GET ipd:hr:token` 期望有值
- pub/sub 同步：启 2 实例（A/B），A 调 `invalidate("foo")`；B 调 `getString("foo")` 期望 B 立即读 DB 重建
- Token 共享：A 写 Token；B 读期望不打 HR API
- Redis 降级：拔 Redis 网线，期望 fall through 调 HR API
- 新增 IT：HrTokenClientRedisIT（Testcontainers Redis）、BusinessConfigServiceMultiInstanceIT（双 SpringBoot 上下文）

## 6) 工作量估计
| 子任务 | 人时 | 风险 |
|---|---|---|
| HrTokenClient 切 Redis | 4h | 低（接口已隔离） |
| BusinessConfigServiceImpl pub/sub | 8h | 中（SCOPE_CACHE 模糊匹配边界） |
| application.yml 开关 + 文档 | 2h | 低 |
| 测试（Testcontainers + 多实例 IT） | 8h | 中 |
| 联调 + 看板同步 | 4h | 低 |
| **合计** | **26h ≈ 3.5 人日** | — |

## 7) 撞车 0 严守
- ❌ 未 commit 任何文件（本报告为唯一落档）
- ❌ 未修改任何 `src/main/**` 代码（仅伪代码在本文档）
- ❌ 未触碰兄弟会话在盘工作（HrTokenClient/BusinessConfigServiceImpl 无未提交改动）
- ✅ 仅 `mkdir` + 写入调研报告一个文件
- ✅ 调研结论：0 项 pom 新增，零侵入面，红线在「多实例」语义切换，不影响单实例性能
