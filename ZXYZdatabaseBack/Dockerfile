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

# 使用预构建基础镜像（已缓存 Maven 依赖），需要时手动重建：
#   docker build -t aclouda/zxyz-maven-base:latest -f ZXYZdatabaseBack/Dockerfile.base ZXYZdatabaseBack/
# TODO(pin-digest): 该基础镜像由外部账号 aclouda 维护、tag 为可变的 latest，
# 构建不可复现。因本 host 无法 `docker manifest inspect` 校验其 sha256 digest，
# 且镜像为私有、Docker Hub 上不存在，故不写 digest、不改 tag 名（改了会找不到镜像）。
# 维护策略：由 .github/dependabot.yml（package-ecosystem: docker，
# directory: /ZXYZdatabaseBack/）在能拉取该私有镜像的 CI 环境自动 bump 并固化 digest；
# 当前保留 :latest 是为了兼容本机手动
#   docker build -t aclouda/zxyz-maven-base:latest -f Dockerfile.base ZXYZdatabaseBack/
# 的既有工作流（见 Dockerfile.base 顶部说明）。
FROM aclouda/zxyz-maven-base:latest AS builder

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
# TODO(pin-digest): 运行时镜像已固化为确切 patch 版本 tag（immutable、可复现），
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
