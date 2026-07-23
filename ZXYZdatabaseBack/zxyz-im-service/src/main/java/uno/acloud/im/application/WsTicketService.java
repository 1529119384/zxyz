package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class WsTicketService {

    private static final String TICKET_PREFIX = "ws:ticket:";
    /** WebSocket 票据有效期 fallback（30 秒） */
    private static final int FALLBACK_TICKET_TTL_SECONDS = 30;

    private static final RedisScript<String> GETDEL_SCRIPT =
            new DefaultRedisScript<>("local v = redis.call('GET', KEYS[1]); redis.call('DEL', KEYS[1]); return v;", String.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigGetter configGetter;
    private final Duration ticketTtl;

    public WsTicketService(StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper,
                           ConfigGetter configGetter) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.configGetter = configGetter;
        this.ticketTtl = Duration.ofSeconds(configGetter.getInt("app.im.ws.ticket-ttl-seconds", FALLBACK_TICKET_TTL_SECONDS));
    }

    public String createTicket(Long userId, String saToken) {
        try {
            String uuid = UUID.randomUUID().toString();
            String json = objectMapper.writeValueAsString(new TicketInfo(userId, saToken));
            stringRedisTemplate.opsForValue().set(TICKET_PREFIX + uuid, json, ticketTtl);
            return uuid;
        } catch (Exception e) {
            log.warn("Failed to create WebSocket ticket: userId={}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建 WebSocket 票据失败");
        }
    }

    public Optional<TicketInfo> resolveAndConsumeTicket(@Nullable String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = stringRedisTemplate.execute(GETDEL_SCRIPT, List.of(TICKET_PREFIX + ticket));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, TicketInfo.class));
        } catch (Exception e) {
            log.warn("Failed to resolve WebSocket ticket", e);
            return Optional.empty();
        }
    }

    public record TicketInfo(Long userId, String saToken) {
    }
}
