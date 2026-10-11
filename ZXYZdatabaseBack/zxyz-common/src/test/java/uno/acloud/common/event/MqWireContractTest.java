package uno.acloud.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uno.acloud.common.audit.OperateLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ 线契约门禁（ISSUE/54 §2.3-C1）：对每个跨服务事件载荷做「生产等价 mapper 往返」。
 *
 * <h2>它防的是什么（51 号 P0 的结构性条件）</h2>
 * 51 号 P0：OperateLogConsumer 运行时缺 jackson-datatype-jsr310，LocalDateTime 反序列化全量抛
 * InvalidDefinitionException，审计静默丢 17 小时。该缺陷的结构性条件至今没有消除——
 * 6 个 MQ 消费者测试把 ObjectMapper 整个 mock（事件反序列化能力零验证），事件 record
 * 一旦加入 LocalDateTime/Instant 字段，所有消费端将同时静默炸掉。本测试把
 * 「每个事件 record 都能被生产环境的 ObjectMapper 无损往返」钉成常驻断言：
 * jsr310 缺失/JavaTimeModule 未注册在这里<b>响亮失败</b>，而不是在生产里静默丢数据。
 *
 * <h2>什么是「生产等价 mapper」</h2>
 * 全仓 88 个 main 文件（含全部 MQ 发布端 {@code objectMapper.writeValueAsString(event)}
 * 与消费端 {@code objectMapper.readValue(json, XxxEvent.class)}）用的是
 * {@code com.fasterxml} 的 Jackson 2 轨 ObjectMapper，Spring 注入的 bean 来自
 * Boot 4 的 {@code spring-boot-jackson2} 自动配置（zxyz-common 以 compile 依赖
 * 把该模块传递给全部 10 服务，见 zxyz-common/pom.xml 注释）。因此本测试用
 * {@link ApplicationContextRunner} 装配真实的 {@link Jackson2AutoConfiguration}
 * 取出容器 bean——等价于发端/收端实际注入的那个 ObjectMapper
 * （Jackson 3 轨 tools.jackson 由 starter-web 提供，仅服务 HTTP 层，不经 MQ）。
 *
 * <h2>为什么 record 能反序列化</h2>
 * Boot 4 的 jackson2 自动配置显式启用了 ParameterNamesModule
 * （{@code Jackson2AutoConfiguration.ParameterNamesModuleConfiguration}），
 * Java 21 编译带 {@code -parameters} 时 record 组件可按名绑定，无需 @JsonCreator。
 * 这一点本身就是契约的一部分——若未来有人改掉编译参数或换成裸 new ObjectMapper()，
 * 这里全量红给你看。
 *
 * <h2>覆盖清单（2026-10-11 全仓盘点）</h2>
 * <ul>
 *   <li>zxyz-common/event 包 7 个 record：6 个跨服务事件
 *       + {@link FileResourceChangedEvent}（全仓唯一含 LocalDateTime 的 MQ 载荷，
 *       file 发 → im/project 消，jsr310 需求全在它身上）；</li>
 *   <li>{@code OperateLog}（common/audit 包，类而非 record）：audit 队列的 wire 载荷，
 *       含 {@code operateTime}（LocalDateTime）——51 号 P0 的当事类型，纳入往返；</li>
 *   <li>{@code project.member.*} 的 Map 载荷与 {@code ImDomainEvent}：
 *       前者无固定 schema（消费端 readTree 取字段），后者是 Spring 进程内事件
 *       （ApplicationEventPublisher，不进 MQ wire），均不入本门禁，原因记录在报告。</li>
 * </ul>
 */
@Tag("mq-contract")
class MqWireContractTest {

