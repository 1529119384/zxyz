package uno.acloud.im.application;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.im.infrastructure.persistence.entity.UserProfile;
import uno.acloud.im.infrastructure.client.UserServiceClient;
import uno.acloud.im.infrastructure.mapper.UserProfileMapper;
import uno.acloud.im.vo.UserProfileVO;

import java.util.List;

import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class UserProfileService {

    private final UserProfileMapper userProfileMapper;
    private final UserServiceClient userServiceClient;
    private final UserProfileService self;

    public UserProfileService(UserProfileMapper userProfileMapper,
                              UserServiceClient userServiceClient,
                              @Lazy UserProfileService self) {
        this.userProfileMapper = userProfileMapper;
        this.userServiceClient = userServiceClient;
        this.self = self;
    }

    public List<UserProfileVO> searchAndSync(String keyword) {
        String normalizedKeyword = requireText(keyword, "搜索关键词不能为空");
        // HTTP call outside transaction to avoid holding DB connection during remote I/O
        List<UserProfile> profiles = userServiceClient.searchUsers(normalizedKeyword);
        // DB operations via proxy to ensure proper transaction boundary
        return self.syncAndSearchLocal(normalizedKeyword, profiles);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<UserProfileVO> syncAndSearchLocal(String normalizedKeyword, List<UserProfile> profiles) {
        for (UserProfile profile : profiles) {
            userProfileMapper.upsert(profile);
        }
        return userProfileMapper.searchLocal(normalizedKeyword, 20);
    }

    public void ensurePlaceholder(Long userId) {
        if (userProfileMapper.getByUserId(userId) == null) {
            userProfileMapper.insertPlaceholder(userId, "user-" + userId);
        }
    }
}
