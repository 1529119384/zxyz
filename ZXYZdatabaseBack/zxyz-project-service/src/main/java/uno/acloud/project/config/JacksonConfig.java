package uno.acloud.project.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * LocalDateTime 全局序列化格式：{@code yyyy-MM-dd HH:mm:ss}。
 *
 * <h2>Boot 4 双轨实证（P2-4，ISSUE/54 §2.7）</h2>
 * <p>Boot 4 的 MVC JSON 消息转换器<b>默认走 Jackson 3 轨</b>
 * （{@code JacksonJsonHttpMessageConverter}，装配条件 {@code @ConditionalOnBean(JsonMapper)}）；
 * Jackson 2 轨的 {@code MappingJackson2HttpMessageConverter} 挂在
 * {@code @ConditionalOnMissingBean(JacksonJsonHttpMessageConverter)} 上
 * （spring-boot-http-converter 4.0.8 反编译实证），只有显式设
 * {@code spring.http.converters.preferred-json-mapper=jackson2} 才优先。
 * 本仓从未设该属性 ⇒ 生产 MVC 响应由 Jackson 3 轨序列化。</p>
 *
 * <p><b>历史缺陷</b>：本类原先只注册 Jackson 2 轨的
 * {@code Jackson2ObjectMapperBuilderCustomizer}，它只影响 Jackson2 ObjectMapper
 * （MQ 载荷 / RestClient 反序列化路径），<b>不影响 MVC 响应</b> ——
 * 前端拿到的 LocalDateTime 一直是 ISO 默认（{@code 2026-10-11T08:30:00}）。
 * 由 {@code MvcLocalDateTimeFormatTest}（@WebMvcTest 切片）实测坐实并回归钉住。</p>
 *
 * <p><b>修法</b>：补 Jackson 3 轨的 {@link JsonMapperBuilderCustomizer}
 * （与 Jackson2 轨 customizer 并存，各管各轨 —— 双轨显式化，杜绝「哪条轨生效」的猜谜）。
 * 不走 file-service 的字段级 {@code @JsonFormat} 先例：那要求每个含 LocalDateTime 的 VO
 * 逐字段补注解（project 有 12+ 处），漏一处就漂移；全局 customizer 是该语义的正确载体。</p>
 *
 * <p><b>Jackson 3 的 jsr310 已并入 databind</b>（{@code tools.jackson.databind.ext.javatime}），
 * 无需单独注册模块依赖；序列化器经 {@code SimpleModule} 挂入，
 * 由 Boot 的 JacksonAutoConfiguration 收集 {@code JsonMapperBuilderCustomizer} 应用到
 * 自动装配的 {@code JsonMapper}。
 * 两轨的序列化器<b>类同名不同包</b>（com.fasterxml 与 tools.jackson），一律用全限定名
 * {@code new}，避免 single-type-import 冲突 —— 勿改成 import 简名。</p>
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * Jackson 2 轨：影响 Jackson2 ObjectMapper（MQ 载荷发布、RestClient 收发投影等
     * {@code com.fasterxml} 路径）。保留不动 —— MQ wire 格式不能因 Boot 4 迁移漂移。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> builder.serializerByType(LocalDateTime.class,
                new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(formatter));
    }

    /**
     * Jackson 3 轨（P2-4 补）：影响 MVC 响应（{@code JacksonJsonHttpMessageConverter}）
     * 与其它消费自动装配 {@code JsonMapper} 的路径。经 {@code SimpleModule} 按类型挂
     * {@code LocalDateTimeSerializer(formatter)}，与 Jackson2 轨同格式。
     */
    @Bean
    public JsonMapperBuilderCustomizer localDateTimeJsonMapperCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> {
            tools.jackson.databind.module.SimpleModule module =
                    new tools.jackson.databind.module.SimpleModule("project-local-date-time");
            module.addSerializer(LocalDateTime.class,
                    new tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer(formatter));
            builder.addModule(module);
        };
    }
}
