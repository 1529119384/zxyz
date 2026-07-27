package uno.acloud.user.satoken;

import org.springframework.stereotype.Service;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.config.ServiceProperties;
import uno.acloud.user.service.AuthSessionPort;

import java.util.List;

@Service
public class SaTokenAuthSessionService implements AuthSessionPort {
    private static final String EXTRA_USER_ID = "userId";
    private static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_PERMISSION = "permission";

    private final AuthServicePort authServicePort;
    private final ServiceProperties serviceProperties;

    public SaTokenAuthSessionService(AuthServicePort authServicePort, ServiceProperties serviceProperties) {
        this.authServicePort = authServicePort;
        this.serviceProperties = serviceProperties;
    }

    @Override
    public String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions) {
        return createLoginSession(userId, username, roles, permissions, false);
    }

    @Override
    public String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions, boolean rememberMe) {
        long timeoutSeconds = rememberMe
                ? serviceProperties.getAuth().getLongLivedTimeoutSeconds()
                : serviceProperties.getAuth().getTokenTimeoutSeconds();
        authServicePort.login(userId, timeoutSeconds);
        authServicePort.setSessionAttribute(userId, EXTRA_USER_ID, userId);
        authServicePort.setSessionAttribute(userId, EXTRA_USERNAME, username);
        authServicePort.setSessionAttribute(userId, EXTRA_ROLE, roles);
        authServicePort.setSessionAttribute(userId, EXTRA_PERMISSION, permissions);
        return authServicePort.getTokenValue();
    }

    @Override
    public void logout(Long userId) {
        authServicePort.logout(userId);
    }
}
