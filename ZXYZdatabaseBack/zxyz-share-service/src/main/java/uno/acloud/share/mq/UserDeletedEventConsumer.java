package uno.acloud.share.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.share.config.RabbitMqConfig;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消费用户注销/删除事件，清理用户创建的分享。
 *
 * <p>share-service 监听 user.deleted 路由键，执行以下清理流程：
 * <ol>
 *   <li>查找用户创建的所有分享</li>
 *   <li>批量删除分享项（share_item）</li>
 *   <li>删除分享记录（share）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final ShareMapper shareMapper;
    private final StringRedisTemplate redisTemplate;

    public UserDeletedEventConsumer(ObjectMapper objectMapper,
                                    ShareMapper shareMapper,
                                    StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.shareMapper = shareMapper;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        try {
            UserDeletedEvent event = objectMapper.readValue(message, UserDeletedEvent.class);
            String eventType = event.eventType();
            if (!RabbitMqConstants.ROUTING_KEY_USER_DELETED.equals(eventType)) {
                log.debug("MQ: 忽略非 user.deleted 事件: eventType={}", eventType);
                return;
            }

            long userId = event.userId();
            String username = event.username();

            // 幂等性检查：userId 作为去重 key
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + userId;
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户删除事件，跳过处理: key={}", idempotencyKey);
                return;
            }

            log.info("MQ: 开始清理用户分享: userId={}, username={}", userId, username);
            cleanupUserShares(userId);
            log.info("MQ: 用户分享清理完成: userId={}, username={}", userId, username);
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }

    /**
     * 清理用户创建的所有分享及其关联数据。
     */
    @Transactional(rollbackFor = Exception.class)
    protected void cleanupUserShares(long userId) {
        List<uno.acloud.share.infrastructure.entity.Share> shares = shareMapper.listByUserId(userId);
        if (shares == null || shares.isEmpty()) {
            log.debug("用户无分享记录: userId={}", userId);
            return;
        }

        for (uno.acloud.share.infrastructure.entity.Share share : shares) {
            List<ShareItem> items = shareMapper.listItemsByShareId(share.getId());
            if (items != null && !items.isEmpty()) {
                List<Long> fileIds = items.stream()
                        .map(ShareItem::getFileId)
                        .toList();
                shareMapper.deleteShareItemsByFileIds(fileIds);
            }
            shareMapper.deleteById(share.getId());
        }
        log.info("清理用户分享完成: userId={}, shareCount={}", userId, shares.size());
    }
}
