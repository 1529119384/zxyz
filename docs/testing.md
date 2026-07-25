# 测试指南

## 概述

### 项目测试策略

本项目采用分层测试策略，覆盖单元测试、集成测试和组件测试三个层面：

| 测试类型 | 目的 | 占比 | 运行成本 |
|---|---|---|---|
| **单元测试** | 验证单类逻辑正确性，隔离外部依赖 | 主体（~65 类） | 低（秒级） |
| **集成测试** | 验证 MyBatis SQL + Flyway 迁移在真实 MySQL 上的行为 | 辅助（8 类 Mapper） | 中（需 Docker） |
| **MQ 消费者测试** | 验证消息处理逻辑、幂等性、毒消息处理 | 专项（2 类） | 低 |
| **上下文冒烟测试** | 验证 Spring 上下文能加载 | 1 类（`ZxyzImApplicationTests`） | 中（需 Spring） |
| **组件测试** | 验证 Vue 组件渲染和交互 | 暂缺（Phase B 补） | 中 |

**回归测试**：修改代码后运行全量测试（`mvn test` + `npm run test`），确保未引入破坏性变更。

**TDD 开发**：新功能先写测试（红），再写实现（绿），最后重构。

### 技术栈

| 方向 | 框架/工具 | 版本 |
|---|---|---|
| 后端测试 | JUnit 5 (Jupiter) | 5.12.2（由 Spring Boot 3.5.7 BOM 管理，未在 pom 显式指定） |
| 后端 Mock | Mockito | 5.17.0（同上） |
| 后端集成 | Spring Boot Test + Testcontainers | MySQL 8.4 + Redis 7 |
| 前端测试 | Vitest | 4.1 |
| 前端环境 | happy-dom | ^20.10.2 |
| 前端 Mock | `vi.mock()` + `vi.fn()` | — |
| 前端状态 | Pinia 测试（`setActivePinia`） | ^3.0.4 |

> 注：Spring 6.2 起推荐用 `@MockitoBean` 替代旧的 `@MockBean`，本项目集成测试统一采用 `@MockitoBean`。

---

## 后端测试

### 单元测试模式

#### 基础结构

```java
package uno.acloud.{service}.{layer};

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.{service}.SomeService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SomeServiceTest {

    @Mock
    private SomeDependency dependency;

    @InjectMocks  // 或手动 new
    private SomeService someService;

    @BeforeEach
    void setUp() {
        // 配置 mock 行为
        when(dependency.someMethod(any())).thenReturn(expectedValue);
    }

    @Test
    void methodName_scenario_expectedBehavior() {
        // 准备输入
        // 执行被测方法
        // 断言结果
        // verify 交互（如需要）
    }
}
```

#### 两种 Mock 注入方式

项目同时使用两种方式，选择取决于偏好：

**方式一：`@InjectMocks`（自动注入）**

```java
@ExtendWith(MockitoExtension.class)
class RoleManagementServiceTest {

    @Mock
    private TeamPermissionMapper teamPermissionMapper;

    @Mock
    private TeamMapper teamMapper;

    @InjectMocks
    private RoleManagementService roleManagementService;
    // Mockito 自动通过构造器注入
}
```

**方式二：手动 `new`（显式构造）**

```java
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserProfileService userProfileService;

    // ... 其余 7 个 @Mock 协作者

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(
                authService, userProfileService, /* ...其他 7 个依赖 */);
    }
}
```

**建议**：Service 层用 `@InjectMocks`，Controller 层用手动 `new`（因为 Controller 通常依赖较多，显式构造更清晰；且 Controller 形参可能包含 `HttpServletRequest`/`HttpServletResponse` 等需要 fake 对象的类型）。

#### 宽松 Stubbing

当某些 mock 只在部分测试中使用时，添加 `@MockitoSettings(strictness = Strictness.LENIENT)` 避免 `UnnecessaryStubbingException`：

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileUploadServiceTest {
    // ...
}
```

#### 测试分组注释

使用分隔线注释将测试按方法分组：

```java
// ==================== confirmUpload — sufficient quota ====================

@Test
void confirmUpload_sufficientQuota_shouldSucceed() { ... }

// ==================== confirmUpload — exceeding quota ====================

@Test
void confirmUpload_exceedingQuota_shouldThrow() { ... }
```

#### 断言风格

使用 JUnit 5 内置断言，**不使用** AssertJ 或 Hamcrest（虽两者均在 classpath 中）：

```java
// 基础断言
assertNotNull(result);
assertEquals(expected, actual);
assertTrue(condition);
assertFalse(condition);
assertNull(value);

// 异常断言
BusinessException ex = assertThrows(BusinessException.class,
        () -> service.doSomething());
assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
assertTrue(ex.getMessage().contains("错误信息关键词"));

// 实例断言
assertInstanceOf(PersistenceException.class, ex.getCause());
```

> 注：`ErrorCode` 是 `int` 常量类（`public final class ErrorCode`，常量 `SUCCESS=1`、`NOT_FOUND=4040` 等），非枚举。`Result.getCode()` 返回 `Integer`（boxed），与 `int` 比较时走自动拆箱；对 `-128~127` 区间外的值需用 `.equals(...)` 或 `.intValue()` 避免引用比较坑。

#### Mockito 验证

```java
// 正向验证
verify(dependency).method(eq("arg"), any());

// 负向验证
verify(dependency, never()).method(any());

// 无交互
verifyNoInteractions(dependency);

// 参数捕获（用于验证复杂对象）
ArgumentCaptor<Entity> captor = ArgumentCaptor.forClass(Entity.class);
verify(mapper).insert(captor.capture());
assertEquals("expected", captor.getValue().getName());

// 对 null 值使用 isNull()，不要用 eq(null)
verify(mapper).insert(eq("key"), isNull(), eq("value"), anyLong());
```

#### ConfigGetter Passthrough 模式

当 `ConfigGetter` 未配置时返回默认值，使用 `thenAnswer` passthrough：

```java
when(configGetter.getJsonSet(eq("app.file.upload.allowed-extensions"), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));

when(configGetter.getLong(eq("app.file.upload.max-size-bytes"), anyLong()))
        .thenAnswer(invocation -> invocation.getArgument(1));
