package uno.acloud.user.service;

import java.util.List;

public interface AuthSessionPort {

    String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions);

    void logout(Long userId);
}
