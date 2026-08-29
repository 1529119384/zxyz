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
        // Sa-Token 拦截全路径（注解鉴权为主，非强制登录——new SaInterceptor() 默认只做 @SaCheck* 注解鉴权）。
        // 排除矩阵只豁免【纯服务间内部调用】端点（仅内部 token 校验，无用户会话）：
        //   send / send-batch / send-template / send-template-batch / scheduled-batches / verify-codes/**
        // 其余 /api/email/internal/**（server-configs / records / runtime-status）为 admin 桥接管理端点，
        // 必须参与 SaInterceptor 以让 @SaCheckRole(SYSTEM_ADMIN) 生效，堵住 gateway 注入内部 token 的越权路径（P0-1）。
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/api/email/internal/send")
                .excludePathPatterns("/api/email/internal/send-batch")
                .excludePathPatterns("/api/email/internal/send-template")
                .excludePathPatterns("/api/email/internal/send-template-batch")
                .excludePathPatterns("/api/email/internal/scheduled-batches")
                .excludePathPatterns("/api/email/internal/verify-codes/**")
                .excludePathPatterns("/actuator/**");
    }
}
