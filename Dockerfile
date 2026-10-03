# IPD 后端生产镜像（多阶段：maven 构建 → 精简 JRE 运行）
# 构建示例：docker build -t ipd-backend:latest .
# 运行示例（数据源/Redis/密钥全部走环境变量，禁入镜像）：
#   docker run -e SPRING_PROFILES_ACTIVE=prod \
#     -e SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL="jdbc:mysql://<db-host>:3306/ipd?..." \
#     -e SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME=ipd_app \
#     -e SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD=*** \
#     -e SA_TOKEN_JWT_SECRET_KEY=*** \
#     -p 16039:16039 ipd-backend:latest
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# 先拷 POM 利用 Docker 层缓存拉依赖
COPY pom.xml .
RUN mvn -q -B -DskipTests -Dmaven.main.skip dependency:go-offline || true
# 再拷源码全量构建（IPD 主链：ruoyi-admin 聚合 ruoyi-modules/ruoyi-ipd）
COPY . .
RUN mvn -q -B -DskipTests package -pl ruoyi-admin -am

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN command -v curl
RUN groupadd -r ipd && useradd -r -g ipd ipd && mkdir -p /app/logs && chown -R ipd:ipd /app
USER ipd
COPY --from=build /src/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar
# 生产 JVM 参数与 PERF-P2-3 start.sh 对齐：G1GC + 堆上限 + OOM 时 HeapDump
ENV SERVER_PORT=16039
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs"
# 镜像的运行档兜底为 prod。
# 为什么需要这条：application.yml 里该键的默认值是 dev，而 dev 档下两件事同时成立——
#   (a) ProdConfigFailFastRunner 标注 @Profile("prod")，生产配置校验根本不会执行；
#   (b) 三个种子器 IpdMockDataInitializer / IpdZkScenarioInitializer /
#       IpdGateElementSeedInitializer 标注 @Profile("dev")，会向所连的库写入
#       演示账号、ZK 场景与 Gate 要素种子。
# 即：漏设运行档时，旧行为是「把演示数据静默写进目标库，且全程没有任何校验或告警」。
# 影响面：docker-compose.yml 中已显式设 prod，显式传入的环境变量优先级高于本 ENV，
# 故该路径行为不变；本地 mvn spring-boot:run 读的是 application.yml 的默认值，不受影响。
# 需要在容器里跑 dev 档时，显式传 -e SPRING_PROFILES_ACTIVE=dev 即可覆盖。
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 16039
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