```

#### 实体构建

`@Data` 实体用无参构造 + setter；**不要用** `Builder` 或多参构造（实体未加 `@Builder`/`@AllArgsConstructor`）：

```java
SysConfig config = new SysConfig();
config.setConfigKey("app.file.upload.max-size");
config.setConfigValue("104857600");
config.setConfigType("SYSTEM");  // String 类型：SYSTEM/FEATURE/SECURITY，不是 int
config.setIsEncrypted(false);
```

> `FileItem.create()` 等部分实体提供 `create()` 工厂方法，请按实际实体类判断。

#### ServiceProperties 配置

`*ServiceProperties`（如 `AdminServiceProperties`）作为真实对象构建（不 mock），按需新建不同配置实例。**注意其 `@ConfigurationProperties(prefix="...")` 与 YAML 键必须严格对齐**（见 CLAUDE.md 中 prefix 匹配的常见错误）。

---

### Controller 测试模式

#### 现状说明

项目 **不使用** `@WebMvcTest` / `MockMvc`。原因是 Sa-Token 的静态 `StpUtil` API 使 HTTP 层 mock 成本极高。

但当 Controller 方法形参为 `HttpServletRequest` / `HttpServletResponse` 时（如 `UserController.login(LoginRequest, HttpServletRequest, HttpServletResponse)` —— 控制器需要 `httpRequest.getRemoteAddr()` 做限流、需要 `response` 写 cookie），测试**会使用** `MockHttpServletRequest` / `MockHttpServletResponse`。这两个类来自 `spring-test`，是**轻量 fake servlet 对象**，**不是** `MockMvc` 体系的一部分，可在纯 Mockito 单测中独立 new 出来。

#### 两种 Controller 测试模式

**模式一：通过 Controller 调用、验证对 Port 接口的委派**（`FileController` 采用）

Controller 通过构造器注入 Port 接口，测试 `new Controller(...)` 后**调用 Controller 方法**并 verify Port：

```java
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileUploadPort fileUploadPort;

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileUploadPort, /* 其他 port */);
    }

    @Test
    void getUploadSign_delegatesToFileUploadPort() {
        UploadInfo info = new UploadInfo();
        info.setUploadUrl("https://oss.example.com/put");
        when(fileUploadPort.getUploadSign("test.txt")).thenReturn(info);

        UploadInfo result = controller.getUploadSign("test.txt");  // 调 controller，不是 port

        assertNotNull(result);
        assertEquals("https://oss.example.com/put", result.getUploadUrl());
        verify(fileUploadPort).getUploadSign("test.txt");
    }
}
```

**模式二：用 MockHttpServletRequest/Response 构造 Controller 直接调用**（`UserController` 采用）

对于需要 Servlet 请求/响应参数的场景：

```java
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private CookieHelper cookieHelper;
    // ... 其他协作者

    private UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(authService, cookieHelper, /* ... */);
    }

    @Test
    void login_withValidCredentials_returnsSuccess() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password123");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authService.login(request)).thenReturn("test-token-abc");

        Result<LoginVO> result = userController.login(request, httpRequest, response);

        assertNotNull(result);
        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(cookieHelper).setAuthCookies(response, "test-token-abc");
    }
}
```

#### Sa-Token 相关限制

类级 `@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)` 等注解**只在 Spring AOP 运行时生效**；在 `@ExtendWith(MockitoExtension.class)` + 手动 `new` Controller 的纯单测里**注解不会触发**。这意味着：

- 通过的纯单测**仅验证业务委派正确**，**不验证授权正确**；要覆盖授权，需走 `@WebMvcTest` + Sa-Token mock（属于 Phase B 调研目标）。

`StpUtil.getLoginIdAsLong()` 等静态方法在纯单元测试中不可用。处理方式：

- 需要 `@CurrentUser Long userId` 注入的场景：Controller 形参已解耦为 `Long`，单测直接传 `1L` 即可（`@CurrentUser` 由 `CurrentUserArgumentResolver` 在 web 层解析，单测中绕过）
- 需要 `@SaCheckRole` 的场景：在集成测试中验证，或 Phase B 的 MockMvc + Sa-Token mock 中验证

---

### Service 层测试模式

#### 事务边界测试

对于 `@Transactional` 方法中使用 `TransactionSynchronizationManager.registerSynchronization` 的场景，使用 `MockedStatic` + `ArgumentCaptor` 验证 afterCommit 回调：

```java
@Test
void clearMemberRole_evictsCacheAndCallsUserService() {
    ArgumentCaptor<TransactionSynchronization> captor =
            ArgumentCaptor.forClass(TransactionSynchronization.class);

    try (MockedStatic<TransactionSynchronizationManager> mocked =
            mockStatic(TransactionSynchronizationManager.class)) {

        roleManagementService.clearMemberRole(1L, 10L);

        verify(teamPermissionCacheService).evictMember(1L, 10L);

        mocked.verify(() ->
                TransactionSynchronizationManager.registerSynchronization(captor.capture()));
        captor.getValue().afterCommit();  // 手动触发 afterCommit
        verify(userServiceClient).clearPermissionCache(10L);
    }
}
```

**关键规则**：HTTP/MQ 调用不能放在 `@Transactional` 方法内部。必须在 `afterCommit` 回调中执行，且回调必须包裹 try-catch。

> ⚠ **必须用 `MockedStatic` 包裹** `TransactionSynchronizationManager`，否则单测中无事务同步激活，`registerSynchronization(...)` 会抛 `IllegalStateException: Transaction synchronization is not active`。

---

### MQ 消费者测试模式

#### 基础结构

```java
@ExtendWith(MockitoExtension.class)
class UserDeletedEventConsumerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private FileUserCleanupService cleanupService;

    @Test
    void handleUserEvent_validEvent_callsCleanup() throws Exception {
        // 准备 JSON 字符串（模拟 RabbitMQ 原始消息）
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,...}";

        // Mock 反序列化结果
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, ...));

        // 直接调用处理方法
        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        // 验证业务逻辑
        verify(cleanupService).cleanupUserPersonalFiles(1L);
    }
}
```

#### 必须覆盖的场景

| 场景 | 验证点 |
|---|---|
| 重复事件 | 幂等键获取失败，跳过处理，`never()` 验证 cleanup 未调用 |
| 有效事件 | 正常处理流程 + 幂等键释放（根据实现） |
| 处理异常 | cleanup 抛出异常时释放幂等键 + 重新抛出 |
| **毒消息** | `JsonProcessingException` → 抛出 `AmqpRejectAndDontRequeueException` |
| 非目标事件 | 事件类型不匹配时直接忽略 |

#### 毒消息处理（强制要求）

```java
@Test
void handleUserEvent_invalidJson_throwsAmqpRejectAndDontRequeue() throws Exception {
    when(objectMapper.readValue(anyString(), eq(UserDeletedEvent.class)))
            .thenThrow(new JsonProcessingException("bad json") {});

    assertThrows(AmqpRejectAndDontRequeueException.class,
            () -> consumer.handleUserEvent("{bad"));

    verifyNoInteractions(cleanupService);
}
```

**注意**：`JsonProcessingException` 是抽象类，需要匿名实例 `new JsonProcessingException("msg") {}`。

---

### 集成测试模式

#### 基类

所有集成测试继承 `zxyz-common` 的 `AbstractIntegrationTest`：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.autoconfigure.exclude="
                + "com.alibaba.cloud.nacos.registry.NacosDiscoveryAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    protected static String DB_NAME;

    static {
        mysql.start();
        redis.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String jdbcUrl = mysql.getJdbcUrl().replace("/test", "/" + DB_NAME);
        registry.add("spring.datasource.url", () -> jdbcUrl
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

#### 子类模板

```java
class FileMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_file"; }  // 对应服务的数据库名

    @MockitoBean
    private RabbitTemplate rabbitTemplate;  // mock 外部依赖

    @Autowired
    private FileMapper fileMapper;  // 注入被测 Mapper

    @Test
    void insertAndQuery_shouldWork() {
        // 使用真实 MySQL 执行 SQL
    }
}
```

#### 子类约定

| 约定 | 说明 |
|---|---|
| `static { DB_NAME = "zxyz_xxx"; }` | 设置测试数据库名，必须与 Flyway 迁移的库对应 |
| `@MockitoBean` | mock 所有外部服务客户端、`RabbitTemplate`、必要时 `JasyptEncryptor`、`RedissonClient` |
| `@Autowired` | 注入被测 Mapper |
| 不要 mock Mapper | Mapper、Entity、Flyway 迁移全部使用真实实现 |

**关键**：`@MockitoBean` 必须**覆盖所有会让上下文起不来或副作用不可控的外部依赖**。否则 `@SpringBootTest` 加载整个 Spring 上下文时会因下游服务连接失败而启动失败。参考下面 admin-service 示例的完整 `@MockitoBean` 列表。

#### application-test.yml

集成测试需要 `src/test/resources/application-test.yml`。**按本服务实际依赖的下游裁剪**——例如 admin-service 的 `AdminServiceProperties` 绑定 `app` prefix，仅有 `emailService`、`fileService`、`internalServiceToken` 三项，无需配 `team-service` / `user-service`：

```yaml
spring:
  config:
    import: classpath:application-common.yml
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1
  cloud:
    nacos:
      discovery:
        enabled: false

