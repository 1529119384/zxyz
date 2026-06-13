package uno.acloud.im.config;

import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.SaManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final ImHttpAuthInterceptor imHttpAuthInterceptor;

    public SaTokenConfigure(ImHttpAuthInterceptor imHttpAuthInterceptor) {
        this.imHttpAuthInterceptor = imHttpAuthInterceptor;
    }

    @PostConstruct
    public void initSaTokenContext() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(imHttpAuthInterceptor)
                .addPathPatterns("/api/im/**", "/api/team-collaboration/**", "/api/permissions/teams/**");
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/api/im/internal/**");
    }
}
