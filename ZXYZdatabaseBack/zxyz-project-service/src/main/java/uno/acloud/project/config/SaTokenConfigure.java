package uno.acloud.project.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.interceptor.SaInterceptor;
import lombok.RequiredArgsConstructor;
import uno.acloud.common.config.InternalServiceAuthInterceptor;
import cn.dev33.satoken.SaManager;
import jakarta.annotation.PostConstruct;
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
        // 内部服务调用鉴权（优先级高于 Sa-Token 拦截器）
        registry.addInterceptor(internalServiceAuthInterceptor)
                .addPathPatterns("/api/internal/**");

        // 注册 Sa-Token 拦截器，打开注解式鉴权功能
        var saInterceptor = registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/public/shares/**")
                .excludePathPatterns("/api/internal/**")
                .excludePathPatterns("/actuator/**");

    }
}
