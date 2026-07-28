package uno.acloud.admin.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import uno.acloud.common.config.InternalServiceAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    @PostConstruct
    public void initSaTokenContext() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalServiceAuthInterceptor)
                .addPathPatterns("/api/internal/**");

        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/internal/**")
                .excludePathPatterns("/actuator/**");
    }
}
