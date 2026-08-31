package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.im.infrastructure.persistence.entity.FileCardResolveResult;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class FileCardRedisCache {

    static final Duration TTL = Duration.ofMinutes(5);
    static final String MESSAGE_KEY_PREFIX = "zxyz:im:filecard:message:";
    static final String FILE_KEY_PREFIX = "zxyz:im:filecard:file:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public FileCardRedisCache(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<FileCardResolveResult> get(Long messageId) {
        try {
            return readPayload(messageId).map(CachePayload::result);
        } catch (Exception e) {
            log.warn("读取文件卡片 Redis 缓存失败: messageId={}", messageId, e);
            return Optional.empty();
        }
    }

    public void put(Long messageId, Set<Long> fileIds, FileCardResolveResult result) {
        if (messageId == null || result == null || fileIds == null || fileIds.isEmpty()) {
            return;
        }
        try {
            invalidateMessage(messageId);
            CachePayload payload = new CachePayload(result, new HashSet<>(fileIds));
            String messageKey = buildMessageKey(messageId);
            String json = objectMapper.writeValueAsString(payload);
            // Pipeline: batch SET + N * (SADD + EXPIRE) into a single Redis round trip
            stringRedisTemplate.executePipelined((RedisCallback<Void>) connection -> {
                StringRedisConnection stringConn = (StringRedisConnection) connection;
                stringConn.setEx(messageKey, TTL.getSeconds(), json);
                for (Long fileId : fileIds) {
                    if (fileId == null) {
                        continue;
                    }
                    String fileKey = buildFileKey(fileId);
                    stringConn.sAdd(fileKey, String.valueOf(messageId));
                    stringConn.expire(fileKey, TTL.toSeconds());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("写入文件卡片 Redis 缓存失败: messageId={}", messageId, e);
        }
    }

    public void invalidateByFileId(Long fileId) {
        if (fileId == null) {
            return;
        }
        try {
            String fileKey = buildFileKey(fileId);
            Set<String> messageIds = stringRedisTemplate.opsForSet().members(fileKey);
            if (messageIds == null || messageIds.isEmpty()) {
                stringRedisTemplate.delete(fileKey);
                return;
            }
            for (String messageIdValue : messageIds) {
                parseMessageId(messageIdValue).ifPresent(this::invalidateMessage);
            }
            stringRedisTemplate.delete(fileKey);
        } catch (Exception e) {
            log.warn("按文件失效文件卡片 Redis 缓存失败: fileId={}", fileId, e);
        }
    }

    private void invalidateMessage(Long messageId) {
        Optional<CachePayload> payload = readPayload(messageId);
        String messageKey = buildMessageKey(messageId);
        stringRedisTemplate.delete(messageKey);
        if (payload.isEmpty() || payload.get().fileIds() == null || payload.get().fileIds().isEmpty()) {
            return;
        }
        for (Long fileId : payload.get().fileIds()) {
            if (fileId == null) {
                continue;
            }
            String fileKey = buildFileKey(fileId);
            stringRedisTemplate.opsForSet().remove(fileKey, String.valueOf(messageId));
            Long remain = stringRedisTemplate.opsForSet().size(fileKey);
            if (remain != null && remain <= 0) {
                stringRedisTemplate.delete(fileKey);
            }
        }
    }

    private Optional<CachePayload> readPayload(Long messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        String json = stringRedisTemplate.opsForValue().get(buildMessageKey(messageId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, CachePayload.class));
        } catch (Exception e) {
            stringRedisTemplate.delete(buildMessageKey(messageId));
            log.warn("解析文件卡片 Redis 缓存失败，已删除损坏缓存: messageId={}", messageId, e);
            return Optional.empty();
        }
    }

    private Optional<Long> parseMessageId(String messageIdValue) {
        if (messageIdValue == null || messageIdValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(messageIdValue));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String buildMessageKey(Long messageId) {
        return MESSAGE_KEY_PREFIX + messageId;
    }

    private String buildFileKey(Long fileId) {
        return FILE_KEY_PREFIX + fileId;
    }

    private record CachePayload(FileCardResolveResult result, Set<Long> fileIds) {
        private CachePayload {
            fileIds = fileIds == null ? Collections.emptySet() : Set.copyOf(fileIds);
        }
    }
}
