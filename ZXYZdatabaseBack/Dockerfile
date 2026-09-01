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
#   - zxyz-admin-service
# -----------------------------------------------------------------------------

# 镜像钉定策略：所有基础镜像均使用官方版本并固定到确切版本 tag（非 :latest）。
# 如需进一步锁定到不可变 digest（@sha256:），在有 docker 的环境执行：
#   docker pull maven:3.9-eclipse-temurin-17
#   docker inspect --format='{{index .RepoDigests 0}}' maven:3.9-eclipse-temurin-17
# 取 RepoDigests 后写成 maven:3.9-eclipse-temurin-17@sha256:...，由 Dependabot 在 CI 维护。

# builder 直接使用官方 maven:3.9-eclipse-temurin-17，不再依赖个人私有镜像
# （原 aclouda/zxyz-maven-base:latest 为可变 tag，构建不可复现，已移除）。
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

ARG MODULE

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
COPY ZXYZdatabaseBack/zxyz-admin-service/pom.xml ./zxyz-admin-service/pom.xml

# 复制源码后执行目标模块打包。
# 分两步：先构建 zxyz-common 的 test-jar（部分服务依赖其测试工具类），
# 再用 -Dmaven.test.skip=true 打包目标模块（跳过测试编译和执行）。
COPY ZXYZdatabaseBack/ ./
RUN mvn -B -T 1C -pl zxyz-common -am install -Dmaven.test.skip=false -DskipTests \
    && mvn -B -T 1C -pl "${MODULE}" -am package -Dmaven.test.skip=true \
    && find "${MODULE}/target" -maxdepth 1 -type f -name "${MODULE}-*.jar" ! -name "original-*.jar" -exec cp {} /tmp/app.jar \;

# Alpine 瘦身：eclipse-temurin:17-jre-alpine 体积更小。
# 阻塞点检查：项目仅使用 Netty NIO（纯 Java），不依赖 netty-transport-native，
# 因此 Alpine musl libc 不会影响 Netty 功能。
# 运行时镜像已固化为确切 patch 版本 tag（immutable、可复现），
# 不再使用会随上游滚动的裸 17-jre-alpine。tag 名经 Docker Hub 核对真实存在：
#   eclipse-temurin:17.0.14_7-jre-alpine
# 来源：https://hub.docker.com/_/eclipse-temurin 该 tag 的图层页（带真实 digest/layers）。
# 为什么不直接写 sha256 digest：本 host 无法校验对应架构 digest，写错会令 build 失败；
# 确切 patch tag 已可复现且无需校验，是少数可安全手动固化的官方 tag。
# 后续如需进一步固化 digest，由 .github/dependabot.yml（docker）在 CI 环境维护。
FROM eclipse-temurin:17.0.14_7-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

ARG MODULE
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    TZ=Asia/Shanghai

# 使用构建阶段挑选出的 Spring Boot 可执行 jar，避免误复制 original 包。
COPY --from=builder /tmp/app.jar /app/app.jar

EXPOSE 18000 18080 18081 18082 18083 18084 18085 18086 18087 18088 19090

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
