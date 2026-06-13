# Commands

## 后端（所有命令在 `ZXYZdatabaseBack/` 目录执行）

```bash
mvn clean -DskipTests compile          # 基线编译检查
mvn test                               # 运行全部测试（~60 个测试类）
mvn test -pl zxyz-project-service      # 运行单个模块测试
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest  # 单个测试类
mvn clean package -DskipTests          # 打包（Docker 构建用）
```

启动单个服务：
```bash
mvn -pl zxyz-project-service spring-boot:run   # 18080
mvn -pl zxyz-im-service spring-boot:run        # 18081
mvn -pl zxyz-email-service spring-boot:run     # 18082
mvn -pl zxyz-user-service spring-boot:run      # 18083
mvn -pl zxyz-share-service spring-boot:run     # 18084
mvn -pl zxyz-file-service spring-boot:run      # 18085
mvn -pl zxyz-team-service spring-boot:run      # 18086
```

测试命名约定：`*Test.java`（非 `*Tests.java`）。Docker 生产构建用 `-Dmaven.test.skip=true`。

## 前端（所有命令在 `ZXYZdatabaseFront/` 目录执行）

```bash
npm run dev        # 开发服务器，端口 5173
npm run build      # 生产构建至 dist/
npm run preview    # 预览生产构建，端口 4173
npm run lint       # ESLint 检查
npm run lint:fix   # ESLint 自动修复
npm run format     # Prettier 格式化
npm run test       # Vitest 单次运行
npm run test:watch # Vitest 监听模式
```

代码规范：ESLint 9 + Prettier 3.8，Husky + commitlint 强制 conventional commits。