mybatis:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

app:
  internal-service-token: test-internal-token
  email-service:
    base-url: http://localhost:19999    # 指向死地址，对应 EmailProviderClient 已被 @MockitoBean
  file-service:
    base-url: http://localhost:19999    # 同上，对应 StorageProviderClient 已被 @MockitoBean

# admin-service 需要额外配置 Jasypt，否则 JasyptEncryptor 解密路径在集成测试中可能起不来。
# 如集成测试不触达解密路径可省略；如需走过，可 @MockitoBean JasyptEncryptor 或在 yml 中配置：
# jasypt:
#   encryptor:
#     password: test-password
```

**关键点**：
- `nacos.discovery.enabled: false` — 不注册到 Nacos
- 服务 `base-url` 指向 `localhost:19999`（不可达），因为对应的 Client 已被 `@MockitoBean` mock，配置仅用于让 `AdminServiceProperties.normalizedBaseUrl()` 不抛 `IllegalStateException`
- `internal-service-token` 使用固定测试值
- 检查 `AdminServiceProperties` 的 `@ConfigurationProperties(prefix="app")` 与 YAML 键严格对齐（这里是 `app`，不是 `app.admin-service`）

---

### 覆盖率（JaCoCo）

#### 配置位置

JaCoCo 插件配置在根 `pom.xml` 的 `<build><plugins>` 中（**不是** `<pluginManagement>` —— `pluginManagement` 只 pin 版本与默认配置，不会激活 executions）：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
    <configuration>
        <excludes>
            <exclude>uno/acloud/*/config/*</exclude>
            <exclude>uno/acloud/*/*Application.class</exclude>
        </excludes>
    </configuration>
</plugin>
```

#### 报告位置

各模块 `target/site/jacoco/index.html`。

#### 验证命令

```bash
cd ZXYZdatabaseBack
mvn clean test
# 检查 target/site/jacoco/index.html 是否存在
```

---

## 前端测试

### Composable 测试模式

#### 基础结构

```javascript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

// vi.mock() 紧挨放，无空行
vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({ teamId: null })),
}))

vi.mock('@/utils/someUtil', () => ({
  someFunction: vi.fn(),
}))

import { useMyComposable } from '@/composables/useMyComposable'

describe('useMyComposable', () => {
  let instance

  beforeEach(() => {
    vi.clearAllMocks()
    instance = useMyComposable(someParam, options)
  })

  it('应初始化状态', () => {
    expect(instance.someRef.value).toBe(initialValue)
  })

  it('应执行某个操作', () => {
    instance.doAction()
    expect(instance.result.value).toBe(expected)
  })
})
```

#### 两种 Store 依赖处理方式

**方式一：真实 Pinia + mock 其依赖**

```javascript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
}))

vi.mock('@/utils/auth', () => ({
  clearToken: vi.fn(),
}))

import { useCurrentUserStore } from '@/store/currentUser'
import { fetchCurrentUser, login as loginByPassword } from '@/api/auth'
import { clearToken } from '@/utils/auth'

describe('useCurrentUserStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('login calls API then loadProfile', async () => {
    loginByPassword.mockResolvedValue({ code: 1 })
    fetchCurrentUser.mockResolvedValue({ data: fullProfileData })

    const store = useCurrentUserStore()
    const result = await store.login({ username: 'testuser', password: 'pass123' })

    expect(loginByPassword).toHaveBeenCalledWith({ username: 'testuser', password: 'pass123' })
    expect(result.profile).toMatchObject({ id: 1, username: 'testuser' })
  })
})
```

**方式二：mock 整个 store**

```javascript
vi.mock('@/store/currentUser', () => ({
  useCurrentUserStore: vi.fn(),
}))

import { useCurrentUserStore } from '@/store/currentUser'

// 在测试中控制返回值
const mockStore = {
  login: vi.fn().mockResolvedValue({ profile: { id: 1 } }),
}
useCurrentUserStore.mockReturnValue(mockStore)
```

**选择建议**：测试 Store 自身时用方式一；测试依赖 Store 的 Composable 时用方式二。

#### 工厂函数模式

对于参数多的 Composable，使用工厂函数简化实例化：

