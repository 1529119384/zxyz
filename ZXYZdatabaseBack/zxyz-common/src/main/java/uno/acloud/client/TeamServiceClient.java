package uno.acloud.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

/**
 * 团队服务查询客户端（基础版）。
 * <p>供 user-service 等需要调用团队服务内部接口的模块复用。
 * 子类应添加 {@code @Component}，本类不注册为 Spring Bean。</p>
 *
 * <p>构造参数由子类从各自的 {@code ServiceProperties} 注入后传入，
 * 基类不使用 {@code @Value}，避免与子类配置来源冲突。</p>
 */
public class TeamServiceClient extends AbstractServiceClient {

    public TeamServiceClient(RestClient restClient,
                             String teamServiceBaseUrl,
                             String internalServiceToken,
                             ObjectMapper objectMapper) {
        super(restClient, teamServiceBaseUrl, internalServiceToken, objectMapper);
    }

    @Override
    protected String serviceName() {
        return "团队服务";
    }

}
