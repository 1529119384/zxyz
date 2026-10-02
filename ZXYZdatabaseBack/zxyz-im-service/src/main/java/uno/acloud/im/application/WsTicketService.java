package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.ImProperties;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WebSocket 一次性票据。
 *
 * <p><b>为什么去掉 {@code @RefreshScope}（B-4）</b>：本类持有 {@code StringRedisTemplate}，
 * 刷新会销毁并重建实例；票据校验路径若恰好落在刷新窗口内会拒绝创建新 Bean
 * （{@code BeanCreationNotAllowedException}），客户端表现为握手 401。
 * 改由 {@link ImProperties} 在<b>使用时刻</b>读取 TTL ——
 * {@code @ConfigurationProperties} 由 Spring Cloud 就地回填，不重建本 Bean，
 * 热更新能力保留、刷新窗口消失。</p>
 */
@Slf4j
@Service
public class WsTicketService {

    private static final String TICKET_PREFIX = "ws:ticket:";

    private static final RedisScript<String> GETDEL_SCRIPT =
            new DefaultRedisScript<>("local v = redis.call('GET', KEYS[1]); redis.call('DEL', KEYS[1]); return v;", String.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ImProperties imProperties;

    public WsTicketService(StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper,
                           ImProperties imProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.imProperties = imProperties;
    }

    public String createTicket(Long userId, String saToken) {
        try {
            String uuid = UUID.randomUUID().toString();
            String json = objectMapper.writeValueAsString(new TicketInfo(userId, saToken));
            // 使用时刻读取：@ConfigurationProperties 的字段可被 Nacos 动态刷新就地回填
            stringRedisTemplate.opsForValue().set(TICKET_PREFIX + uuid, json,
                    Duration.ofSeconds(imProperties.getWs().getTicketTtlSeconds()));
            return uuid;
        } catch (Exception e) {
            log.warn("Failed to create WebSocket ticket: userId={}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建 WebSocket 票据失败");
        }
    }

    /**
     * 校验并「消费」一次性票据。
     *
     * <p><b>为什么是「先 GET 探针、解析成功后才 GETDEL 消费」（B-5）</b>：早期实现直接
     * GETDEL 再解析，结算顺序反了 —— 只要 GETDEL 成功，票据就已从 Redis 删除，
     * 而随后的 {@code readValue} 一旦失败（Redis 抖动读到半截值、
     * 序列化形态变更等）就只会返回 {@code Optional.empty()}。上层在 prod
     * （{@code allowTokenFallback=false}）会据此拒绝握手，于是出现
     * 「票据有效却被 401，且票据已被销毁、客户端重连必失败」的诡异状态。</p>
     *
     * <p>现在只有<b>解析成功</b>才进入消费阶段；并发下同一票据被两个连接同时校验时，
     * GETDEL 的原子性保证只有一方拿到值，另一方拿到 null 并失败 —— 一次性语义不变。</p>
     *
     * @param ticket 客户端提交的一次性票据（可空）
     * @return 解析成功且成功消费时返回票据信息；否则 {@link Optional#empty()}
     */
    public Optional<TicketInfo> resolveAndConsumeTicket(@Nullable String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String key = TICKET_PREFIX + ticket;
        String json;
        try {
            // 阶段一：只读探针。此阶段无论如何都不会删除票据。
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Failed to read WebSocket ticket（未消费，客户端可重试）", e);
            return Optional.empty();
        }
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        TicketInfo info;
        try {
            info = objectMapper.readValue(json, TicketInfo.class);
        } catch (Exception e) {
            // 解析失败：票据可能是坏的，但「坏」不等于「该被悄悄删掉」——
            // 保留它让 TTL 自然过期，运维也还能在 Redis 里看到现场。
            log.error("WebSocket 票据解析失败（不消费，保留至 TTL 过期）: ticket={}", ticket, e);
            return Optional.empty();
        }
        if (info == null) {
            return Optional.empty();
        }

        try {
            // 阶段二：原子消费。并发下只有一方能拿到值。
            String consumed = stringRedisTemplate.execute(GETDEL_SCRIPT, List.of(key));
            if (consumed == null || consumed.isBlank()) {
                log.warn("WebSocket 票据已被并发消费，本次握手失败: ticket={}", ticket);
                return Optional.empty();
            }
            return Optional.of(info);
        } catch (Exception e) {
            log.warn("Failed to consume WebSocket ticket", e);
            return Optional.empty();
        }
    }

    public record TicketInfo(Long userId, String saToken) {
    }
}