```javascript
function createManager(overrides = {}) {
  listRef = ref([...items])
  tableRef = ref({ clearSelection: vi.fn(), toggleRowSelection: vi.fn() })
  onSelectionChange = vi.fn()

  return useSelectionManager({
    list: listRef,
    filteredList: filteredListRef,
    tableRef,
    onSelectionChange,
    ...overrides,
  })
}

// 使用
const { selectedIds, setSelectedIds } = createManager()
setSelectedIds([1, 3, 5])
expect(selectedIds.value).toEqual([1, 3, 5])
```

#### 异步测试

```javascript
// async/await + mockResolvedValue
it('应异步加载数据', async () => {
  api.fetch.mockResolvedValue({ data: [1, 2, 3] })
  await instance.loadData()
  expect(instance.list.value).toEqual([1, 2, 3])
})

// nextTick 等待 Vue 响应式刷新
await nextTick()
expect(localStorage.getItem('displayUser')).not.toBeNull()

// 并发控制测试
it('应防止重复请求', async () => {
  fetchCurrentUser.mockImplementation(
    () => new Promise((resolve) => setTimeout(() => resolve({ data }), 100)),
  )
  const p1 = store.loadProfile()
  const p2 = store.loadProfile()
  await p1
  expect(fetchCurrentUser).toHaveBeenCalledTimes(1)
})
```

#### 浏览器 API Stub

```javascript
// requestAnimationFrame（happy-dom 不触发 RAF）
beforeEach(() => {
  vi.stubGlobal('requestAnimationFrame', (cb) => {
    cb()
    return 0
  })
})

// localStorage
beforeEach(() => {
  localStorage.clear()
})
```

---

### Store 测试模式

```javascript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMyStore } from '@/store/myStore'

describe('useMyStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  // 顶层 fixture
  const fullData = {
    id: 1,
    name: 'Test',
    roles: ['admin'],
    permissions: ['file:write'],
  }

  describe('setProfile', () => {
    it('应正常设置 profile', () => {
      const store = useMyStore()
      store.setProfile(fullData)
      expect(store.profile.id).toBe(1)
    })

    it('应处理缺失字段', () => {
      const store = useMyStore()
      store.setProfile({ id: 2 })
      expect(store.profile.name).toBe('')  // 默认值
    })
  })

  describe('computed', () => {
    it('isAdmin 应返回正确值', () => {
      const store = useMyStore()
      store.setProfile({ ...fullData, roles: ['system_admin'] })
      expect(store.isAdmin).toBe(true)
    })
  })
})
```

---

### API 测试模式

```javascript
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/utils/request', () => ({
  default: {
    post: vi.fn().mockResolvedValue({ code: 1 }),
    get: vi.fn().mockResolvedValue({ code: 1, data: {} }),
  },
}))

import { login, register } from '@/api/auth'
import request from '@/utils/request'

describe('auth API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('login', () => {
    it('应发送 POST 到正确路径', async () => {
      await login({ username: 'u', password: 'p' })
      expect(request.post).toHaveBeenCalledWith('/api/users/login', {
        username: 'u',
        password: 'p',
      })
    })

    it('应返回服务器响应', async () => {
      const mockResponse = { code: 1, data: { token: 'abc' } }
      request.post.mockResolvedValue(mockResponse)
      const result = await login({ username: 'u', password: 'p' })
      expect(result).toEqual(mockResponse)
    })

    it('应传播网络错误', async () => {
      request.post.mockRejectedValue(new Error('network error'))
      await expect(login({ username: 'u', password: 'p' })).rejects.toThrow('network error')
    })
  })
})
```

#### 真 HTTP 服务器测试（createApiClient）

对于 HTTP 客户端工厂，使用 Node.js 内置 `http.createServer`：

```javascript
import http from 'node:http'

let server

beforeEach((done) => {
  server = http.createServer((req, res) => {
    if (req.url === '/success') {
      res.writeHead(200)
      res.end(JSON.stringify({ code: 1, data: { id: 1 } }))
    } else if (req.url === '/business-error') {
      res.writeHead(200)
      res.end(JSON.stringify({ code: 4001, message: '业务错误' }))
    }
    // ... 其他路由
  })
  server.listen(0, () => done())
})

afterEach(() => server?.close())
```

---

### 组件测试模式（Phase B 补）

> 当前 `@vue/test-utils` 已安装但全仓库零 import，组件测试将在 Phase B 建立。

**Element Plus 全局 mock**：

```javascript
// test/setup.js 中统一 mock
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElDialog: { ... },
  ElForm: { ... },
}))
```

**基础组件测试模板**：

```javascript
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import MyComponent from '@/components/MyComponent.vue'

describe('MyComponent', () => {
  it('应渲染正确内容', () => {
    const wrapper = mount(MyComponent, {
      props: { title: '测试标题' },
    })
    expect(wrapper.text()).toContain('测试标题')
  })

  it('应触发事件', async () => {
    const wrapper = mount(MyComponent)
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('update')).toBeTruthy()
  })
})
```

`shallowMount` 用于只渲染当前组件、不渲染子组件。

---

### import 顺序约定

前端测试文件的 import 顺序：

```
// 第 1 组：vitest / vue / pinia 导入（按字母序）
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

// 第 2 组：vi.mock() 调用（紧挨，无空行）
vi.mock('element-plus', () => ({ ... }))
vi.mock('@/api/auth', () => ({ ... }))
vi.mock('@/utils/request', () => ({ ... }))

// 第 3 组：@/ 和第三方包导入（空行分隔）
import { useMyComposable } from '@/composables/useMyComposable'
import { useMyStore } from '@/store/myStore'
import request from '@/utils/request'
```

**注意**：ESLint `import-x/order` 只校验分组顺序，不校验 mock 与 import 的相对先后。此约定靠人工遵守。现有部分文件（如 `useBatchFeedback.spec.js`）已偏离此约定，后续逐步修正。

---

## TDD 工作流

### 红 → 绿 → 重构

```
1. 红（Red）：写一个失败的测试
   └── 运行测试，确认它失败（且失败原因符合预期）

2. 绿（Green）：写最少代码通过测试
   └── 运行测试，确认通过

3. 重构（Refactor）：改进代码结构
   └── 运行测试，确保仍通过
```

### 后端 TDD 示例

**需求**：给 `ConfigService` 新增一个 `getAsString(key, defaultValue)` 方法 —— 返回字符串值，键不存在或解密后为空时返回 `defaultValue`。（用于说明 TDD；本方法目前未实现。）

**Step 1 — 写失败测试**：

