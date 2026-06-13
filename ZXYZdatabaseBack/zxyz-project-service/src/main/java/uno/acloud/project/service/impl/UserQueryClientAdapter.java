package uno.acloud.project.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.UserQueryClient;
import uno.acloud.project.config.ServiceProperties;

@Component
public class UserQueryClientAdapter extends UserQueryClient {

    public UserQueryClientAdapter(RestClient restClient,
                                  ServiceProperties serviceProperties,
                                  ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getUserService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }
}
