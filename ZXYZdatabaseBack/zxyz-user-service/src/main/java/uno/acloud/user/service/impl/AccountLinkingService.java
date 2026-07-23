package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.dto.LinkedAccountTrustRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.mapper.UserEntityMapper;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.service.AuthSessionPort;
import uno.acloud.user.vo.AccountSwitchVO;
import uno.acloud.user.vo.CurrentUserVO;
import uno.acloud.user.vo.LinkedAccountVO;

import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;

@Slf4j
@Service
public class AccountLinkingService {
    private final UserMapper userMapper;
    private final AuthSessionPort authSessionService;
    private final TeamServicePermissionClient teamServicePermissionClient;
    private final UserQueryHelper userQueryHelper;
    private final UserProfileService userProfileService;
    private final UserEntityMapper userEntityMapper;

    public AccountLinkingService(UserMapper userMapper,
                                 AuthSessionPort authSessionService,
                                 TeamServicePermissionClient teamServicePermissionClient,
                                 UserQueryHelper userQueryHelper,
                                 UserProfileService userProfileService,
                                 UserEntityMapper userEntityMapper) {
        this.userMapper = userMapper;
        this.authSessionService = authSessionService;
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.userQueryHelper = userQueryHelper;
        this.userProfileService = userProfileService;
        this.userEntityMapper = userEntityMapper;
    }

    public List<LinkedAccountVO> listLinkedAccounts(Long userId) {
        return userMapper.listLinkedAccounts(userId).stream()
                .map(user -> toLinkedAccountVO(userId, user))
                .toList();
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public LinkedAccountVO trustLinkedAccount(Long userId, Long targetUserId, LinkedAccountTrustRequest request) {
        requireLinkedTarget(userId, targetUserId);
        User target = userQueryHelper.requireExistingUser(targetUserId);
        String password = optionalText(request.getPassword());
        if (!userQueryHelper.passwordMatched(password, target)) {
            throw new BusinessException(UserErrorCode.LOGIN_FAILED, "目标账号密码错误");
        }
        userMapper.upsertAccountSwitchTrust(userId, targetUserId);
        userMapper.upsertAccountSwitchTrust(targetUserId, userId);
        return toLinkedAccountVO(userId, target);
    }

    public AccountSwitchVO switchLinkedAccount(Long userId, Long targetUserId) {
        requireLinkedTarget(userId, targetUserId);
        if (userMapper.countAccountSwitchTrust(userId, targetUserId) <= 0) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "首次切换前需要验证目标账号密码");
        }
        User target = userQueryHelper.requireExistingUser(targetUserId);
        Long targetId = target.getId();
        String targetUsername = target.getUsername();
        teamServicePermissionClient.ensureDefaultRole(targetId, targetUsername);
        // 先销毁目标用户旧 session，避免多个 session 同时有效
        authSessionService.logout(targetId);
        String token = authSessionService.createLoginSession(
                targetId,
                targetUsername,
                teamServicePermissionClient.getSystemRolesByUserId(targetId),
                teamServicePermissionClient.getSystemPermissionsByUserId(targetId)
        );
        CurrentUserVO targetCurrentUser = userProfileService.getCurrentUser(target.getId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND, "目标用户不存在"));
        return new AccountSwitchVO(token, "Bearer", true, targetCurrentUser);
    }

    private void requireLinkedTarget(Long userId, Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0 || targetUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标账号不合法");
        }
        if (userMapper.countVerifiedLinkedAccount(userId, targetUserId) <= 0) {
            throw new BusinessException(ErrorCode.NO_PERMISSION, "目标账号未与当前账号绑定同一个已验证联系方式");
        }
    }

    private LinkedAccountVO toLinkedAccountVO(Long sourceUserId, User user) {
        LinkedAccountVO vo = userEntityMapper.toLinkedAccountVO(user);
        vo.setTrusted(userMapper.countAccountSwitchTrust(sourceUserId, user.getId()) > 0);
        return vo;
    }
}
