package uno.acloud.user.service;

import java.util.List;

public interface AuthSessionPort {

    String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions);

    /**
     * 创建登录会话（带记住我选项）。
     *
     * @param userId      用户 ID
     * @param username    用户名
     * @param roles       系统角色列表
     * @param permissions 系统权限列表
     * @param rememberMe  是否记住我（true = 7天，false = 12小时）
     * @return 登录 token
     */
    String createLoginSession(Long userId, String username, List<String> roles, List<String> permissions, boolean rememberMe);

    void logout(Long userId);
}
