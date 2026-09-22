package uno.acloud.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.satoken.SaTokenAuthServicePort;

/**
 * 自动注册 {@link AuthServicePort} Bean。
 *
 * <p>当 classpath 中存在 Sa-Token 时（即所有业务服务），
 * 自动创建 {@link SaTokenAuthServicePort} 实例。</p>
 *
 * <p><b>C-10（2026-09-21）：本类原位于 {@code uno.acloud.common.config} —— 一个被各服务{@code @ComponentScan} 覆盖的包（basePackages 含 {@code uno.acloud.common}），同时又登记在{@code AutoConfiguration.imports} 中 ⇒ 同一份配置被注册两次、条件在两个不同阶段各求值一次。已迁入 {@code uno.acloud.autoconfig}（不被任何 {@code @ComponentScan} 覆盖），并补上 Spring Boot 3 要求的 {@code @AutoConfiguration}。实测证据见同包 {@code AuditBufferRetryAutoConfiguration} 的类注释。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "cn.dev33.satoken.stp.StpUtil")
public class AuthServicePortAutoConfig {

    @Bean
    public AuthServicePort authServicePort() {
        return new SaTokenAuthServicePort();
    }
}
