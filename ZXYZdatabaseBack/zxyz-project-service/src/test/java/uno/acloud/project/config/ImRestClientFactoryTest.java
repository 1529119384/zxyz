package uno.acloud.project.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImRestClientFactoryTest {

    @Test
    void appImBaseUrlShouldDriveTeamSyncRequest() throws Exception {
        // Start a real HTTP server on a random port to capture the request
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();
        httpServer.createContext("/api/im/internal/team-sync/members/remove", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedToken.set(exchange.getRequestHeaders().getFirst(InternalServiceHeaders.TOKEN_HEADER));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        try {
            AppImProperties properties = bind(Map.of(
                    "app.im.base-url", "http://localhost:" + port + "/"
            ));
            RestClient restClient = new ImRestClientFactory().imRestClient(
                    RestClient.builder(), properties);

            restClient.post()
                    .uri("/api/im/internal/team-sync/members/remove")
                    .header(InternalServiceHeaders.TOKEN_HEADER, "internal-token")
                    .retrieve()
                    .toBodilessEntity();

            assertEquals("POST", capturedMethod.get());
            assertEquals("/api/im/internal/team-sync/members/remove", capturedPath.get());
            assertEquals("internal-token", capturedToken.get());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void legacyImServiceBaseUrlShouldNotBindToAppImProperties() {
        AppImProperties properties = bind(Map.of(
                "im.service.base-url", "http://legacy-im.example.test:18081"
        ));

        assertThrows(IllegalStateException.class, properties::normalizedBaseUrl);
    }

    @Test
    void appImBaseUrlShouldTrimTrailingSlash() {
        AppImProperties properties = bind(Map.of(
                "app.im.base-url", "http://im.example.test:18081///"
        ));

        assertEquals("http://im.example.test:18081", properties.normalizedBaseUrl());
    }

    private AppImProperties bind(Map<String, String> values) {
        AppImProperties properties = new AppImProperties();
        new Binder(new MapConfigurationPropertySource(values))
                .bind("app.im", Bindable.ofInstance(properties));
        return properties;
    }
}
