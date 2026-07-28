package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.satoken.AuthServicePort;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.common.InputNormalizer;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.oss.AvatarUploadSignRequest;
import uno.acloud.common.oss.AvatarUploadSignService;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.user.dto.DefaultTeamRequest;
import uno.acloud.user.dto.PasswordChangeRequest;
import uno.acloud.user.dto.UserSettingsRequest;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServiceMemberClient;
import uno.acloud.user.infrastructure.mq.UserEventPublisher;
import uno.acloud.user.mapper.UserEntityMapper;
import uno.acloud.user.mapper.UserMapper;
import uno.acloud.user.vo.CurrentUserVO;
import uno.acloud.user.vo.UserSearchItemVO;

import java.util.List;
import java.util.Optional;

import static uno.acloud.common.InputNormalizer.optionalText;

@Slf4j
@Service
public class UserProfileService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AvatarUploadSignService avatarUploadSignService;
    private final UserEventPublisher userEventPublisher;
    private final TeamServiceMemberClient teamServiceMemberClient;
    private final UserQueryHelper userQueryHelper;
    private final UserEntityMapper userEntityMapper;
    private final AuthServicePort authServicePort;

    public UserProfileService(UserMapper userMapper,
                              PasswordEncoder passwordEncoder,
                              AvatarUploadSignService avatarUploadSignService,
                              UserEventPublisher userEventPublisher,
                              TeamServiceMemberClient teamServiceMemberClient,
                              UserQueryHelper userQueryHelper,
                              UserEntityMapper userEntityMapper,
                              AuthServicePort authServicePort) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.avatarUploadSignService = avatarUploadSignService;
        this.userEventPublisher = userEventPublisher;
        this.teamServiceMemberClient = teamServiceMemberClient;
        this.userQueryHelper = userQueryHelper;
        this.userEntityMapper = userEntityMapper;
        this.authServicePort = authServicePort;
    }

    public Optional<CurrentUserVO> getCurrentUser(Long userId) {
        return userQueryHelper.getCurrentUser(userId);
    }

    public CurrentUserVO getUserSettings(Long userId) {
        return userQueryHelper.requireCurrentUser(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public CurrentUserVO updateSettings(Long userId, UserSettingsRequest request) {
        User dbUser = userQueryHelper.requireExistingUser(userId);
        String name = request == null || request.getName() == null
                ? dbUser.getName()
                : optionalText(request.getName());
        String avatar = request == null || request.getAvatar() == null
                ? dbUser.getAvatar()
                : avatarUploadSignService.normalizeManagedAvatarUrl(request.getAvatar(), "头像地址长度不能超过 512");
        userQueryHelper.requireUpdated(userMapper.updateProfile(userId, name, avatar));
        dbUser.setName(name);
        dbUser.setAvatar(avatar);
        // 在事务提交后发布 MQ 事件，避免事务回滚时下游服务已收到通知
        final Long eventId = dbUser.getId();
        final String eventUsername = dbUser.getUsername();
        final String eventEmail = dbUser.getEmail();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userEventPublisher.publishProfileUpdated(eventId, eventUsername, name, eventEmail, avatar);
                } catch (Exception e) {
                    log.warn("Failed to publish profile updated event for user {} after commit", eventId, e);
                }
            }
        });
        return userQueryHelper.requireCurrentUser(userId);
    }

    public OssSignInfo getAvatarUploadSign(Long userId, AvatarUploadSignRequest request) {
        userQueryHelper.requireExistingUser(userId);
        return avatarUploadSignService.generateAvatarUploadSign(request);
    }

    @Transactional(rollbackFor = Exception.class)
    public CurrentUserVO changePassword(Long userId, PasswordChangeRequest request) {
        User dbUser = userQueryHelper.requireExistingUser(userId);
        String oldPassword = optionalText(request.getOldPassword());
        String newPassword = optionalText(request.getNewPassword());
        if (!userQueryHelper.passwordMatched(oldPassword, dbUser)) {
            throw new BusinessException(UserErrorCode.LOGIN_FAILED, "当前密码错误");
        }
        userQueryHelper.requireUpdated(userMapper.updatePassword(userId, passwordEncoder.encode(newPassword)));

        // 改密后踢出所有旧会话
        final Long uid = userId;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                try {
                    authServicePort.logout(uid);
                } catch (Exception e) {
                    log.warn("改密后踢出会话失败 userId={}", uid, e);
                }
            }
        });
        }

        return userQueryHelper.requireCurrentUser(userId);
    }

    public CurrentUserVO setDefaultTeam(Long userId, DefaultTeamRequest request) {
        userQueryHelper.requireExistingUser(userId);
        Long teamId = request == null ? null : request.getTeamId();
        if (teamId != null) {
            teamServiceMemberClient.requireTeamMember(teamId, userId);
        }
        userQueryHelper.requireUpdated(userMapper.updateDefaultTeam(userId, teamId));
        return userQueryHelper.requireCurrentUser(userId);
    }

    public User getUserById(Long userId) {
        return userMapper.getById(userId);
    }

    public List<UserSearchItemVO> searchUsers(String keyword) {
        String normalizedKeyword = InputNormalizer.requireText(keyword, "搜索关键词不能为空");
        Long numericKeyword = resolveNumericKeyword(normalizedKeyword);
        return userEntityMapper.toUserSearchItemVOList(userMapper.searchUsers(normalizedKeyword, numericKeyword, 20));
    }

    @Nullable
    private Long resolveNumericKeyword(String keyword) {
        if (!keyword.matches("\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
