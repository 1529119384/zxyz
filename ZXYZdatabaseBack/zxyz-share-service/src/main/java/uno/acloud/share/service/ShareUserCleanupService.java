package uno.acloud.share.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ShareUserCleanupService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:share:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ShareMapper shareMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    public ShareUserCleanupService(ShareMapper shareMapper,
                                   org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.shareMapper = shareMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanupUserShares(long userId) {
        List<Share> shares = shareMapper.listByUserId(userId);
        if (shares == null || shares.isEmpty()) {
            log.debug("用户无分享记录: userId={}", userId);
            return;
        }

        for (Share share : shares) {
            shareMapper.deleteShareItemsByShareId(share.getId());
            shareMapper.deleteById(share.getId());
        }
        log.info("清理用户分享完成: userId={}, shareCount={}", userId, shares.size());
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
