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

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:file:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

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

    public boolean tryAcquireIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        return stringRedisTemplate.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS);
    }

    public void releaseIdempotencyKey(long userId) {
        String key = IDEMPOTENCY_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
    }
}
