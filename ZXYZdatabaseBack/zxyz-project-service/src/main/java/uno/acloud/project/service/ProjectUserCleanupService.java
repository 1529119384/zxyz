package uno.acloud.project.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.project.mapper.ProjectMapper;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProjectUserCleanupService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:project:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ProjectMapper projectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    public ProjectUserCleanupService(ProjectMapper projectMapper,
                                     org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.projectMapper = projectMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeUserFromProjects(long userId) {
        int deleted = projectMapper.deleteByUserId(userId);
        log.info("移除用户项目成员关系完成: userId={}, removedCount={}", userId, deleted);
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
