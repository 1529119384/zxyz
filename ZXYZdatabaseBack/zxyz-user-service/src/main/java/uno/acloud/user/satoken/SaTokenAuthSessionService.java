package uno.acloud.user.satoken;

import org.springframework.stereotype.Service;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.service.AuthSessionPort;

import java.util.List;

@Service
public class SaTokenAuthSessionService implements AuthSessionPort {
    private static final String EXTRA_USER_ID = "userId";
    private static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_PERMISSION = "permission";

    private final AuthServicePort authServicePort;

    public SaTokenAuthSessionService(AuthServicePort authServicePort) {
        this.authServicePort = authServicePort;
    }

    @Override
    public String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions) {
        authServicePort.login(userId);
        authServicePort.setSessionAttribute(userId, EXTRA_USER_ID, userId);
        authServicePort.setSessionAttribute(userId, EXTRA_USERNAME, username);
        authServicePort.setSessionAttribute(userId, EXTRA_ROLE, roles);
        authServicePort.setSessionAttribute(userId, EXTRA_PERMISSION, permissions);
        return authServicePort.getTokenValue();
    }
}
