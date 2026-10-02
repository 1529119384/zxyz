package uno.acloud.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * IM WebSocket 路由地址的启动期门禁（B-10）。
 *
 * <p><b>它防的是什么</b>：WS 路由的 uri 是
 * {@code ${IM_WEBSOCKET_URI:ws://localhost:19090}}。那个 {@code localhost} 默认值
 * 对本地开发是必要的（{@code scripts/run-local.sh gateway} 不起 compose），
 * 但它有一个真实的运维陷阱 —— <b>compose 之外的 prod 部署若漏注入该环境变量，
 * 网关会把全部 WS 流量静默指向自己的 localhost:19090</b>，表现为「握手连不上」，
 * 而配置层面看不出任何异常（yml 里那个值看起来完全合法）。
 * 报告 B-10 建议把它改成必填（{@code ${IM_WEBSOCKET_URI:?...}}），
 * 那会连本地开发一起打断；故这里改为<b>分档 fail-closed</b>：只在 prod 档要求显式注入。</p>
 *
 * <p><b>为什么用 ApplicationReadyEvent 而不是 @PostConstruct</b>：让网关先完成
 * 上下文装配与路由注册，再对配置做断言 —— 这样失败信息里带的是「运行期真实生效的档位」，
 * 且不会在 Bean 初始化中途抛出难以归因的异常。</p>
 *
 * <p>非 prod 档（dev/test，以及网关刻意不设 default profile 的场景）只记日志：
 * 本地开发者不该为了跑起来去编造一个容器主机名。</p>
 */
@Component
public class ImWebSocketRouteValidator {

    private static final Logger log = LoggerFactory.getLogger(ImWebSocketRouteValidator.class);

    /** 网关刻意不设 spring.profiles.default（见 application.yml 注释），故此处按「包含 prod」判断。 */
    private static final String PROD_PROFILE = "prod";

    private final GatewayProperties gatewayProperties;
    private final Set<String> activeProfiles;

    public ImWebSocketRouteValidator(GatewayProperties gatewayProperties,
                                     @Value("${spring.profiles.active:}") String activeProfiles) {
        this.gatewayProperties = gatewayProperties;
        this.activeProfiles = Set.of(activeProfiles.split(","));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyWebSocketRouteConfigured() {
        if (!activeProfiles.contains(PROD_PROFILE)) {
            if (gatewayProperties.getGateway().getWebsocketUri().isBlank()) {
                log.info("IM_WEBSOCKET_URI 未显式注入（非 prod 档，允许），"
                        + "WS 路由将回退到 yml 中的 localhost 默认值");
            }
            return;
        }
        if (gatewayProperties.getGateway().getWebsocketUri().isBlank()) {
            // fail-closed：宁可不启动，也不要让全站 WS 静默指向 localhost。
            throw new IllegalStateException(
                    "prod 档必须显式注入 IM_WEBSOCKET_URI（容器内应为 ws://im-service:19090）。"
                            + "当前未注入 —— WS 路由会静默回退到 ws://localhost:19090，"
                            + "表现为握手全部失败但配置看不出异常。"
                            + "请在 docker-compose environment 或部署环境变量中显式设置该值。");
        }
        log.info("IM WebSocket 路由地址已显式配置: {}", gatewayProperties.getGateway().getWebsocketUri());
    }
}
