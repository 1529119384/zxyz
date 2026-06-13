package uno.acloud.share.satoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

import java.util.List;

@Component
public class StpInterfaceImpl extends RemoteStpInterfaceImpl {

    public StpInterfaceImpl(RestClient restClient,
                            TeamServiceProperties teamServiceProperties,
                            ObjectMapper objectMapper) {
        super(restClient, teamServiceProperties.normalizedBaseUrl(),
              objectMapper, teamServiceProperties.getInternalServiceToken());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