```java
@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock private SysConfigMapper configMapper;
    @Mock private SysConfigAuditMapper auditMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private JasyptEncryptor jasyptEncryptor;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configMapper, auditMapper, redisTemplate, jasyptEncryptor);
    }

    @Test
    void getAsString_existingKey_returnsDecryptedValue() {
        // 准备实体（@Data 无多参构造，只能 new + setter）
        SysConfig config = new SysConfig();
        config.setConfigValue("104857600");
        when(configMapper.selectByKey("app.max.upload.size")).thenReturn(config);
        // ConfigService.get 会调 jasyptEncryptor.decrypt(configValue)
        when(jasyptEncryptor.decrypt("104857600")).thenReturn("104857600");

        String result = service.getAsString("app.max.upload.size", "fallback");

        assertEquals("104857600", result);
    }

    @Test
    void getAsString_missingKey_returnsDefaultValue() {
        when(configMapper.selectByKey("nonexistent")).thenReturn(null);

        String result = service.getAsString("nonexistent", "fallback");

        assertEquals("fallback", result);
    }
}
```

**Step 2 — 运行，确认失败**（`service.getAsString(...)` 方法不存在，编译失败）。

**Step 3 — 写最少实现**：

```java
public String getAsString(String key, String defaultValue) {
    String value = get(key);
    return (value == null || value.isEmpty()) ? defaultValue : value;
}
```

**Step 4 — 运行，确认通过**。

**Step 5 — 重构**（如需，例如抽取 `isEmpty` 工具）。

### 前端 TDD 示例

**需求**：给 `useFileUpload` 新增一个 `hasPendingFiles` 计算属性 —— 当 `fileList` 非空且当前未在上传中时为 `true`，否则为 `false`。（用于说明 TDD；本属性目前未实现。）

**Step 1 — 写失败测试**：

```javascript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}))
vi.mock('@/services/upload', () => ({ uploadFileWithPresign: vi.fn() }))
vi.mock('@/composables/useCurrentSpaceContext', () => ({
  resolveSpaceRequestParams: vi.fn(() => ({ teamId: null })),
}))
vi.mock('@/utils/uploadProgress', () => ({
  calculateUploadPercentage: vi.fn(() => 0),
  getUploadTrackingKey: vi.fn(() => 'key-1'),
  sumUploadedBytes: vi.fn(() => 0),
}))
vi.mock('@/utils/nameConflict', () => ({
  buildBatchPredictedNames: vi.fn(() => []),
  FILE_TYPE: { FILE: 1, FOLDER: 0 },
}))
vi.mock('@/utils/id', () => ({ createClientId: vi.fn(() => 'client-1') }))
vi.mock('@/utils/fileValidation', () => ({
  validateFiles: vi.fn(() => ({ valid: [], rejected: [] })),
  MAX_FILE_SIZE: 5368709120,
  DANGEROUS_EXTENSIONS: ['.exe', '.bat', '.cmd', '.sh', '.js'],
}))

import { useFileUpload } from '@/composables/useFileUpload'

describe('useFileUpload — hasPendingFiles', () => {
  let upload

  beforeEach(() => {
    vi.clearAllMocks()
    upload = useFileUpload(ref(1), { spaceContext: ref({}) })
  })

  it('应初始化为 false（空列表未上传）', () => {
    expect(upload.hasPendingFiles.value).toBe(false)
  })

  it('appendFiles 后应变为 true', () => {
    const file = new File(['hello'], 'test.txt', { type: 'text/plain' })
    // validateFiles 被 mock 为 { valid: [] }，需要先 mock 返回该文件
    const { validateFiles } = require('@/utils/fileValidation')
    validateFiles.mockReturnValue({ valid: [file], rejected: [] })

    upload.appendFiles([file])

    expect(upload.hasPendingFiles.value).toBe(true)
  })

  it('uploading 中应为 false', () => {
    const file = new File(['hello'], 'test.txt', { type: 'text/plain' })
    const { validateFiles } = require('@/utils/fileValidation')
    validateFiles.mockReturnValue({ valid: [file], rejected: [] })
    upload.appendFiles([file])

    upload.uploading.value = true  // 模拟上传中

    expect(upload.hasPendingFiles.value).toBe(false)
  })
})
```

**Step 2 — 运行，确认失败**（`upload.hasPendingFiles` 为 `undefined`）。

**Step 3 — 写最少实现**：

```javascript
// useFileUpload.js 内部
import { computed, ref } from 'vue'

export function useFileUpload(currentId, options = {}) {
  // ... 原有代码 ...
  const uploading = ref(false)
  const fileList = ref([])

  const hasPendingFiles = computed(() => !uploading.value && fileList.value.length > 0)

  // ... 原有代码 ...

  return {
    // ... 原有导出 ...
    hasPendingFiles,  // 新增
  }
}
```

**Step 4 — 运行，确认通过**。

---

## 测试命名约定

### 后端

| 类型 | 命名规则 | 示例 |
|---|---|---|
| 测试类 | `*Test.java` | `FileUploadServiceTest` |
| 集成测试 | `*MapperIntegrationTest.java` | `FileMapperIntegrationTest` |
| 上下文冒烟测试 | `*ApplicationTests.java` | `ZxyzImApplicationTests`（唯一例外） |
| 测试方法 | `methodName_scenario_expectedBehavior` | `confirmUpload_exceedingQuota_shouldThrow` |

### 前端

| 类型 | 命名规则 | 示例 |
|---|---|---|
| 测试文件 | `*.spec.js`，放在对应目录的 `__tests__/` 下 | `src/composables/__tests__/useFileUpload.spec.js` |
| describe | 中文优先，功能分组 | `describe('useFileUpload', ...)` |
| it | 中文 `应...` 或英文 `should...` | `it('应初始化状态', ...)` |

---

## 命令速查

### 后端

```bash
# 编译检查
mvn clean -DskipTests compile

# 全量测试
mvn test

# 单模块测试
mvn test -pl zxyz-file-service

# 单测试类
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest

# 单测试方法
mvn test -pl zxyz-file-service -Dtest=FileUploadServiceTest#confirmUpload_sufficientQuota_shouldSucceed

# 仅运行集成测试
mvn test -pl zxyz-file-service -Dgroups=integration

# 单服务运行
mvn -pl zxyz-project-service spring-boot:run
```

### 前端

```bash
# 单次运行
npm run test

# Watch 模式
npm run test:watch

# 覆盖率（需先 npm install -D @vitest/coverage-v8）
npm run test:coverage
```

---

## CI 质量门禁

### 当前状态

| 检查项 | dev 分支 | PR / main |
|---|---|---|
| 前端 lint | 跳过 | 运行 |
| 前端 test | 跳过 | 运行 |
| 后端 test | 跳过 | 运行 |
| 覆盖率 | 无 | 无 |

### 覆盖率工具

**后端 JaCoCo**：在根 `pom.xml` 配置后，每次 `mvn test` 自动生成报告。