    // ==========================================================================
    // 生产等价 mapper：Boot 4 spring-boot-jackson2 自动配置产出的真实 ObjectMapper bean
    // ==========================================================================

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class));

    /** 发端/收端实际注入的 ObjectMapper（容器 bean，非裸 new）。 */
    private static ObjectMapper productionMapper() {
        List<ObjectMapper> found = new ArrayList<>();
        RUNNER.run(context -> {
            assertThat(context).as("spring-boot-jackson2 自动配置必须产出唯一的 ObjectMapper bean —— "
                    + "生产环境的 MQ 序列化路径就靠它（若 Boot 4 模块坐标变更，这里响亮失败）")
                    .hasSingleBean(ObjectMapper.class);
            found.add(context.getBean(ObjectMapper.class));
        });
        assertThat(found).as("ApplicationContextRunner 回调未执行（反空扫，防 runner 失效时静默通过）").isNotEmpty();
        return found.get(0);
    }

    // ==========================================================================
    // 事件清单：模块归属 + JavaTimeModule 需求显式化
    // ==========================================================================

    /** JavaTimeModule 需求等级。 */
    enum JavaTimeRequirement {
        /** 载荷含 LocalDateTime/Instant 等 java.time 字段：jsr310 缺失 ⇒ 反序列化必炸。 */
        REQUIRED,
        /** 载荷纯 JDK 基本类型/String：不依赖 jsr310（显式记录，防「以为不需要」的漂移）。 */
        NOT_REQUIRED
    }

    /** 一条 MQ 线载荷的契约条目。 */
    record WirePayload(String module, String queue, Class<?> payloadType, JavaTimeRequirement javaTime) {
    }

    /**
     * 全仓 MQ wire 载荷清单（2026-10-11 盘点，与 54 号 §2.3 的口径一致）。
     * <p>queue 名与 {@code @RabbitListener} / RabbitMqConfig 常量一一对应；
     * 新增跨服务事件必须在此登记——本测试用「清单基数 ≥ 已知数」反空扫，
     * 漏登记不会被静默放过（见 {@link #清单基数不得缩水}）。</p>
     */
    static final List<WirePayload> WIRE_PAYLOADS = List.of(
            // ---- zxyz-common/event：跨服务结构化事件 record ----
            new WirePayload("zxyz-common", "user.profile.updated（user→share/im）", UserProfileUpdatedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            new WirePayload("zxyz-common", "user.deleted（user→file/share/im/project/team）", UserDeletedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            new WirePayload("zxyz-common", "team.created（team→im）", TeamCreatedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            new WirePayload("zxyz-common", "team.updated（team→im）", TeamUpdatedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            new WirePayload("zxyz-common", "team.member.added（team→im）", TeamMemberAddedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            new WirePayload("zxyz-common", "team.member.removed（team→im）", TeamMemberRemovedEvent.class, JavaTimeRequirement.NOT_REQUIRED),
            // 全仓唯一含 LocalDateTime 的 MQ 载荷：file 发 → im/project 消（54 号 §2.3-A3）
            new WirePayload("zxyz-common", "file.resource.changed（file→im/project）", FileResourceChangedEvent.class, JavaTimeRequirement.REQUIRED),
            // ---- zxyz-common/audit：audit.log 队列的 wire 载荷（51 号 P0 当事类型，类非 record） ----
            new WirePayload("zxyz-common", "audit.log（各服务→audit）", OperateLog.class, JavaTimeRequirement.REQUIRED));

    /** 已知最小基数：盘点时的载荷条数。清单只能增不能减。 */
    private static final int MIN_WIRE_PAYLOADS = WIRE_PAYLOADS.size();

    // ==========================================================================
    // C1 主门禁：生产等价 mapper 往返
    // ==========================================================================

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = SampleEvent.class, names = {"TEAM_CREATED", "TEAM_UPDATED", "TEAM_MEMBER_ADDED",
            "TEAM_MEMBER_REMOVED", "USER_DELETED", "USER_PROFILE_UPDATED", "FILE_RESOURCE_CHANGED"})
    void 事件record_生产mapper往返_字段全等(SampleEvent sample) throws Exception {
        ObjectMapper mapper = productionMapper();
        Object event = sample.event();

        String json = mapper.writeValueAsString(event);
        Object back = mapper.readValue(json, sample.type());

        assertThat(back).as("%s 经生产等价 ObjectMapper 往返后必须字段全等", sample.type().getSimpleName()).isEqualTo(event);
    }

    @Test
    void 文件资源变更事件_localDateTime往返_必须实测通过() throws Exception {
        // 51 号 P0 的故障模式：运行时缺 jackson-datatype-jsr310 ⇒
        // InvalidDefinitionException: Java 8 date/time type `java.time.LocalDateTime` not supported。
        // 裸 mapper（无 JavaTimeModule）在这里必然抛异常；生产等价 mapper 必须实测通过。
        ObjectMapper mapper = productionMapper();
        FileResourceChangedEvent event = new FileResourceChangedEvent(
                "file.resource.changed", 1, 1760000000000L, 100L, 9L,
                "team/9/2026/10/x.pdf", 0,
                LocalDateTime.of(2026, 10, 11, 18, 30, 0), 9L, null);

        String json = mapper.writeValueAsString(event);
        assertThat(json).as("modifyTime 必须被序列化为 ISO-8601 文本（jsr310 生效的证据）")
                .contains("\"modifyTime\":\"2026-10-11T18:30:00\"");

        FileResourceChangedEvent back = mapper.readValue(json, FileResourceChangedEvent.class);
        assertThat(back).as("FileResourceChangedEvent 含 LocalDateTime 字段，"
                + "jsr310 缺失时这里会以 InvalidDefinitionException 响亮失败（而非生产静默丢数据）")
                .isEqualTo(event);
    }

    @Test
    void 操作日志_operateTime往返_审计链路jsr310必须实测通过() throws Exception {
        // audit.log 队列的真实载荷（AuditEventPublisher.write → OperateLogConsumer.read）。
        ObjectMapper mapper = productionMapper();
        OperateLog log = new OperateLog();
        log.setId(1L);
        log.setServiceName("file-service");
        log.setOperateUser(42L);
        log.setOperateTime(LocalDateTime.of(2026, 10, 11, 18, 30, 0));
        log.setClassName("FileController");
        log.setMethodName("upload");
        log.setMethodParams("[]");
        log.setReturnValue("{}");
        log.setCostTime(15L);

        String json = mapper.writeValueAsString(log);
        assertThat(json).contains("\"operateTime\":\"2026-10-11T18:30:00\"");

        OperateLog back = mapper.readValue(json, OperateLog.class);
        assertThat(back.getOperateTime()).as("OperateLog.operateTime 往返（51 号 P0 的确切故障点）")
                .isEqualTo(log.getOperateTime());
        assertThat(back.getServiceName()).isEqualTo("file-service");
        assertThat(back.getOperateUser()).isEqualTo(42L);
    }

    @Test
    void 裸mapper_无jsr310_必须对localDateTime载荷失败_证明本门禁有鉴别力() {
        // 自检：证明「jsr310 缺失」真的会被本门禁抓到——
        // 一个没有 JavaTimeModule 的裸 ObjectMapper（= 51 号 P0 的运行时状态）
        // 对 FileResourceChangedEvent 反序列化必然抛 InvalidDefinitionException。
        // 若这条自检失败（比如 Jackson 2 改为内置 java.time 支持），门禁的
        // REQUIRED 分级需要重新审视——先响亮失败，不要静默放行。
        ObjectMapper bare = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = "{\"eventType\":\"file.resource.changed\",\"version\":1,\"timestamp\":1760000000000,"
                + "\"fileId\":100,\"parentId\":9,\"storePath\":\"p\",\"deleted\":0,"
                + "\"modifyTime\":\"2026-10-11T18:30:00\",\"teamId\":9,\"projectId\":null}";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bare.readValue(json, FileResourceChangedEvent.class))
                .as("无 JavaTimeModule 的 mapper 对 LocalDateTime 必须失败——"
                        + "这是本门禁「REQUIRED 分级」有效性的前提")
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    // ==========================================================================
    // 清单显式化：模块归属、JavaTime 需求分级、反空扫
    // ==========================================================================

    @Test
    void 清单基数不得缩水() {
        assertThat((long) WIRE_PAYLOADS.size())
                .as("MQ wire 载荷清单基数（2026-10-11 盘点）。删除事件/改队列为内部调用都会让基数下降，"
                        + "必须先更新本清单并确认契约变化，而不是让门禁静默缩水")
                .isGreaterThanOrEqualTo(MIN_WIRE_PAYLOADS);
        assertThat((long) WIRE_PAYLOADS.stream().map(WirePayload::payloadType).distinct().count())
                .as("清单内不允许重复类型（防复制粘贴登记错）")
                .isEqualTo(WIRE_PAYLOADS.size());
    }

    @Test
    void 清单内localDateTime需求分级必须与类型实际字段一致() {
        for (WirePayload payload : WIRE_PAYLOADS) {
            boolean hasJavaTime = hasJavaTimeField(payload.payloadType());
            assertThat(payload.javaTime())
                    .as("%s 的 JavaTimeRequirement 与实际字段不符：实际含 java.time 字段=%s",
                            payload.payloadType().getName(), hasJavaTime)
                    .isEqualTo(hasJavaTime ? JavaTimeRequirement.REQUIRED : JavaTimeRequirement.NOT_REQUIRED);
        }
    }

    @Test
    void 清单内全部类型必须被生产等价mapper往返通过() throws Exception {
        // 防漏网：即使有人新增事件忘了加专属用例，只要登记进 WIRE_PAYLOADS，
        // 这里会强制它过一遍生产 mapper 往返（RecordSupport/equals 兜底断言）。
        ObjectMapper mapper = productionMapper();
        for (WirePayload payload : WIRE_PAYLOADS) {
            Object sample = SamplePayloadFactory.sampleOf(payload.payloadType());
            String json = mapper.writeValueAsString(sample);
            Object back = mapper.readValue(json, payload.payloadType());
            String description = String.format("%s（%s）登记于清单就必须能被生产 mapper 无损往返",
                    payload.payloadType().getSimpleName(), payload.queue());
            if (payload.payloadType().isRecord()) {
                assertThat(back).as(description).isEqualTo(sample);
            } else {
                // 非 record 载荷（Lombok bean，如 OperateLog：@Getter/@Setter 无 equals/hashCode）
                // 走 isEqualTo 会退化为 Object 身份比较 ⇒ 恒 false（54 批次实测：
                // diff 两侧行逐字段相同仅对象头 hash 不同仍判失败）。用逐字段递归比较承载同一契约。
                assertThat(back).as(description).usingRecursiveComparison().isEqualTo(sample);
            }
        }
    }

    // ==========================================================================
    // 夹具：每个事件的代表性样本（字段值与发布端实际形态一致）
    // ==========================================================================

    enum SampleEvent {
        TEAM_CREATED(TeamCreatedEvent.class) {
            @Override Object event() {
                return TeamCreatedEvent.of(9L, "架构组", "https://oss/avatar9.png", "负责架构",
                        42L, "u42", "张三", "zhang@x.dev");
            }
        },
        TEAM_UPDATED(TeamUpdatedEvent.class) {
            @Override Object event() {
                return TeamUpdatedEvent.of(9L, "架构组2", "https://oss/avatar9b.png", "负责架构与基建",
                        42L, "u42", "张三", "zhang@x.dev");
            }
        },
        TEAM_MEMBER_ADDED(TeamMemberAddedEvent.class) {
            @Override Object event() {
                return TeamMemberAddedEvent.of(9L, 7L, "u7", "李四", "li@x.dev",
                        "https://oss/a7.png", "MEMBER", 5L);
            }
        },
        TEAM_MEMBER_REMOVED(TeamMemberRemovedEvent.class) {
            @Override Object event() {
                return TeamMemberRemovedEvent.of(9L, 7L, 6L);
            }
        },
        USER_DELETED(UserDeletedEvent.class) {
            @Override Object event() {
                return UserDeletedEvent.of(42L, "u42");
            }
        },
        USER_PROFILE_UPDATED(UserProfileUpdatedEvent.class) {
            @Override Object event() {
                return UserProfileUpdatedEvent.of(42L, "u42", "张三", "zhang@x.dev", "https://oss/a42.png");
            }
        },
        FILE_RESOURCE_CHANGED(FileResourceChangedEvent.class) {
            @Override Object event() {
                return new FileResourceChangedEvent("file.resource.changed", 0, 0, 100L, 9L,
                        "team/9/2026/10/x.pdf", 0, LocalDateTime.of(2026, 10, 11, 18, 30, 0), 9L, null);
            }
        };

        private final Class<?> type;

        SampleEvent(Class<?> type) {
            this.type = type;
        }

        Class<?> type() {
            return type;
        }

        abstract Object event();
    }

    /** 清单兜底用例的样本工厂（与发布端字段形态一致）。 */
    private static final class SamplePayloadFactory {
        private SamplePayloadFactory() {
        }

        static Object sampleOf(Class<?> type) {
            if (type == TeamCreatedEvent.class) {
                return TeamCreatedEvent.of(9L, "架构组", "a", "d", 42L, "u42", "张三", "z@x.dev");
            }
            if (type == TeamUpdatedEvent.class) {
                return TeamUpdatedEvent.of(9L, "架构组2", "a2", "d2", 42L, "u42", "张三", "z@x.dev");
            }
            if (type == TeamMemberAddedEvent.class) {
                return TeamMemberAddedEvent.of(9L, 7L, "u7", "李四", "l@x.dev", "a7", "MEMBER", 5L);
            }
            if (type == TeamMemberRemovedEvent.class) {
                return TeamMemberRemovedEvent.of(9L, 7L, 6L);
            }
            if (type == UserDeletedEvent.class) {
                return UserDeletedEvent.of(42L, "u42");
            }
            if (type == UserProfileUpdatedEvent.class) {
                return UserProfileUpdatedEvent.of(42L, "u42", "张三", "z@x.dev", "a42");
            }
            if (type == FileResourceChangedEvent.class) {
                return new FileResourceChangedEvent("file.resource.changed", 0, 0, 100L, 9L,
                        "team/9/p", 0, LocalDateTime.of(2026, 10, 11, 18, 30, 0), 9L, null);
            }
            if (type == OperateLog.class) {
                OperateLog log = new OperateLog();
                log.setServiceName("file-service");
                log.setOperateUser(42L);
                log.setOperateTime(LocalDateTime.of(2026, 10, 11, 18, 30, 0));
                log.setClassName("FileController");
                log.setMethodName("upload");
                log.setMethodParams("[]");
                log.setReturnValue("{}");
                log.setCostTime(15L);
                return log;
            }
            throw new AssertionError("WIRE_PAYLOADS 登记了未知类型 " + type.getName()
                    + " —— 请在 SamplePayloadFactory 补样本，保持清单与夹具同步");
        }
    }

    /** 反射判断类型（含父类/字段）是否携带 java.time 字段（REQUIRED 分级的判定依据）。 */
    private static boolean hasJavaTimeField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType().getName().startsWith("java.time.")) {
                    return true;
                }
            }
        }
        return false;
    }
}
