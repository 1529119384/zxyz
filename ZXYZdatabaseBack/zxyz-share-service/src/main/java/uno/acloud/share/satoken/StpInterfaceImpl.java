package uno.acloud.share.satoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.satoken.PermissionCache;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

@Component
public class StpInterfaceImpl extends RemoteStpInterfaceImpl {

    public StpInterfaceImpl(RestClient restClient,
                            TeamServiceProperties teamServiceProperties,
                            ObjectMapper objectMapper,
                            PermissionCache permissionCache,
                            @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String sourceService,
                            @org.springframework.beans.factory.annotation.Value("${app.internal-service-key:}") String selfServiceKey) {
        super(restClient, teamServiceProperties.normalizedBaseUrl(),
              objectMapper, teamServiceProperties.getInternalServiceToken(), sourceService, selfServiceKey, permissionCache);
    }
}