**前端 V8 coverage**：需先安装依赖（当前 `package.json` 未声明 `@vitest/coverage-v8`，`npm run test:coverage` 报 `MISSING DEPENDENCY`）：

```bash
cd ZXYZdatabaseFront
npm install -D @vitest/coverage-v8
```

安装后 `npm run test:coverage` 可正常生成报告。阈值设置需先实测基线（vitest 4.x 默认 `coverage.all:false`，报告池为测试 import 链触达的文件，而非 `src/**` 全部）。

### 修改 CI 配置

dev 分支启用测试：修改 `.github/workflows/ci-cd.yml`，移除 "Determine skip quality" 步骤（第 145–152 行），保留 `workflow_dispatch` 的 `skip_quality` 输入用于紧急热修复。

---

## admin-service 测试示例

> admin-service 是全项目唯一一处 pom 已声明 test-jar + Testcontainers 依赖但 `src/test/` 完全为空的模块。下面给出可直接套用的示例。

### 单元测试：ConfigService

> `ConfigService` 构造器需要 4 个参数：`SysConfigMapper`、`SysConfigAuditMapper`、`StringRedisTemplate`、`JasyptEncryptor`（`uno.acloud.common.util.JasyptEncryptor`）。**不可只 mock 一个**。
> `get(key)` 对 `selectByKey` 返回的非 null config，**无条件**调 `jasyptEncryptor.decrypt(configValue)`，相关单测必须 stub `decrypt`。
> `update` 是 `@Transactional` 方法，在方法体内调用 `TransactionSynchronizationManager.registerSynchronization(...)`。纯 Mockito 单测中**必须用 `MockedStatic` 包裹** `TransactionSynchronizationManager`，否则会抛 `IllegalStateException`。

```java
package uno.acloud.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private SysConfigAuditMapper auditMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private JasyptEncryptor jasyptEncryptor;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configMapper, auditMapper, stringRedisTemplate, jasyptEncryptor);
    }

    @Test
    void get_existingKey_returnsDecryptedValue() {
        SysConfig config = new SysConfig();
        config.setConfigValue("ENC(encrypted)");
        when(configMapper.selectByKey("app.name")).thenReturn(config);
        when(jasyptEncryptor.decrypt("ENC(encrypted)")).thenReturn("my-app");

        String result = service.get("app.name");

        assertEquals("my-app", result);
        verify(configMapper).selectByKey("app.name");
    }

    @Test
    void get_missingKey_returnsNull() {
        when(configMapper.selectByKey("nonexistent")).thenReturn(null);

        String result = service.get("nonexistent");

        assertNull(result);
    }

    @Test
    void get_nullValue_returnsNull() {
        SysConfig config = new SysConfig();
        config.setConfigValue(null);
        when(configMapper.selectByKey("app.name")).thenReturn(config);
        // 当 configValue 为 null 时，ConfigService.get 会调用 decrypt(null)。
        // 需要让 decrypt(null) 返回非 §NULL§ 的结果以触达 NULL_SENTINEL 分支判断。
        // 但更稳妥的写法是 observe 实际行为：decrypt(null) 默认返回 null，价值经过 §NULL§ 比较
        // -> 结果是 null。本测试验证此行为。

        String result = service.get("app.name");

        assertNull(result);
    }

    @Test
    void getType_integerValue_returnsInteger() {
        SysConfig config = new SysConfig();
        config.setConfigValue("42");
        when(configMapper.selectByKey("app.limit")).thenReturn(config);
        when(jasyptEncryptor.decrypt("42")).thenReturn("42");  // 必须显式 stub

        Integer result = service.get("app.limit", Integer.class);

        assertEquals(42, result);
    }

    @Test
    void getType_unsupportedType_throwsException() {
        SysConfig config = new SysConfig();
        config.setConfigValue("value");
        when(configMapper.selectByKey("app.test")).thenReturn(config);
        when(jasyptEncryptor.decrypt("value")).thenReturn("value");  // 必须 stub，否则 get 返回 null，根本走不到 switch

        assertThrows(IllegalArgumentException.class,
                () -> service.get("app.test", Double.class));
    }

    @Test
    void update_writesAuditAndNotifiesAfterCommit() {
        // update 是 @Transactional 方法，单测中必须 MockedStatic 包裹 TransactionSynchronizationManager
        ArgumentCaptor<TransactionSynchronization> syncCaptor =
                ArgumentCaptor.forClass(TransactionSynchronization.class);

        try (MockedStatic<TransactionSynchronizationManager> mocked =
                mockStatic(TransactionSynchronizationManager.class)) {

            // update 内部先 get(key) 取 oldValue；selectByKey 返回 null -> oldValue = null
            when(configMapper.selectByKey("app.name")).thenReturn(null);

            service.update("app.name", "new-value", 1L);

            verify(configMapper).updateValue("app.name", "new-value");
            verify(auditMapper).insert(eq("app.name"), isNull(), eq("new-value"), eq(1L));
            mocked.verify(() ->
                    TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));

            // 模拟事务提交：触发 afterCommit -> 应发送 Redis 通知
            syncCaptor.getValue().afterCommit();
            verify(stringRedisTemplate).convertAndSend("zxyz:config:changed", "app.name");
        }
    }
}
```

> 注：上述 `update_writesAuditAndNotifiesAfterCommit` 验证了事务提交后的 Redis 通知逻辑，但**未直接验证本地 Caffeine 缓存失效**——因 `cache` 是 `ConfigService` 内部 private final 字段，外部不可访问。若需验证缓存行为，可改用集成测试（真实 mysql + Redis，连续 update 后再 get 看缓存是否失效），或通过观察行为（再次 get 时是否再次走 `selectByKey`）间接验证。

### 单元测试：ConfigAdminController

> `ConfigAdminController` 构造器需要 3 个参数：`ConfigService`、`SysConfigMapper`、`SysConfigAuditMapper`。`listAll()` 与 `listAuditLogs()` 不经过 Service，直接调用 Mapper。
> 类级 `@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)` 在 MockitoExtension 单测中**注解不生效**，本测试**仅验证业务委派**，**不验证授权**（覆盖授权需走 `@WebMvcTest` + Sa-Token mock，见 Phase B）。

