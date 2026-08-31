package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.persistence.entity.UserProfile;
import uno.acloud.im.infrastructure.mapper.UserProfileMapper;
import uno.acloud.im.dto.InternalUserProfileSyncRequest;

import static uno.acloud.common.InputNormalizer.optionalText;

@Slf4j
@Service
public class InternalUserProfileSyncService {

    private final UserProfileMapper userProfileMapper;

    public InternalUserProfileSyncService(UserProfileMapper userProfileMapper) {
        this.userProfileMapper = userProfileMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncUserProfile(InternalUserProfileSyncRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户资料同步参数不完整");
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(request.getUserId());
        profile.setUsername(StringUtils.hasText(request.getUsername()) ? request.getUsername() : "user-" + request.getUserId());
        profile.setName(optionalText(request.getName()));
        profile.setAvatar(optionalText(request.getAvatar()));
        userProfileMapper.upsert(profile);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeUserProfile(Long userId) {
        if (userId == null) {
            return;
        }
        userProfileMapper.deleteByUserId(userId);
        log.info("删除用户 IM 资料: userId={}", userId);
    }
}
