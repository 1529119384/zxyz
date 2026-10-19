package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.entity.UserQuota;
import uno.acloud.user.infrastructure.client.TeamServiceMemberClient;
import uno.acloud.user.mapper.UserEntityMapper;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.mapper.UserQuotaMapper;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.user.satoken.SaTokenAuthSessionService;

import uno.acloud.user.vo.UserInfoVO;
import uno.acloud.user.vo.UserQuotaVO;
import uno.acloud.user.vo.UserQuotaVO;

import java.util.List;

/**
 * 内部用户服务（65/66 号 🟠-5 三轮点名）：InternalUserController 的全部业务下沉至此，
 * Controller 只保留 HTTP 边界（照 InternalProjectController Port 范式消解
 * 「Controller 直注 3 Mapper + deleteUser 业务规则在 Controller」的分层违规）。
 */
@Slf4j
@Service
public class InternalUserService {

    private final UserMapper userMapper;
    private final UserQuotaMapper userQuotaMapper;
    private final UserEntityMapper userEntityMapper;
    private final AuthServicePort authServicePort;
    private final TeamServiceMemberClient teamServiceMemberClient;

    public InternalUserService(UserMapper userMapper,
                               UserQuotaMapper userQuotaMapper,
                               UserEntityMapper userEntityMapper,
                               AuthServicePort authServicePort,
                               TeamServiceMemberClient teamServiceMemberClient) {
        this.userMapper = userMapper;
        this.userQuotaMapper = userQuotaMapper;
        this.userEntityMapper = userEntityMapper;
        this.authServicePort = authServicePort;
        this.teamServiceMemberClient = teamServiceMemberClient;
    }

    public User getUserById(Long id) {
        return userMapper.getById(id);
    }

    public List<User> getUsersByIds(List<Long> userIds) {
        return userMapper.listByIds(userIds);
    }

    public UserInfoVO createTeamUser(User user) {
        userMapper.insertTeamUser(user);
        return userEntityMapper.toUserInfoVO(user);
    }

    /** userInfo VO 转换一并下沉（66 号二.10：controller 不再持 UserEntityMapper）。 */
    public UserInfoVO toUserInfo(User user) {
        return userEntityMapper.toUserInfoVO(user);
    }

    public void updateDefaultTeam(Long id, Long teamId) {
        userMapper.updateDefaultTeam(id, teamId);
    }

    public List<Long> listAllUserIds() {
        return userMapper.listAllUserIds();
    }

    public List<String> listVerifiedEmails() {
        return userMapper.listVerifiedEmails();
    }

    public UserQuota getQuota(Long id) {
        return userQuotaMapper.getByUserId(id);
    }

    /** 66 号二.10：配额 VO 转换一并下沉（controller 不再持 UserEntityMapper）。 */
    public UserQuotaVO getQuotaVO(Long id) {
        UserQuota quota = userQuotaMapper.getByUserId(id);
        return quota == null ? null : userEntityMapper.toUserQuotaVO(quota);
    }

    public void clearPermissionCache(Long id) {
        authServicePort.deleteSessionAttribute(id, SaTokenAuthSessionService.EXTRA_ROLE);
        authServicePort.deleteSessionAttribute(id, SaTokenAuthSessionService.EXTRA_PERMISSION);
    }

    /**
     * 删除用户（事务回滚补偿用）。业务规则（仍属团队则拒删）从 Controller 下沉至此。
     */
    public void deleteUser(Long id) {
        List<Long> teamIds = teamServiceMemberClient.listUserTeamIds(id);
        if (teamIds != null && !teamIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户仍关联 " + teamIds.size() + " 个团队，无法删除，请先从各团队移除用户");
        }
        userMapper.deleteById(id);
    }
}
