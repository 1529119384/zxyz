package uno.acloud.satoken;

/**
 * 认证与授权服务端口（六边形架构中的 Port）。
 *
 * <p>封装 Sa-Token 的核心操作，使业务代码不直接依赖 StpUtil 静态方法，
 * 便于单元测试 mock 和未来替换认证框架。</p>
 *
 * <p>当前实现委托给 {@link SaTokenAuthServicePort}。
 * 迁移状态：以下文件仍直接使用 StpUtil，待后续逐步迁移：</p>
 * <ul>
 *   <li>zxyz-user-service (已迁移): UserController, InternalUserController, UserQueryHelper, SaTokenAuthSessionService</li>
 *   <li>zxyz-project-service: StorageUsageController, ProjectQuotaController,
 *       ProjectMemberController, ProjectLifecycleController, ProjectCreateRequestController, ProjectCatalogController</li>
 *   <li>zxyz-file-service: FileController, FolderController, TrashController</li>
 *   <li>zxyz-share-service: ShareController</li>
 *   <li>zxyz-team-service: TeamController, TeamPermissionController, SystemPermissionController</li>
 *   <li>zxyz-im-service (已迁移): WsTicketController, ImTokenAuthService</li>
 * </ul>
 *
 * @see SaTokenAuthServicePort
 */
public interface AuthServicePort {

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID
     * @throws cn.dev33.satoken.exception.NotLoginException 如果未登录
     */
    long getCurrentUserId();

    /**
     * 检查当前是否已登录，未登录则抛出异常。
     *
     * @throws cn.dev33.satoken.exception.NotLoginException 如果未登录
     */
    void checkLogin();

    /**
     * 判断当前是否已登录。
     *
     * @return true 如果已登录
     */
    boolean isLogin();

    /**
     * 检查当前用户是否拥有指定权限，无权限则抛出异常。
     *
     * @param permissionCode 权限码
     * @throws cn.dev33.satoken.exception.NotPermissionException 如果无权限
     */
    void checkPermission(String permissionCode);

    /**
     * 判断当前用户是否拥有指定权限。
     *
     * @param permissionCode 权限码
     * @return true 如果拥有权限
     */
    boolean hasPermission(String permissionCode);

    /**
     * 检查当前用户是否拥有指定角色，无角色则抛出异常。
     *
     * @param roleCode 角色码
     * @throws cn.dev33.satoken.exception.NotRoleException 如果无角色
     */
    void checkRole(String roleCode);

    /**
     * 判断当前用户是否拥有指定角色。
     *
     * @param roleCode 角色码
     * @return true 如果拥有角色
     */
    boolean hasRole(String roleCode);

    /**
     * 创建登录会话。
     *
     * @param userId 用户 ID
     */
    void login(long userId);

    /**
     * 创建登录会话（带自定义超时时间）。
     *
     * @param userId  用户 ID
     * @param timeout 超时时间（秒）
     */
    void login(long userId, long timeout);

    /**
     * 注销当前登录会话。
     */
    void logout();

    /**
     * 注销指定用户的登录会话（强制踢出）。
     *
     * @param userId 用户 ID
     */
    void logout(long userId);

    // ---- Session operations ----

    /**
     * 获取指定用户的会话属性。
     *
     * @param userId 用户 ID（loginId）
     * @param key    属性键
     * @return 属性值，不存在时返回 null
     */
    Object getSessionAttribute(long userId, String key);

    /**
     * 设置指定用户的会话属性。
     *
     * @param userId 用户 ID（loginId）
     * @param key    属性键
     * @param value  属性值
     */
    void setSessionAttribute(long userId, String key, Object value);

    /**
     * 删除指定用户的会话属性。
     *
     * @param userId 用户 ID（loginId）
     * @param key    属性键
     */
    void deleteSessionAttribute(long userId, String key);

    /**
     * 获取当前登录用户的会话属性。
     *
     * @param key 属性键
     * @return 属性值，不存在时返回 null
     */
    Object getCurrentSessionAttribute(String key);

    // ---- Token operations ----

    /**
     * 获取当前请求的 Token 值。
     *
     * @return Token 字符串
     */
    String getTokenValue();

    /**
     * 根据 Token 值获取对应的用户 ID。
     *
     * @param token Token 字符串
     * @return 用户 ID，Token 无效时返回 null
     */
    Long getLoginIdByToken(String token);
}
