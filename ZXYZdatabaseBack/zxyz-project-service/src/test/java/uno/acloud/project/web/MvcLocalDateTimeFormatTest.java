package uno.acloud.project.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import uno.acloud.common.Result;
import uno.acloud.project.config.JacksonConfig;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2-4（ISSUE/54 §2.7）：project 的 LocalDateTime 全局格式 customizer
 * 在 Boot 4 Jackson2/Jackson3 双轨下是否作用于 MVC 响应 —— <b>测试即实证</b>。
 *
 * <h2>背景</h2>
 * <p>{@link JacksonConfig} 用 {@code Jackson2ObjectMapperBuilderCustomizer}（Jackson 2 轨）
 * 注册 {@code yyyy-MM-dd HH:mm:ss} 的 {@code LocalDateTimeSerializer}。Boot 4 起 MVC 的
 * JSON 消息转换器默认走 <b>Jackson 3 轨</b>（{@code tools.jackson} 的
 * {@code JacksonJsonHttpMessageConverter}，装配条件 {@code @ConditionalOnBean(JsonMapper)}）；
 * Jackson 2 轨的 {@code MappingJackson2HttpMessageConverter} 挂在
 * {@code @ConditionalOnMissingBean(JacksonJsonHttpMessageConverter)} 上
 * （spring-boot-http-converter 4.0.8 反编译实证），只有显式设
 * {@code spring.http.converters.preferred-json-mapper=jackson2} 才优先。
 * ⇒ Jackson2 轨全局 customizer 可能根本不在 MVC 响应的序列化路径上。</p>
 *
 * <h2>实证方法（本类就是证据）</h2>
 * <ol>
 *   <li><b>MVC 链路（核心断言）</b>：{@code @WebMvcTest} 切片装入与生产一致的
 *       Jackson3 {@code JacksonAutoConfiguration} + Jackson2
 *       {@code Jackson2AutoConfiguration} + {@code HttpMessageConvertersAutoConfiguration}
 *       + {@code WebMvcAutoConfiguration}（AutoConfigureWebMvc.imports 与 ImportsContextCustomizer
 *       键实测），再 {@code @Import(JacksonConfig)} 挂上被测的全局 customizer ——
 *       走真实 DispatcherServlet 与真实消息转换器选择逻辑，断言响应 JSON 的实际格式。</li>
 *   <li><b>Jackson2 直探</b>：把 customizer 手工应用到 {@code Jackson2ObjectMapperBuilder}
 *       —— 证明 customizer 机械上有效；若 MVC 断言失败而本断言通过，
 *       问题精确锁定在「转换器轨选择」而非 customizer 本身。</li>
 *   <li><b>Jackson3 直探</b>：默认 {@code JsonMapper} 序列化 {@code LocalDateTime} 的格式
 *       （Boot 4 默认轨的缺省行为基线）。</li>
 * </ol>
 *
 * <h2>为什么用 {@link MvcLocalDateTimeSliceApp} 而不是真实应用类</h2>
 * <p>真实 {@code ZxyzProjectServiceApplication} 声明了
 * {@code @ComponentScan(basePackages={"uno.acloud.project","uno.acloud.common"})}，
 * 会把依赖 RabbitTemplate 的 {@code LogAspect}（@Aspect @Component）扫进切片
 * （RabbitAutoConfiguration 不在 @WebMvcTest 切片内 ⇒ 上下文必炸）。
 * {@code @WebMvcTest} 自带的 {@code WebMvcTypeExcludeFilter} 只对「默认扫描」生效，
 * 管不住应用类显式声明的 basePackages。切片按 Boot 惯用法就近发现测试包内的
 * {@code @SpringBootConfiguration} —— 本测试类位于 {@code uno.acloud.project.web}，
 * 故用下方扫描面受控的 {@link MvcLocalDateTimeSliceApp} 作为主配置，
 * 组件扫描只覆盖本测试包（探针控制器），被测 customizer 经 {@code @Import} 显式挂入。</p>
 *
 * <p><b>离线可跑</b>：{@code @ActiveProfiles("test")} 走 application-test.yml
 * （spring.config.import 只引 classpath:application-common.yml，不碰 Nacos/DB），
 * 切片不含数据源/RabbitMQ/Sa-Token 拦截器。Boot 4 中 {@code @WebMvcTest} 已迁至
 * 独立构件 {@code spring-boot-webmvc-test}（pom 已加 test 依赖）。</p>
 */
