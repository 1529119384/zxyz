package uno.acloud.im.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import uno.acloud.im.infrastructure.netty.ImNettyServer;

@Component
@ConditionalOnBean(ImNettyServer.class)
public class NettyHealthIndicator implements HealthIndicator {

    private final ImNettyServer nettyServer;
    private final ImNettyProperties properties;

    public NettyHealthIndicator(ImNettyServer nettyServer, ImNettyProperties properties) {
        this.nettyServer = nettyServer;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.up()
                    .withDetail("netty", "disabled")
                    .build();
        }

        boolean active = nettyServer.isRunning();
        if (active) {
            return Health.up()
                    .withDetail("netty", "running")
                    .withDetail("port", properties.getPort())
                    .withDetail("websocketPath", properties.getWebsocketPath())
                    .build();
        }

        return Health.down()
                .withDetail("netty", "not running")
                .build();
    }
}
