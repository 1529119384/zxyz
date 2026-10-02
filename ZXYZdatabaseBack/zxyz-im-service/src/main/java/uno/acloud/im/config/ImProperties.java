package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IM 服务的<b>热更新</b>配置（prefix {@code app.im}），绑定 {@code nacos-config/zxyz-dynamic.yml}。
 *
 * <p><b>为什么要有这个类（B-4 的核心）</b>：这三个值原本由 {@code @RefreshScope} + {@code @Value}
 * 消费。{@code @RefreshScope} 的刷新语义是「销毁旧实例 → 重建新实例」——
 * 对 {@code ImMessageService} 这类持有
 * {@code serialExecutor → ConversationLockManager → DistributedLockTemplate → RedissonClient}
 * 依赖链、且会在分布式锁临界区内执行 DB 事务的 Bean 来说，刷新瞬间到达的消息会被打断，
 * 甚至抛 {@code BeanCreationNotAllowedException}（上下文关闭中拒绝创建新 Bean）。
 * 这与 {@code ImNettyServer} 顶部注释「不可加 @RefreshScope」是同一类风险。</p>
 *
 * <p>改用 {@code @ConfigurationProperties} 后：Spring Cloud 的
 * {@code ConfigurationPropertiesRebinder} 在 RefreshEvent 时<b>就地回填</b>本 Bean 的字段，
 * <b>不销毁、不重建</b>消费方 Bean —— 既保住了热更新能力，又消除了刷新窗口。
 * 因此消费方必须<b>在用到的时刻</b>读取本对象（而不是在构造期把值拷进 final 字段）。</p>
 *
 * <p><b>与 {@link ImNettyProperties} 的分工</b>：{@code app.im.ws.max-content-length} 仍由
 * {@code ImNettyServer} 用 {@code @Value} 在启动期读取（Netty 通道初始化时一次性生效，
 * 热更新无意义），故<b>刻意不</b>在本类声明该字段。两个类共享 {@code app.im.ws} 前缀但字段不重叠，
 * 属有意为之：这里的字段放「运行期可变」，{@code ImNettyProperties} 放「启动期固定」。</p>
 */
@ConfigurationProperties(prefix = "app.im")
public class ImProperties {

    private final Message message = new Message();
    private final Ws ws = new Ws();

    public Message getMessage() {
        return message;
    }

    public Ws getWs() {
        return ws;
    }

    /** {@code app.im.message.*} —— 消息内容与撤回窗口。 */
    public static class Message {

        /** 单条文本消息最大长度（与 im_message.content_extracted 列宽不变量绑定）。 */
        private int maxTextLength = 5000;

        /** 允许撤回的时间窗口（秒）。 */
        private int recallWindowSeconds = 120;

        public int getMaxTextLength() {
            return maxTextLength;
        }

        public void setMaxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
        }

        public int getRecallWindowSeconds() {
            return recallWindowSeconds;
        }

        public void setRecallWindowSeconds(int recallWindowSeconds) {
            this.recallWindowSeconds = recallWindowSeconds;
        }
    }

    /** {@code app.im.ws.*} —— WebSocket 票据（不含 max-content-length，见类注释）。 */
    public static class Ws {

        /** 一次性 WebSocket 票据有效期（秒）。 */
        private int ticketTtlSeconds = 30;

        public int getTicketTtlSeconds() {
            return ticketTtlSeconds;
        }

        public void setTicketTtlSeconds(int ticketTtlSeconds) {
            this.ticketTtlSeconds = ticketTtlSeconds;
        }
    }
}
