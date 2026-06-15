package uno.acloud.common.config;

import uno.acloud.satoken.RemoteStpInterfaceImpl;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 为不需要自定义 StpInterface 的服务提供默认 Bean。
 * <p>
 * file-service 和 share-service 因重写 getRoleList() 返回空列表，需保留自定义实现。
 * team-service 直接调用本地权限服务，不使用远程基类。
 *
 * <p>使用 {@code @Value} 读取 {@code app.*} 属性是故意设计：
 * 各服务通过 application.yml 将共享的 {@code services.*} 映射到本地 {@code app.*} 命名空间，
 * 使此自动配置类无需知道具体服务的属性来源。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.team-service.base-url")
public class RemoteStpInterfaceAutoConfig {

    @Bean
    @ConditionalOnBean(RestClient.class)
    @ConditionalOnMissingBean(StpInterface.class)
    public StpInterface stpInterface(RestClient restClient,
                                     @Value("${app.team-service.base-url}") String teamServiceBaseUrl,
                                     ObjectMapper objectMapper,
                                     @Value("${app.internal-service-token:}") String internalServiceToken) {
        return new RemoteStpInterfaceImpl(restClient, teamServiceBaseUrl, objectMapper, internalServiceToken);
    }
}
