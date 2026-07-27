package uno.acloud.satoken;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;

/**
 * {@link AuthServicePort} 的 Sa-Token 实现。
 *
 * <p>所有方法委托给 {@link StpUtil} 静态调用，保持行为一致。
 * 注册为 Spring Bean 后，业务层通过注入 AuthServicePort 使用，
 * 不再直接依赖 StpUtil。</p>
 *
 * @see AuthServicePort
 */
public class SaTokenAuthServicePort implements AuthServicePort {

    @Override
    public long getCurrentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    @Override
    public void checkLogin() {
        StpUtil.checkLogin();
    }

    @Override
    public boolean isLogin() {
        return StpUtil.isLogin();
    }

    @Override
    public void checkPermission(String permissionCode) {
        StpUtil.checkPermission(permissionCode);
    }

    @Override
    public boolean hasPermission(String permissionCode) {
        return StpUtil.hasPermission(permissionCode);
    }

    @Override
    public void checkRole(String roleCode) {
        StpUtil.checkRole(roleCode);
    }

    @Override
    public boolean hasRole(String roleCode) {
        return StpUtil.hasRole(roleCode);
    }

    @Override
    public void login(long userId) {
        StpUtil.login(userId);
    }

    @Override
    public void login(long userId, long timeout) {
        StpUtil.login(userId, new SaLoginModel().setTimeout(timeout));
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public void logout(long userId) {
        StpUtil.logout(userId);
    }

    // ---- Session operations ----

    @Override
    public Object getSessionAttribute(long userId, String key) {
        return StpUtil.getSessionByLoginId(userId).get(key);
    }

    @Override
    public void setSessionAttribute(long userId, String key, Object value) {
        StpUtil.getSessionByLoginId(userId).set(key, value);
    }

    @Override
    public void deleteSessionAttribute(long userId, String key) {
        StpUtil.getSessionByLoginId(userId).delete(key);
    }

    @Override
    public Object getCurrentSessionAttribute(String key) {
        return StpUtil.getSession().get(key);
    }

    // ---- Token operations ----

    @Override
    public String getTokenValue() {
        return StpUtil.getTokenValue();
    }

    @Override
    public Long getLoginIdByToken(String token) {
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            return null;
        }
        return Long.valueOf(loginId.toString());
    }
}
