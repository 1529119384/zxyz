package uno.acloud.file.satoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

import java.util.List;

@Component
public class StpInterfaceImpl extends RemoteStpInterfaceImpl {

    public StpInterfaceImpl(RestClient restClient,
                            ServiceProperties serviceProperties,
                            ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(),
              objectMapper, serviceProperties.getInternalServiceToken());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
