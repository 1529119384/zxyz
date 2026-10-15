package uno.acloud.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.mq.MqUserDeletedCleanupSupport;
import uno.acloud.common.mq.MqUserDeletedEventConsumerTemplate;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.service.impl.FileObjectReferenceManager;

import java.util.List;

@Slf4j
@Service
public class FileUserCleanupService implements MqUserDeletedEventConsumerTemplate.Cleanup {

    /**
     * 幂等键采用<b>两段式</b>（F4，P2），语义与 TTL 见
     * {@link MqUserDeletedCleanupSupport} —— 三件套已下沉 common 统一实现
     * （60 号审计 🟠-4：本服务是范本，project/share/team 已同步收敛到同一实现）。
     * 键前缀保持本服务既有形态（:processing: / :done:），历史键自然过期。
     */
    private static final String PROCESSING_KEY_PREFIX = "mq:idempotent:user:deleted:file:processing:";

    /** 已完成标记键前缀（长 TTL，只在清理成功后写入）。 */
    private static final String COMPLETED_KEY_PREFIX = "mq:idempotent:user:deleted:file:done:";

    private final FileMapper fileMapper;
    private final FileObjectReferenceManager fileObjectReferenceManager;
    private final StringRedisTemplate stringRedisTemplate;

    public FileUserCleanupService(FileMapper fileMapper,
                                   FileObjectReferenceManager fileObjectReferenceManager,
                                   StringRedisTemplate stringRedisTemplate) {
        this.fileMapper = fileMapper;
        this.fileObjectReferenceManager = fileObjectReferenceManager;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanupUserPersonalFiles(long userId) {
        List<Long> rootFileIds = fileMapper.getPersonalRootFileIds(userId);
        if (rootFileIds == null || rootFileIds.isEmpty()) {
            log.debug("用户无个人空间文件: userId={}", userId);
            return;
        }

        List<Long> allIds = fileMapper.collectDescendantIds(rootFileIds);
        if (allIds == null || allIds.isEmpty()) {
            log.warn("收集文件树节点失败: userId={}", userId);
            return;
        }

        List<String> ossKeys = fileMapper.getOssKeysByIds(allIds);
        int deleted = fileMapper.reallyDeleteByIds(allIds, userId);
        if (deleted != allIds.size()) {
            log.warn("部分文件节点删除失败: userId={}, expected={}, actual={}", userId, allIds.size(), deleted);
        }
        log.info("硬删除用户个人文件完成: userId={}, count={}", userId, deleted);

        if (!ossKeys.isEmpty()) {
            fileObjectReferenceManager.releaseReferences(ossKeys);
        }
    }

    /** {@link MqUserDeletedEventConsumerTemplate.Cleanup} 适配：模板统一调 cleanup(long)。 */
    @Override
    public void cleanup(long userId) {
        cleanupUserPersonalFiles(userId);
    }

    /**
     * 认领本次清理。返回 {@code false} 有两种成因，语义都是「本次不要再跑」：
     * <ol>
     *   <li>{@code done} 键存在 ⇒ 该用户的清理<b>已真正完成</b>（正常的重复投递）；</li>
     *   <li>{@code processing} 键仍在 ⇒ 另一个消费者正在处理（并发投递），或上一个消费者
     *       崩溃后短 TTL 尚未过期 —— 后者最多 {@link MqUserDeletedCleanupSupport#PROCESSING_TTL_MINUTES}
     *       分钟后即可重试。</li>
     * </ol>
     */
    public boolean tryAcquireIdempotencyKey(long userId) {
        return MqUserDeletedCleanupSupport.tryAcquireIdempotencyKey(
                stringRedisTemplate, PROCESSING_KEY_PREFIX, COMPLETED_KEY_PREFIX, userId);
    }

    /**
     * 清理<b>成功</b>后调用：写入长 TTL 的 done 键并释放 processing 键。
     * <p>必须先写 done 再删 processing：反过来的话，两步之间崩溃会让两个键都不存在，
     * 重复投递会重新执行一遍清理（虽然幂等，但白跑一次；此处保持「宁可多跑不可漏清」之外的更优顺序）。</p>
     */
    public void markCleanupCompleted(long userId) {
        MqUserDeletedCleanupSupport.markCleanupCompleted(
                stringRedisTemplate, PROCESSING_KEY_PREFIX, COMPLETED_KEY_PREFIX, userId);
    }

    /** 该用户的清理是否已真正完成（供消费者区分「已完成」与「处理中」以便日志准确）。 */
    public boolean isCleanupCompleted(long userId) {
        return MqUserDeletedCleanupSupport.isCleanupCompleted(
                stringRedisTemplate, COMPLETED_KEY_PREFIX, userId);
    }

    /** 释放「处理中」认领（失败回滚路径）。 */
    public void releaseIdempotencyKey(long userId) {
        MqUserDeletedCleanupSupport.releaseIdempotencyKey(
                stringRedisTemplate, PROCESSING_KEY_PREFIX, userId, log);
    }
}
