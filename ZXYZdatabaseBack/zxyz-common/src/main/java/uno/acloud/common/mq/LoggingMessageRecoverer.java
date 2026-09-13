package uno.acloud.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import uno.acloud.common.util.LogSanitizer;

/**
 * 「只加日志、不改投递语义」的 {@link MessageRecoverer}（审计 L7 修复）。
 *
 * <h2>为什么需要它</h2>
 * <p>本仓库的消费重试配置在 {@code application-common.yml} 的
 * {@code spring.rabbitmq.listener.simple.retry}（{@code enabled=true}、默认 3 次、指数退避），
 * 配套 {@code default-requeue-rejected: false}。这条配置链在「重试耗尽」那一刻是<strong>静默</strong>的：
 * 框架把消息 reject 掉、由业务队列的 {@code x-dead-letter-exchange: zxyz.dlx} 送进
 * {@code zxyz.<svc>.dlq}，而项目自身<strong>一行日志都不打</strong>。
 * 更麻烦的是 file / project / share / team 四个服务的 DLQ 队列<strong>根本没有消费者</strong>，
 * 死信只会静默堆积 —— 全链路连一个可观测信号都没有。</p>
 *
 * <h2>它做了什么、没做什么</h2>
 * <ul>
 *   <li><strong>做</strong>：在重试耗尽那一刻打一条 ERROR，带上服务名、队列、exchange、
 *       routingKey、messageId、是否重投、重试上限、payload 长度/摘要/清洗预览、死信头、异常堆栈。</li>
 *   <li><strong>没做</strong>：不改投递语义。末尾仍委托给 Spring AMQP 自带的
 *       {@link RejectAndDontRequeueRecoverer}：它抛出 {@code ListenerExecutionFailedException}，
 *       其 cause 为 {@code AmqpRejectAndDontRequeueException}，容器据此判定「拒绝且不重新入队」⇒
 *       消息照旧由队列的 {@code x-dead-letter-exchange} 送进 {@code zxyz.dlx}。
 *       本类只是「在同一个决策点上补一条 ERROR 日志」。</li>
 *   <li><strong>一个要知道的事实</strong>：默认的 {@code RejectAndDontRequeueRecoverer} 自己
 *       也会打一条 WARN（{@code "Retries exhausted for message " + message}），但那条日志
 *       <strong>没有服务名 / 队列 / 死亡次数，而且把整条 payload 原样写进日志</strong>。
 *       本类替换掉它之后，排障时会看到「本类的 ERROR + 那条 WARN」两条 ——
 *       第三方那条 WARN 未做抑制（压制框架日志属于更大范围的配置变更，不在 L7 范围内）。</li>
 * </ul>
 *
 * <h2>它是怎么被装上的</h2>
 * <p>Spring Boot 的 {@code RabbitAnnotationDrivenConfiguration} 用
 * {@code ObjectProvider<MessageRecoverer>} + {@code getIfUnique()} 取 recoverer，
 * {@code AbstractRabbitListenerContainerFactoryConfigurer#configure} 里是
 * {@code recoverer = (this.messageRecoverer != null) ? this.messageRecoverer : new RejectAndDontRequeueRecoverer()}。
 * 也就是说：<strong>容器里恰有一个 {@code MessageRecoverer} Bean 时，它就会替换掉默认实现</strong>。
 * 装配入口见 {@code MqRecovererAutoConfiguration}。</p>
 *
 * <p><b>注意</b>：正因为是 {@code getIfUnique()}，各服务<strong>不要</strong>再自行定义第二个
 * {@code MessageRecoverer} Bean —— 一旦出现两个，Boot 会判定「不唯一」而整体放弃，
 * 静默退回默认实现，本类就再也不生效了。</p>
 */
@Slf4j
public class LoggingMessageRecoverer implements MessageRecoverer {

    /**
     * 与容器默认 recoverer 行为一致的委托目标。本类只在其前面加一条日志，
     * <strong>不要</strong>换成任何「重新发布 / 入库 / 丢弃」的实现 —— 那会改变投递语义。
     */
    private final MessageRecoverer delegate = new RejectAndDontRequeueRecoverer();

