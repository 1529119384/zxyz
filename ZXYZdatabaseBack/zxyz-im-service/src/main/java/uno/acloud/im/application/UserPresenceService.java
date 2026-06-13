package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.im.domain.model.UserPresence;
import uno.acloud.im.infrastructure.mapper.ImEntityMapper;
import uno.acloud.im.infrastructure.mapper.UserPresenceMapper;
import uno.acloud.im.vo.UserPresenceVO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserPresenceService {

    private final UserPresenceMapper userPresenceMapper;
    private final ImEntityMapper imEntityMapper;

    public UserPresenceService(UserPresenceMapper userPresenceMapper,
                               ImEntityMapper imEntityMapper) {
        this.userPresenceMapper = userPresenceMapper;
        this.imEntityMapper = imEntityMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markOnline(Long userId, int connectionCount) {
        userPresenceMapper.markOnline(userId, connectionCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateOnlineCount(Long userId, int connectionCount) {
        userPresenceMapper.updateOnlineCount(userId, connectionCount > 0, connectionCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markOffline(Long userId, LocalDateTime lastActiveTime) {
        userPresenceMapper.markOffline(userId, lastActiveTime);
    }

    public UserPresenceVO getPresence(Long userId) {
        UserPresence presence = userPresenceMapper.getByUserId(userId);
        return toVO(userId, presence);
    }

    public List<UserPresenceVO> listPresence(List<Long> userIds) {
        List<Long> normalizedUserIds = userIds == null ? List.of() : userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .distinct()
                .toList();
        if (normalizedUserIds.isEmpty()) {
            return List.of();
        }

        Map<Long, UserPresence> presenceMap = userPresenceMapper.listByUserIds(normalizedUserIds).stream()
                .collect(Collectors.toMap(UserPresence::getUserId, presence -> presence, (left, right) -> left, LinkedHashMap::new));
        return normalizedUserIds.stream()
                .map(userId -> toVO(userId, presenceMap.get(userId)))
                .toList();
    }

    private UserPresenceVO toVO(Long userId, UserPresence presence) {
        if (presence == null) {
            return new UserPresenceVO(userId, false, 0, null);
        }
        return imEntityMapper.toPresenceVO(presence);
    }
}
