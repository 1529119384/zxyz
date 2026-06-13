package uno.acloud.project.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImRestClientFactoryTest {

    @Test
    void appImBaseUrlShouldDriveTeamSyncRequest() {
        AppImProperties properties = bind(Map.of(
                "app.im.base-url", "http://im.example.test:18081/"
        ));
        CapturingRequestFactory requestFactory = new CapturingRequestFactory();
        RestClient restClient = new ImRestClientFactory().imRestClient(
                RestClient.builder().requestFactory(requestFactory),
                properties
        );

        restClient.post()
                .uri("/api/im/internal/team-sync/members/remove")
                .header(InternalServiceHeaders.TOKEN_HEADER, "internal-token")
                .retrieve()
                .toBodilessEntity();

        assertEquals(URI.create("http://im.example.test:18081/api/im/internal/team-sync/members/remove"),
                requestFactory.lastUri);
        assertEquals(HttpMethod.POST, requestFactory.lastMethod);
        assertEquals("internal-token", requestFactory.lastRequest.getHeaders().getFirst(InternalServiceHeaders.TOKEN_HEADER));
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

    private static class CapturingRequestFactory implements ClientHttpRequestFactory {

        private URI lastUri;
        private HttpMethod lastMethod;
        private MockClientHttpRequest lastRequest;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            lastUri = uri;
            lastMethod = httpMethod;
            lastRequest = new MockClientHttpRequest(httpMethod, uri) {
                @Override
                protected MockClientHttpResponse executeInternal() throws IOException {
                    return new MockClientHttpResponse("{}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                }
            };
            return lastRequest;
        }
    }
}
