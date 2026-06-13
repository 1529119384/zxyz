package uno.acloud.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.satoken.SaTokenAuthServicePort;

/**
 * 自动注册 {@link AuthServicePort} Bean。
 *
 * <p>当 classpath 中存在 Sa-Token 时（即所有业务服务），
 * 自动创建 {@link SaTokenAuthServicePort} 实例。</p>
 */
@Configuration
@ConditionalOnClass(name = "cn.dev33.satoken.stp.StpUtil")
public class AuthServicePortAutoConfig {

    @Bean
    public AuthServicePort authServicePort() {
        return new SaTokenAuthServicePort();
    }
}
