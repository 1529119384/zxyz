package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * IM WebSocket 长连接的 Sa-Token 会话续签服务（消息驱动 + 节流）。
 *
 * <p><b>要解决的问题</b>：{@code sa-token.active-timeout: 1800} 的被动续签只认
 * 「直接或间接调用 getLoginId()/getTokenSession()」，IM WS 握手鉴权用的
 * {@code getLoginIdByToken(token)} 不在清单内，且握手后 Netty 管道内没有任何
 * Sa-Token 调用 —— 用户在 IM 页纯聊天 30 分钟（无 HTTP 请求）后 token 被
 * 冻结（active-timeout 场景 -3），此后全站 HTTP 请求 401、重连握手也失败。
 * 本服务让 WS 连接存活期间的活动反过来给 token 续命。</p>
 *
 * <p><b>触发点</b>：每条业务消息帧 / PING 心跳帧处理入口由
 * {@code ImWebSocketFrameHandler} 调用 {@link #renew(Long, String)}（O(1)）。</p>
 *
 * <p><b>节流</b>：lastRenewAt 以 {@code AtomicLong} 挂在 Channel attribute 上，
 * 与 Channel 同生命周期 —— 连接关闭即随 Channel 一起被 GC，<b>不用</b>全局 Map
 * 存（防泄漏）。同一连接两次续签间隔 &lt; {@link #MIN_RENEW_INTERVAL_MS}
 * （5 分钟，30 分钟 active-timeout 下足够）直接跳过。</p>
 *
 * <p><b>失败静默</b>：renew 全程 try-catch，任何异常只打 debug 日志，
 * 绝不影响消息主链路。</p>
 */
@Slf4j
@Service
public class ImSessionKeepAliveService {

    /** 同一连接两次续签的最小间隔（毫秒）。5 分钟 &lt;&lt; active-timeout 30 分钟，节流足够。 */
    public static final long MIN_RENEW_INTERVAL_MS = 5 * 60 * 1000L;

    private final SaTokenRenewDelegate renewDelegate;

    public ImSessionKeepAliveService(SaTokenRenewDelegate renewDelegate) {
        this.renewDelegate = renewDelegate;
    }

    /**
     * 尝试为一条 WS 连续续签其底层 Sa-Token 会话。
     *
     * <p>调用方约定：token 取自握手时挂到 Channel attribute 的裸 token 值
     * （{@code ImChannelAttributes.TOKEN}，无 Bearer 前缀；由
     * {@code ImWebSocketAuthHandler} 在两处握手成功路径写入）。
     * 本方法 O(1)、不抛异常、不阻塞 —— 适合在每条帧处理入口调用。</p>
     *
     * @param lastRenewAt per-channel 节流时间戳（挂 Channel attribute 的 {@link AtomicLong}，
     *                    可为 null —— 视作「从未续签过」）
     * @param tokenValue  握手鉴权时记录的 token 值；null/blank 视作未认证连接，静默跳过
     * @return true 表示本次实际执行了底层续签（未被节流跳过、未被静默跳过）
     */
    public boolean renew(AtomicLong lastRenewAt, String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            // 未认证连接（attr 未挂 token）：静默不续签，也不写节流时间戳
            return false;
        }
        long now = System.currentTimeMillis();
        if (lastRenewAt != null && now - lastRenewAt.get() < MIN_RENEW_INTERVAL_MS) {
            // 节流：5 分钟内已续签过，跳过（底层续签是 Redis 写，没必要每帧一次）
            return false;
        }
        if (lastRenewAt != null) {
            lastRenewAt.set(now);
        }
        try {
            renewDelegate.updateLastActiveToNow(tokenValue);
            return true;
        } catch (Exception e) {
            // 失败静默：token 可能已被冻结/注销/Redis 抖动 —— 续不上就算了，
            // 绝不影响消息主链路（token 冻结的恢复由用户下一次 HTTP 请求的 401 流程兜底）
            log.debug("IM session renew skipped (token may be frozen or logged out): {}", e.getMessage());
            return false;
        }
    }
}
