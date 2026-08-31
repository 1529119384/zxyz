package uno.acloud.file.satoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.satoken.PermissionCache;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

@Component
public class StpInterfaceImpl extends RemoteStpInterfaceImpl {

    public StpInterfaceImpl(RestClient restClient,
                            ServiceProperties serviceProperties,
                            ObjectMapper objectMapper,
                            PermissionCache permissionCache,
                            @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String sourceService,
                            @org.springframework.beans.factory.annotation.Value("${app.internal-service-key:}") String selfServiceKey) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(),
              objectMapper, serviceProperties.getInternalServiceToken(), sourceService, selfServiceKey, permissionCache);
    }
}