```java
package uno.acloud.admin.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.admin.service.ConfigService;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigAdminControllerTest {

    @Mock
    private ConfigService configService;

    @Mock
    private SysConfigMapper configMapper;

    @Mock
    private SysConfigAuditMapper auditMapper;

    private ConfigAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ConfigAdminController(configService, configMapper, auditMapper);
    }

    @Test
    void getByKey_existingKey_returnsValue() {
        when(configService.get("app.name")).thenReturn("my-app");

        Result<String> result = controller.getByKey("app.name");

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("my-app", result.getData());
    }

    @Test
    void getByKey_missingKey_returnsNotFound() {
        when(configService.get("nonexistent")).thenReturn(null);

        Result<String> result = controller.getByKey("nonexistent");

        assertEquals(ErrorCode.NOT_FOUND, result.getCode());
    }

    @Test
    void update_validRequest_callsService() {
        ConfigAdminController.UpdateConfigRequest request = new ConfigAdminController.UpdateConfigRequest();
        request.setValue("new-value");

        Result<Void> result = controller.update("app.name", request, 1L);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(configService).update("app.name", "new-value", 1L);
    }

    @Test
    void update_blankValue_shouldBeRejectedByValidation() {
        // UpdateConfigRequest.value 有 @NotBlank + @Size(max=4096)，但纯单元测试中 Jakarta Validation
        // 不会自动执行。如需测试校验，使用集成测试（@SpringBootTest）或 @WebMvcTest + @Validated。
        // 此处仅记录契约：空值校验在 web 层验证，单测中调用 controller.update 时需手工保证非空。
    }
}
```

### 单元测试：ProviderAdminController

> `ProviderAdminController` 构造器接收 `StorageProviderClient` 与 `EmailProviderClient` 两个 client。该 Controller 共 **6** 个端点：`listStorageProviders`、`updateStorageProvider`、`storageProviderHealth`、`listEmailProviders`、`updateEmailProvider`、`emailProviderHealth`。
> Client 的 `listAll()` 与 `healthCheck()` 方法返回 `com.fasterxml.jackson.databind.JsonNode`（不是 `List<?>`）。`updateConfig` 第二参数类型是 `Object`（不是 `Map<String, Object>`）——Mockito matcher 不能用 `any(Map.class)`，要用 `any()` 或 `any(Object.class)`。

```java
package uno.acloud.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderAdminControllerTest {

    @Mock
    private StorageProviderClient storageProviderClient;

    @Mock
    private EmailProviderClient emailProviderClient;

    private ProviderAdminController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ProviderAdminController(storageProviderClient, emailProviderClient);
    }

    @Test
    void listStorageProviders_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("[{\"id\":\"local\"}]");
        when(storageProviderClient.listAll()).thenReturn(node);

        Result<JsonNode> result = controller.listStorageProviders();

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(storageProviderClient).listAll();
    }

    @Test
    void updateStorageProvider_delegatesToClient() {
        Map<String, Object> request = Map.of("enabled", true);

        Result<Void> result = controller.updateStorageProvider("local", request);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        // updateConfig 第二参数类型是 Object，用 any() 而非 any(Map.class)
        verify(storageProviderClient).updateConfig(eq("local"), any());
    }

    @Test
    void storageProviderHealth_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"status\":\"UP\"}");
        when(storageProviderClient.healthCheck("local")).thenReturn(node);

        Result<JsonNode> result = controller.storageProviderHealth("local");

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(storageProviderClient).healthCheck("local");
    }

    @Test
    void listEmailProviders_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("[{\"id\":\"smtp\"}]");
        when(emailProviderClient.listAll()).thenReturn(node);

        Result<JsonNode> result = controller.listEmailProviders();

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(emailProviderClient).listAll();
    }

    @Test
    void updateEmailProvider_delegatesToClient() {
        Map<String, Object> request = Map.of("enabled", false);

        Result<Void> result = controller.updateEmailProvider("smtp", request);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        verify(emailProviderClient).updateConfig(eq("smtp"), any());
    }

    @Test
    void emailProviderHealth_delegatesToClient() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"status\":\"UP\"}");
        when(emailProviderClient.healthCheck("smtp")).thenReturn(node);

        Result<JsonNode> result = controller.emailProviderHealth("smtp");

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertSame(node, result.getData());
        verify(emailProviderClient).healthCheck("smtp");
    }
}
```

### 集成测试：ConfigMapperIntegrationTest

> `AbstractIntegrationTest` 上有 `@SpringBootTest` + `@ActiveProfiles("test")`，所以**会加载 admin-service 完整 Spring 上下文**。必须用 `@MockitoBean` mock 所有外部依赖 client（`EmailProviderClient`、`StorageProviderClient`）、`RabbitTemplate`，否则上下文因下游连接失败而起不来。可能还需 mock `JasyptEncryptor` 或在 yml 配 `jasypt.encryptor.password`，否则 Spring 启动时 `JasyptEncryptor` 构造或解密路径会失败。
> `SysConfig.configType` 字段是 `String`（不是 `int`/`Integer`），用 `setConfigType("SYSTEM")`。

```java
package uno.acloud.admin.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.admin.client.EmailProviderClient;
import uno.acloud.admin.client.StorageProviderClient;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.common.AbstractIntegrationTest;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMapperIntegrationTest extends AbstractIntegrationTest {

    static { DB_NAME = "zxyz_config"; }

    // 必须把所有外部依赖 bean mock 掉，否则 @SpringBootTest 加载完整上下文会失败
    @MockitoBean private EmailProviderClient emailProviderClient;
    @MockitoBean private StorageProviderClient storageProviderClient;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private JasyptEncryptor jasyptEncryptor;  // 避免真实 Jasypt 在测试中需 password
    @MockitoBean private StringRedisTemplate stringRedisTemplate;  // 避免真实 Redis 在 ConfigService 中被注入

    @Autowired
    private SysConfigMapper configMapper;

    @Test
    void insertAndSelectByKey_roundTrip() {
        SysConfig config = new SysConfig();
        config.setConfigKey("test.key");
        config.setConfigValue("test-value");
        config.setConfigType("SYSTEM");  // String 类型，不是 int
        configMapper.insert(config);

        SysConfig found = configMapper.selectByKey("test.key");
        assertNotNull(found);
        assertEquals("test-value", found.getConfigValue());
    }

    @Test
    void updateValue_modifiesExistingKey() {
        SysConfig config = new SysConfig();
        config.setConfigKey("update.key");
        config.setConfigValue("original");
        config.setConfigType("SYSTEM");
        configMapper.insert(config);

        configMapper.updateValue("update.key", "modified");

        SysConfig updated = configMapper.selectByKey("update.key");
        assertEquals("modified", updated.getConfigValue());
    }
}
```

> 同时需在 `zxyz-admin-service/src/test/resources/` 新建 `application-test.yml`，内容见上文「集成测试模式 → application-test.yml」一节。

---

## 附录

### 常见陷阱

