package uno.acloud.share.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.interceptor.SaInterceptor;
import uno.acloud.common.config.InternalServiceAuthInterceptor;
import cn.dev33.satoken.SaManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    public SaTokenConfigure(InternalServiceAuthInterceptor internalServiceAuthInterceptor) {
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
    }

    @PostConstruct
    public void initSaTokenContext() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 内部服务调用鉴权
        registry.addInterceptor(internalServiceAuthInterceptor)
                .addPathPatterns("/api/internal/**");

        // Sa-Token 用户鉴权
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/public/shares/**")
                .excludePathPatterns("/api/internal/**")
                .excludePathPatterns("/actuator/**");
    }
}
