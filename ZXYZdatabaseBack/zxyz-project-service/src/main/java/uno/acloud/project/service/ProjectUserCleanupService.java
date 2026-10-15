package uno.acloud.project.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.mq.MqUserDeletedCleanupSupport;
import uno.acloud.common.mq.MqUserDeletedEventConsumerTemplate;
import uno.acloud.project.mapper.ProjectMapper;

@Slf4j
@Service
public class ProjectUserCleanupService implements MqUserDeletedEventConsumerTemplate.Cleanup {

    /**
     * 幂等键采用两段式（认领键 + done 键，F4），语义见
     * {@link MqUserDeletedCleanupSupport} —— 60 号审计 🟠-4：此前本类是裸的 24h 占位键，
     * 清理完成前崩溃会让重投递被占位键压制 24 小时（「半途而废」被误报成「重复事件」）。
     * 三件套语义已下沉 common，四处消费端统一调用；键前缀保持既有形态，历史键自然过期。
     */
    private static final String PROCESSING_KEY_PREFIX = "mq:idempotent:user:deleted:project:processing:";
    private static final String DONE_KEY_PREFIX = "mq:idempotent:user:deleted:project:done:";

    private final ProjectMapper projectMapper;
    private final StringRedisTemplate redisTemplate;

    public ProjectUserCleanupService(ProjectMapper projectMapper,
                                     StringRedisTemplate redisTemplate) {
        this.projectMapper = projectMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeUserFromProjects(long userId) {
        int deleted = projectMapper.deleteByUserId(userId);
        log.info("移除用户项目成员关系完成: userId={}, removedCount={}", userId, deleted);
    }

    /** {@link MqUserDeletedEventConsumerTemplate.Cleanup} 适配：模板统一调 cleanup(long)。 */
    @Override
    public void cleanup(long userId) {
        removeUserFromProjects(userId);
    }

    public boolean tryAcquireIdempotencyKey(long userId) {
        return MqUserDeletedCleanupSupport.tryAcquireIdempotencyKey(
                redisTemplate, PROCESSING_KEY_PREFIX, DONE_KEY_PREFIX, userId);
    }

    public void markCleanupCompleted(long userId) {
        MqUserDeletedCleanupSupport.markCleanupCompleted(
                redisTemplate, PROCESSING_KEY_PREFIX, DONE_KEY_PREFIX, userId);
    }

    public boolean isCleanupCompleted(long userId) {
        return MqUserDeletedCleanupSupport.isCleanupCompleted(redisTemplate, DONE_KEY_PREFIX, userId);
    }

    public void releaseIdempotencyKey(long userId) {
        MqUserDeletedCleanupSupport.releaseIdempotencyKey(
                redisTemplate, PROCESSING_KEY_PREFIX, userId, log);
    }
}
