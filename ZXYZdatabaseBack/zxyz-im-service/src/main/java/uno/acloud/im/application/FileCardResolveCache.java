package uno.acloud.im.application;

import org.springframework.stereotype.Component;
import uno.acloud.im.infrastructure.persistence.entity.FileCardResolveResult;

import java.util.Optional;
import java.util.Set;

@Component
public class FileCardResolveCache {

    private final FileCardRedisCache redisCache;

    public FileCardResolveCache(FileCardRedisCache redisCache) {
        this.redisCache = redisCache;
    }

    public Optional<FileCardResolveResult> get(Long messageId) {
        return redisCache.get(messageId);
    }

    public void put(Long messageId, Set<Long> fileIds, FileCardResolveResult result) {
        redisCache.put(messageId, fileIds, result);
    }

    public void invalidateByFileId(Long fileId) {
        redisCache.invalidateByFileId(fileId);
    }
}