@org.junit.jupiter.api.Tag("mvc-slice")
@org.springframework.test.context.ActiveProfiles("test")
@WebMvcTest(ProbeController.class)
@Import(JacksonConfig.class)
@DisplayName("P2-4: LocalDateTime 全局格式 customizer 在 Boot 4 MVC 响应上的实证")
class MvcLocalDateTimeFormatTest {

    /** 断言用的基准时刻（避免真实时间造成的脆弱断言）。ProbeController 返回该值。 */
    static final LocalDateTime SAMPLE = LocalDateTime.of(2026, 10, 11, 8, 30, 0);

    /** customizer 期望的格式（JacksonConfig.DATE_TIME_PATTERN）。 */
    private static final String CUSTOMIZER_PATTERN_VALUE = "2026-10-11 08:30:00";

    /** Jackson 3 默认（ISO-8601）格式 —— customizer 不生效时 MVC 会返回这个。 */
    private static final String ISO_DEFAULT_VALUE = "2026-10-11T08:30:00";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("MVC 响应实证：LocalDateTime 走全局 customizer 的 yyyy-MM-dd HH:mm:ss（而非 ISO 默认）")
    void mvcResponseShouldUseGlobalCustomizerFormat() throws Exception {
        // 核心断言：customizer 的格式。若 Boot 4 双轨下 customizer 不作用于 MVC 响应，
        // 本断言会失败且实际值是 ISO_DEFAULT_VALUE —— 失败信息即实证结论。
        mockMvc.perform(get("/probe/local-date-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.value").value(CUSTOMIZER_PATTERN_VALUE));
    }

    @Test
    @DisplayName("Jackson2 直探：customizer 应用到 Jackson2ObjectMapperBuilder 后机械生效")
    void jackson2MapperWithCustomizerShouldFormatLocalDateTime() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jsonCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        String json = mapper.writeValueAsString(Map.of("value", SAMPLE));

        // 若此断言失败，说明 customizer 本身坏了（而非 MVC 轨选择问题）
        assertTrue(json.contains("\"" + CUSTOMIZER_PATTERN_VALUE + "\""),
                "customizer 应使 Jackson2 mapper 输出 " + CUSTOMIZER_PATTERN_VALUE + "，实际=" + json);
    }

    @Test
    @DisplayName("Jackson3 直探：默认 JsonMapper 序列化 LocalDateTime 为 ISO-8601（Boot 4 默认轨基线）")
    void jackson3DefaultMapperShouldUseIsoFormat() {
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(Map.of("value", SAMPLE));

        assertTrue(json.contains("\"" + ISO_DEFAULT_VALUE + "\""),
                "Jackson3 默认 JsonMapper 应输出 ISO 格式 " + ISO_DEFAULT_VALUE + "，实际=" + json);
    }

    @Test
    @DisplayName("实证记录：MVC 响应的实际格式值（读取原始字符串，供报告引用实测值）")
    void captureActualMvcResponseFormat() throws Exception {
        String body = mockMvc.perform(get("/probe/local-date-time"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 实测值必须二选一：customizer 格式（P2-4 不成立，customizer 正常生效）或 ISO 默认（P2-4 成立，需补 Jackson3 轨）
        boolean customizerFormat = body.contains(CUSTOMIZER_PATTERN_VALUE);
        boolean isoDefault = body.contains(ISO_DEFAULT_VALUE);
        assertTrue(customizerFormat || isoDefault,
                "MVC 响应既非 customizer 格式也非 ISO 默认，需人工检查: " + body);

        System.out.println("[P2-4 实证] MVC 响应体 = " + body);
        System.out.println("[P2-4 实证] LocalDateTime 实际格式 = "
                + (customizerFormat ? CUSTOMIZER_PATTERN_VALUE + "（全局 customizer 生效）"
                : ISO_DEFAULT_VALUE + "（ISO 默认，customizer 未作用于 MVC）"));
    }
}

/**
 * 切片专用主配置（Boot 惯用法：测试包内的 {@code @SpringBootConfiguration} 被
 * {@code WebMvcTestContextBootstrapper} 就近发现，优先于真实应用类）。
 * 组件扫描只覆盖本测试包（唯一成员 = 探针控制器），从而把真实应用类
 * {@code @ComponentScan("uno.acloud.common")} 带来的 LogAspect/AuditEventPublisher
 * 等重依赖组件挡在切片外；Jackson 双轨自动配置仍由切片的
 * AutoConfigureWebMvc.imports 装入，与生产同构。
 */
@SpringBootApplication
class MvcLocalDateTimeSliceApp {
}
