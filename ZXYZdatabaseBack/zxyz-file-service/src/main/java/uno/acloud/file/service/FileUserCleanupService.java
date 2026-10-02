package uno.acloud.file.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.service.impl.FileObjectReferenceManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class FileUserCleanupService {

    /**
     * 幂等键采用<b>两段式</b>（F4，P2），刻意拆成「处理中」与「已完成」两个键：
     *
     * <ul>
     *   <li>{@code …:processing:} —— 短 TTL（{@value #PROCESSING_TTL_MINUTES} 分钟），
     *       表示「本次清理正在进行」。进程在清理完成前崩溃时，它会在短 TTL 后<b>自然过期</b>，
     *       后续重投递得以重新接管执行；</li>
     *   <li>{@code …:done:} —— 长 TTL（{@value #DONE_TTL_HOURS} 小时），只在清理<b>真正成功</b>后写入，
     *       负责把重复投递拦在真正的「已完成」语义上。</li>
     * </ul>
     *
     * <h2>为什么必须拆开（修复前的缺陷）</h2>
     * <p>旧实现只有一个 24 小时的键，且它是「<b>认领</b>」而非「完成」的标记：
     * {@code setIfAbsent} 成功后进程若在清理跑完前崩溃，键会<b>残留整整 24 小时</b>，
     * 期间所有重投递都命中「重复用户删除事件，跳过」，日志还把「半途而废」误报成「重复事件」
     * ⇒ 该用户的个人空间文件最长 24 小时清不掉，且运维从日志上看不出异常。</p>
     * <p>拆开后，崩溃场景的恢复窗口从「最长 24 小时」缩短到
     * {@value #PROCESSING_TTL_MINUTES} 分钟，而「真正已完成」的语义仍由长 TTL 的 done 键守住。</p>
     */
    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:file:processing:";

    /** 已完成标记键前缀（长 TTL，只在清理成功后写入）。 */
    private static final String COMPLETED_KEY_PREFIX = "mq:idempotent:user:deleted:file:done:";

    /** 处理中键的短 TTL（分钟）：崩溃后最多这么久就能被重投递接管。 */
    private static final long PROCESSING_TTL_MINUTES = 10;

    /** 已完成键的长 TTL（小时）：真正的重复投递在此窗口内被跳过。 */
    private static final long DONE_TTL_HOURS = 24;

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

    /**
     * 认领本次清理。返回 {@code false} 有两种成因，语义都是「本次不要再跑」：
     * <ol>
     *   <li>{@code done} 键存在 ⇒ 该用户的清理<b>已真正完成</b>（正常的重复投递）；</li>
     *   <li>{@code processing} 键仍在 ⇒ 另一个消费者正在处理（并发投递），或上一个消费者
     *       崩溃后短 TTL 尚未过期 —— 后者最多 {@value #PROCESSING_TTL_MINUTES} 分钟后即可重试。</li>
     * </ol>
     */
    public boolean tryAcquireIdempotencyKey(long userId) {
        if (isCleanupCompleted(userId)) {
            return false;
        }
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(processingKey(userId), "1", PROCESSING_TTL_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 清理<b>成功</b>后调用：写入长 TTL 的 done 键并释放 processing 键。
     * <p>必须先写 done 再删 processing：反过来的话，两步之间崩溃会让两个键都不存在，
     * 重复投递会重新执行一遍清理（虽然幂等，但白跑一次；此处保持「宁可多跑不可漏清」之外的更优顺序）。</p>
     */
    public void markCleanupCompleted(long userId) {
        stringRedisTemplate.opsForValue().set(completedKey(userId), "1", DONE_TTL_HOURS, TimeUnit.HOURS);
        releaseIdempotencyKey(userId);
    }

    /** 该用户的清理是否已真正完成（供消费者区分「已完成」与「处理中」以便日志准确）。 */
    public boolean isCleanupCompleted(long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(completedKey(userId)));
    }

    /** 释放「处理中」认领（失败回滚路径）。 */
    public void releaseIdempotencyKey(long userId) {
        stringRedisTemplate.delete(processingKey(userId));
    }

    private String processingKey(long userId) {
        return IDEMPOTENCY_KEY_PREFIX + userId;
    }

    private String completedKey(long userId) {
        return COMPLETED_KEY_PREFIX + userId;
    }
}
