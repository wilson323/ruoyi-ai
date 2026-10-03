# 官方 provider 工厂最小修复

状态 PENDING_VALIDATION：原入口7e888包 run2106092487658545153 SOURCE seq8 的 McpJsonInternal:67 帧已经正式定位 mapper 默认provider发现失败。共享工厂显式选当前唯一官方 Jackson mapper supplier；冷Boot loader隔离运行暴露后续schema provider发现失败，经协调者授权，create 也显式选当前唯一官方 Jackson schema supplier。后者是隔离机制证据，尚未正式运行回读。

影响面：IPD使用streamableHttp；market使用create接受自己已有transport，schema显式默认供应者影响二者，mapper补丁仅streamableHttp。0.17.2 supplier.get 分别new JacksonMcpJsonMapper(new ObjectMapper())与new DefaultJsonSchemaValidator()，与当前service声明唯一provider一致；每客户端独立实例，保留SDK并发schema缓存与原校验，无共享可变全局mapper、无SDK/依赖升级。URI/endpoint/query/headers/clientInfo/capability/timeout/调用/关闭不改。

源码仅ManagedMcpAsyncClient与其Test；新增空TCCL构建并close测试。JUnit静态默认缓存可能掩盖旧实现缺陷，因此独立fresh Boot loader冷缓存真实反例作为必要证据，不能只依赖JUnit。第一次仅mapper修复整体构建失败及安全schema帧保留JSON；第二次实际生产源码temp javac退出0，provider0、默认mapper失败、默认schema失败、补丁完整streamableHttp构建成功并close，probe退出0。无initialize、无网络或DB写，无主target写；不宣称远端资料调用成功。

详细命令入口、临时目录、包hash、安全结果、源码与freeze hashes见 codex-c-explicit-mapper-probe-20261002.json；纯runtime源码见同名.java。主Maven与正式包加载验收由A串行完成。