| 陷阱 | 后果 | 避免方式 |
|---|---|---|
| `@WebMvcTest` 无法 mock Sa-Token 静态 API | 测试启动失败或行为不符合预期 | 使用手动 `new` Controller + 直接调用 |
| `StpUtil` 静态方法在纯单测中不可用 | `IllegalStateException` 或 NPE | 通过 `@CurrentUser` 注入的 `Long userId` 单测中直接传值；其他静态调用集成测试中验证 |
| `@SaCheckRole` 在 MockitoExtension 单测中不生效 | 注解被静默跳过，单测过但授权实际错 | 单测只验证业务委派；授权验证走 `@WebMvcTest` + Sa-Token mock |
| `update` 等 `@Transactional 方法`内调用 `TransactionSynchronizationManager.registerSynchronization` | 单测中抛 `IllegalStateException: Transaction synchronization is not active` | 必须用 `MockedStatic` 包裹，手动触发 `afterCommit` |
| `get(key)` 内部无条件调 `jasyptEncryptor.decrypt(configValue)` | 不 stub `decrypt` 时返回 null，错过逻辑分支 | Stub `decrypt` 让 `get` 返回非 null 值再触达下游 |
| 毒消息未抛出 `AmqpRejectAndDontRequeueException` | 消息被 ACK 入死循环 | MQ 消费者测试必须覆盖此场景 |
| HTTP 调用放在 `@Transactional` 内部 | 持有 DB 连接 during 远程 I/O | 使用 `afterCommit` + try-catch |
| `@CacheEvict(allEntries = true)` 误用 | 清空所有团队的缓存 | 使用 `scan` + 模式匹配精确清除 |
| `@ConfigurationProperties` prefix 不匹配 | 配置值绑定为空 | 检查 YAML key 与 `prefix=` 完全一致（如 admin-service 是 `app`） |
| `config.datasource` vs `spring.datasource` | admin-service DataSource 未注入 | admin-service 必须用 `config.datasource.*` |
| `IntegrationTest` 未 mock 外部 ServiceClient | `@SpringBootTest` 因下游连接失败而起不来 | `@MockitoBean` 所有外部 client + `RabbitTemplate`，必要时 `JasyptEncryptor` |
| `ErrorCode` 是 `int` 常量类不是枚举，`Result.getCode()` 返回 `Integer` | `== someIntegerObj` 在 -128~127 区间外是引用比较坑 | 与 `int` 字面量比较安全；与其他 `Integer` 变量比较用 `.equals()` 或 `.intValue()` |
| `StorageProviderClient.listAll()` 返回 `JsonNode` 不是 `List` | 测试中 `thenReturn(List.of())` 编译失败 | 用 `ObjectMapper.readTree(...)` 构造 `JsonNode` |
| `StorageProviderClient.updateConfig(String, Object)` 第二参数是 `Object` 不是 `Map` | Mockito `any(Map.class)` 编译失败 | 用 `any()` 或 `any(Object.class)`；用 `eq(someMap)` 仍可工作 |

### 测试文件清单（实测，按模块）

#### 后端（共 75 个文件）

| 模块 | 文件数 | 拆分 |
|---|---|---|
| `zxyz-common` | 5 | 1 抽象基类 `AbstractIntegrationTest` + 4 单元测试 |
| `zxyz-user-service` | 9 | 2 Mapper 集成 + 6 Service（含 `LoginRateLimiterTest`）+ 1 Controller |
| `zxyz-team-service` | 12 | 2 Mapper 集成 + 9 Service + 1 EventPublisher |
| `zxyz-project-service` | 10 | 2 Mapper 集成 + 4 Service + 1 Assembler + 1 ErrorCode + 1 RestClient + 1 AOP |
| `zxyz-file-service` | 15 | 2 Mapper 集成 + 10 Service + 2 Controller + 1 MQ |
| `zxyz-share-service` | 4 | 4 Service |
| `zxyz-email-service` | 7 | 7 application 层（DDD） |
| `zxyz-im-service` | 11 | 1 上下文冒烟 + 7 application + 1 config + 1 controller + 1 infrastructure |
| `zxyz-audit-service` | 1 | 1 MQ 消费者 |
| `zxyz-gateway` | 1 | 1 Filter 配置 |

总和：5 + 9 + 12 + 10 + 15 + 4 + 7 + 11 + 1 + 1 = **75**

#### 前端（共 26 个文件）

| 目录 | 文件数 |
|---|---|
| `src/api/__tests__/` | 4 |
| `src/composables/__tests__/` | 16 |
| `src/store/__tests__/` | 1（仅 `currentUser.spec.js`） |
| `src/store/im/__tests__/` | 1（`normalizers.spec.js`，独立子目录） |
| `src/utils/__tests__/` | 3 |
| `src/router/__tests__/` | 1 |

总和：4 + 16 + 1 + 1 + 3 + 1 = **26**

> 注：CLAUDE.md 中"22 文件 / 244 用例"为历史数据，已过期；当前文件数 26、用例数约 278（按 `it()` 计数）。

### 涉及的核心 FQN 速查

- `uno.acloud.admin.service.ConfigService`（构造器 4 参）
- `uno.acloud.admin.controller.ConfigAdminController`（构造器 3 参，含嵌套 `UpdateConfigRequest`，4 个端点：`listAll`/`getByKey`/`update`/`listAuditLogs`）
- `uno.acloud.admin.controller.ProviderAdminController`（构造器 2 参，6 个端点，含两个 health）
- `uno.acloud.admin.domain.SysConfig`（`@Data` + `@TableName`，`configType` 是 `String`，无 `create()`/`builder()`）
- `uno.acloud.admin.mapper.SysConfigMapper`（继承 `BaseMapper<SysConfig>`，自定义 `selectByKey`、`updateValue`；`insert` 由父接口继承）
- `uno.acloud.admin.mapper.SysConfigAuditMapper`（自定义 `insert(String, String, String, Long)`）
- `uno.acloud.admin.client.StorageProviderClient` / `EmailProviderClient`（继承 `AbstractServiceClient`；`listAll()`/`healthCheck()` 返 `JsonNode`；`updateConfig(String, Object)`）
- `uno.acloud.admin.config.AdminServiceProperties`（`@ConfigurationProperties(prefix="app")`，三字段：`emailService`、`fileService`、`internalServiceToken`）
- `uno.acloud.common.util.JasyptEncryptor`（`@Component`，包 `uno.acloud.common.util`，**不是** `common.config`）
- `uno.acloud.common.Result<T>`（`code` 是 `Integer`，`SUCCESS=1` 来自 `ErrorCode`）
- `uno.acloud.common.ErrorCode`（`public final class`，常量 `SUCCESS=1`、`NOT_FOUND=4040`、`BAD_REQUEST=4000` 等，非枚举）