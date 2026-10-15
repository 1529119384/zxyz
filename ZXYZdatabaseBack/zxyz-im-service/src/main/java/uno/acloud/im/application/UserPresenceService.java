package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.im.infrastructure.persistence.entity.UserPresence;
import uno.acloud.im.convert.ImEntityMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.infrastructure.mapper.UserPresenceMapper;
import uno.acloud.im.vo.UserPresenceVO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserPresenceService {

    private final UserPresenceMapper userPresenceMapper;
    private final TeamMapper teamMapper;
    private final ImEntityMapper imEntityMapper;

    public UserPresenceService(UserPresenceMapper userPresenceMapper,
                               TeamMapper teamMapper,
                               ImEntityMapper imEntityMapper) {
        this.userPresenceMapper = userPresenceMapper;
        this.teamMapper = teamMapper;
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

    /**
     * 批量查询在线状态，仅返回与调用方存在「同团队成员」关系的目标（60 号 🟡-1）。
     *
     * <p>关系判定取既有范式：双方均为某活跃团队的活跃成员
     * （{@link TeamMapper#listSharedActiveTeamIds}）。无关系的 ID 一律回退为
     * 「离线」占位 VO（与库内无记录的未知用户同形态），既不泄露存在性，也保持响应
     * 结构可被既有前端消费。每调用一次执行一条共享团队 SQL（目标已去重，≤20 个）。</p>
     */
    public List<UserPresenceVO> listPresence(Long requesterUserId, List<Long> userIds) {
        List<Long> normalizedUserIds = userIds == null ? List.of() : userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .distinct()
                .toList();
        if (normalizedUserIds.isEmpty()) {
            return List.of();
        }

        Set<Long> relatedUserIds = resolveRelatedUserIds(requesterUserId, normalizedUserIds);

        Map<Long, UserPresence> presenceMap = userPresenceMapper.listByUserIds(normalizedUserIds).stream()
                .collect(Collectors.toMap(UserPresence::getUserId, presence -> presence, (left, right) -> left, LinkedHashMap::new));
        return normalizedUserIds.stream()
                .map(userId -> toVO(userId, relatedUserIds.contains(userId) ? presenceMap.get(userId) : null))
                .toList();
    }

    private Set<Long> resolveRelatedUserIds(Long requesterUserId, List<Long> userIds) {
        Set<Long> related = new java.util.HashSet<>();
        for (Long userId : userIds) {
            if (userId.equals(requesterUserId)) {
                related.add(userId);
                continue;
            }
            if (!teamMapper.listSharedActiveTeamIds(requesterUserId, userId).isEmpty()) {
                related.add(userId);
            }
        }
        return related;
    }

    private UserPresenceVO toVO(Long userId, UserPresence presence) {
        if (presence == null) {
            return new UserPresenceVO(userId, false, 0, null);
        }
        return imEntityMapper.toPresenceVO(presence);
    }
}
