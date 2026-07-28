package uno.acloud.email.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @PostConstruct
    public void initSaTokenContext() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/email/internal/**")
                .excludePathPatterns("/actuator/**");
    }
}
