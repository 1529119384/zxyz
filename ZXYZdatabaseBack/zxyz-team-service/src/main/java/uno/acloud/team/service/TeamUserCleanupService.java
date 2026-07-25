package uno.acloud.team.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.team.mapper.TeamMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TeamUserCleanupService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:team:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final TeamMapper teamMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    public TeamUserCleanupService(TeamMapper teamMapper,
                                  org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.teamMapper = teamMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeUserFromTeams(long userId) {
        List<Long> teamIds = teamMapper.listMyTeams(userId).stream()
                .map(uno.acloud.team.entity.Team::getId)
                .toList();

        int removed = 0;
        int skipped = 0;
        for (Long teamId : teamIds) {
            try {
                int result = teamMapper.removeMember(teamId, userId);
                if (result > 0) {
                    removed++;
                }
            } catch (Exception e) {
                log.warn("移除用户团队成员关系失败（可能用户是团队所有者）: userId={}, teamId={}", userId, teamId, e);
                skipped++;
            }
        }
        log.info("移除用户团队成员关系完成: userId={}, removed={}, skipped={}", userId, removed, skipped);
    }

    public boolean tryAcquireIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        return redisTemplate.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);
    }

    public void releaseIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }
}
