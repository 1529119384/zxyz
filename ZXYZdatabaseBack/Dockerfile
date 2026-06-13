# -----------------------------------------------------------------------------
# 后端通用 Dockerfile
# 通过 MODULE 构建参数决定打包哪个 Maven 子模块：
#   - zxyz-gateway
#   - zxyz-project-service
#   - zxyz-im-service
#   - zxyz-email-service
#   - zxyz-user-service
#   - zxyz-share-service
#   - zxyz-file-service
#   - zxyz-team-service
#   - zxyz-audit-service
# -----------------------------------------------------------------------------

FROM dockerpull.org/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

# 先复制 POM，利用 Docker layer 缓存 Maven 依赖。
COPY ZXYZdatabaseBack/pom.xml ./pom.xml
COPY ZXYZdatabaseBack/zxyz-common/pom.xml ./zxyz-common/pom.xml
COPY ZXYZdatabaseBack/zxyz-gateway/pom.xml ./zxyz-gateway/pom.xml
COPY ZXYZdatabaseBack/zxyz-project-service/pom.xml ./zxyz-project-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-im-service/pom.xml ./zxyz-im-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-email-service/pom.xml ./zxyz-email-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-share-service/pom.xml ./zxyz-share-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-file-service/pom.xml ./zxyz-file-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-team-service/pom.xml ./zxyz-team-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-user-service/pom.xml ./zxyz-user-service/pom.xml
COPY ZXYZdatabaseBack/zxyz-audit-service/pom.xml ./zxyz-audit-service/pom.xml

ARG MODULE
RUN test -n "${MODULE}" \
    && mvn -B -pl "${MODULE}" -am dependency:go-offline

# 复制源码后执行目标模块打包。
# 分两步：先构建 zxyz-common 的 test-jar（部分服务依赖其测试工具类），
# 再用 -Dmaven.test.skip=true 打包目标模块（跳过测试编译和执行）。
COPY ZXYZdatabaseBack/ ./
RUN mvn -B -pl zxyz-common -am install -Dmaven.test.skip=false -DskipTests \
    && mvn -B -pl "${MODULE}" -am package -Dmaven.test.skip=true \
    && find "${MODULE}/target" -maxdepth 1 -type f -name "${MODULE}-*.jar" ! -name "original-*.jar" -exec cp {} /tmp/app.jar \;

FROM dockerpull.org/eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ARG MODULE
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    TZ=Asia/Shanghai

# 使用构建阶段挑选出的 Spring Boot 可执行 jar，避免误复制 original 包。
COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 18000 18080 18081 18082 18083 18084 18085 18086 18087 19090

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
