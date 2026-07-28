package uno.acloud.share.satoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

@Component
public class StpInterfaceImpl extends RemoteStpInterfaceImpl {

    public StpInterfaceImpl(RestClient restClient,
                            TeamServiceProperties teamServiceProperties,
                            ObjectMapper objectMapper) {
        super(restClient, teamServiceProperties.normalizedBaseUrl(),
              objectMapper, teamServiceProperties.getInternalServiceToken());
    }
}