    /** 来源服务名（spring.application.name），用于在集中式日志里区分是谁的死信。 */
    private final String serviceName;

    /** 重试上限，来自 spring.rabbitmq.listener.simple.retry.max-attempts。 */
    private final int retryMaxAttempts;

    public LoggingMessageRecoverer(String serviceName, int retryMaxAttempts) {
        this.serviceName = serviceName;
        this.retryMaxAttempts = retryMaxAttempts;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        logExhausted(message, cause);
        // 保持与容器默认实现完全一致的语义：拒绝且不重新入队 ⇒ 交给队列的 DLX。
        delegate.recover(message, cause);
    }

    private void logExhausted(Message message, Throwable cause) {
        byte[] body = (message == null) ? null : message.getBody();
        MessageProperties props = (message == null) ? null : message.getMessageProperties();
        log.error("MQ 消息重试耗尽，即将进入死信队列（这是 L7 口径下唯一的可观测信号，"
                        + "请据此人工判断是否补偿）: service={}, queue={}, exchange={}, routingKey={}, "
                        + "messageId={}, redelivered={}, retryMaxAttempts={}, payloadLength={}, "
                        + "payloadSha256_12={}, payloadPreview={}, death={}, causeType={}, causeMessage={}",
                serviceName,
                property(props, MessageProperties::getConsumerQueue),
                property(props, MessageProperties::getReceivedExchange),
                property(props, MessageProperties::getReceivedRoutingKey),
                property(props, MessageProperties::getMessageId),
                booleanProperty(props, MessageProperties::isRedelivered),
                retryMaxAttempts,
                MqMessageDiagnostics.lengthOf(body),
                MqMessageDiagnostics.digest(body),
                MqMessageDiagnostics.preview(body),
                MqMessageDiagnostics.describeDeath(props == null ? null : props.getHeaders()),
                cause == null ? "n/a" : cause.getClass().getSimpleName(),
                LogSanitizer.sanitize(cause),
                // 最后一个参数是 Throwable 且没有对应占位符 ⇒ SLF4J 会打印完整堆栈。
                cause);
    }

    /**
     * 防御性读取 {@link MessageProperties} 的某个字段：{@code props} 为 null（消息本身为空）
     * 时返回 "n/a"。诊断代码运行在故障现场，绝不能因为读属性而抛出二次异常。
     */
    private static String property(MessageProperties props, java.util.function.Function<MessageProperties, String> getter) {
        if (props == null) {
            return "n/a";
        }
        try {
            String value = getter.apply(props);
            return value == null ? "n/a" : value;
        } catch (Exception e) {
            return "n/a";
        }
    }

    /**
     * 防御性读取 {@link MessageProperties} 的布尔字段，返回 {@link Object} 以便在日志里
     * 区分「未设置」与「false」。
     *
     * <p><b>为什么要单独写一个</b>：{@code MessageProperties#isRedelivered()} 返回的是
     * <strong>装箱的 {@link Boolean}</strong>，在「生产者补发 / 消息头被反序列化丢弃」等场景下
     * 可能为 {@code null}。此时若写成 {@code props != null && props.isRedelivered()}，
     * Java 会对 {@code null} 做<strong>自动拆箱</strong>并抛 {@code NullPointerException} ——
     * 诊断代码正好运行在故障现场，抛二次异常会把「消息为什么死信」这个真正的问题掩盖掉。
     * 单测 {@code LoggingMessageRecovererTest} 已覆盖该场景（redelivered 未设置时须打日志且不抛）。</p>
     */
    private static Object booleanProperty(MessageProperties props,
                                          java.util.function.Function<MessageProperties, Boolean> getter) {
        if (props == null) {
            return "n/a";
        }
        try {
            Boolean value = getter.apply(props);
            return value == null ? "n/a" : value;
        } catch (Exception e) {
            return "n/a";
        }
    }
}
