package uno.acloud.im.config;

import org.junit.jupiter.api.Test;
import uno.acloud.common.RabbitMqConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 拓扑契约（ISSUE/54 §2.3-C3，im-service 实例）：
 * {@code RabbitMqConfig} 静态声明的 exchange/queue/binding 必须与消费者
 * {@code @RabbitListener} 的期望一致，DLQ 绑定必须在位。
 *
 * <h2>它防的是什么</h2>
 * 5 服务拓扑 0 测试（54 号实证）：拓扑由「配置类 + 消费者注解」两处独立维护，
 * 没有任何测试把两边对起来。这里用源码常量静态解析（不拉 Spring 上下文、不连 RabbitMQ），
 * 把 im-service 的拓扑事实钉死：
 * <ul>
 *   <li>业务队列 3 个：zxyz.im.file-events（file.resource.changed）+
 *       zxyz.im.team-events（team.#）+ zxyz.im.user-events（user.#）——
 *       后两者是本服务的关键拓扑决策：<b>宽匹配收 team 与 user 两个事件域的全部子键</b>，
 *       团队邀请、资料同步等新增子路由键零配置直达；退化成逐键精确枚举会让
 *       新事件静默丢失（无人监听的键不会报错）；</li>
 *   <li>每个业务队列都必须带 {@code x-dead-letter-exchange}（另有 x-message-ttl 兜底）；</li>
 *   <li>DLQ 三件套（DLX + 死信队列 + {@code #} 全路由绑定）在位；</li>
 *   <li>listener 侧 4 个 @RabbitListener（3 业务 + 1 DLQ 兜底消费者）必须恰好一一对应，
 *       反拼写漂移。</li>
 * </ul>
 * 若拓扑需要演进，先改 {@code RabbitMqConfig}，再同步本文件的常量断言——
 * 断言是拓扑的文档，不是它的枷锁。
 */
class RabbitMqTopologyContractTest {

    // ==========================================================================
    // im-service 拓扑期望（2026-10-14 从 RabbitMqConfig.java 实读钉死）
    // ==========================================================================

    /** 必须存在的队列名 → 路由键模式集合（消费事件面）。 */
    private static final Map<String, Set<String>> EXPECTED_QUEUE_BINDINGS = Map.of(
            RabbitMqConfig.QUEUE, Set.of(RabbitMqConstants.ROUTING_KEY_FILE_RESOURCE_CHANGED),
            RabbitMqConfig.QUEUE_TEAM_EVENTS, Set.of(RabbitMqConfig.ROUTING_KEY_TEAM),
            RabbitMqConfig.QUEUE_USER_EVENTS, Set.of(RabbitMqConfig.ROUTING_KEY_USER));

    /** 必须存在的 exchange。 */
    private static final Set<String> EXPECTED_EXCHANGES = Set.of(
            RabbitMqConstants.EXCHANGE, RabbitMqConstants.DLX_EXCHANGE);

    private static final String EXPECTED_DLQ = "zxyz.im.dlq";
    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** 消费端预期：@RabbitListener 必须恰好按此数量消费各队列（3 业务 + 1 DLQ）。 */
    private static final Map<String, Integer> EXPECTED_LISTENERS_PER_QUEUE = Map.of(
            RabbitMqConfig.QUEUE, 1,
            RabbitMqConfig.QUEUE_TEAM_EVENTS, 1,
            RabbitMqConfig.QUEUE_USER_EVENTS, 1,
            RabbitMqConfig.DLQ_QUEUE, 1);

    // ==========================================================================
    // 解析模式
    // ==========================================================================

    private static final Pattern LISTENER_QUEUES =
            Pattern.compile("@RabbitListener\\s*\\(\\s*queues\\s*=\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
    private static final Pattern NEW_TOPIC_EXCHANGE =
            Pattern.compile("\\bnew\\s+TopicExchange\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
    private static final Pattern QUEUE_BUILDER_DURABLE =
            Pattern.compile("\\bQueueBuilder\\s*\\.\\s*durable\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
    private static final Pattern BINDING_ROUTING_KEY =
            Pattern.compile("\\.with\\(\\s*([A-Za-z0-9_.#*\"+ ]+?)\\s*\\)");
    private static final Pattern STRING_CONSTANT =
            Pattern.compile("\\b(?:final\\s+)?String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern WITH_ARGUMENT =
            Pattern.compile("\\.withArgument\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");

    // ==========================================================================
    // 门禁
    // ==========================================================================

    @Test
    void exchange与queue声明面必须与期望一致且反空扫不塌() {
        String config = readConfig();

        Set<String> exchangeRefs = new TreeSet<>();
        collect(NEW_TOPIC_EXCHANGE.matcher(config), exchangeRefs);
        Set<String> exchanges = resolveAll(exchangeRefs, config);
        Set<String> queueRefs = new TreeSet<>();
        collect(QUEUE_BUILDER_DURABLE.matcher(config), queueRefs);
        Set<String> declaredQueues = resolveAll(queueRefs, config);

        assertTrue(exchanges.size() >= 2, "反空扫：exchange 解析数塌了（实得 " + exchanges + "）");
        assertTrue(declaredQueues.size() >= 4, "反空扫：queue 解析数塌了（业务 3 + DLQ 1，实得 " + declaredQueues + "）");

        // 期望侧的 RabbitMqConfig.QUEUE_* 是编译期常量，测试源码里已内联为字面量；
        // 捕获侧（源码里的常量引用）才需要 resolve。
        TreeSet<String> missingExchanges = new TreeSet<>(EXPECTED_EXCHANGES);
        missingExchanges.removeAll(exchanges);
        assertTrue(missingExchanges.isEmpty(),
                "缺少 exchange 声明: " + missingExchanges + "（实得 " + exchanges + "）"
                        + "—— zxyz.topic 是全仓 MQ 的总线，zxyz.dlx 是毒消息唯一去处，缺一即拓扑断裂");

        TreeSet<String> missingQueues = new TreeSet<>();
        for (String queueName : EXPECTED_QUEUE_BINDINGS.keySet()) {
            if (!declaredQueues.contains(queueName)) {
                missingQueues.add(queueName);
            }
        }
        assertTrue(missingQueues.isEmpty(), "缺少 Queue bean 声明: " + missingQueues + "（实得 " + declaredQueues + "）");
    }

    @Test
    void 每个业务队列的binding必须覆盖消费事件面() {
        String config = readConfig();
        List<String> bindingKeys = new ArrayList<>();
        Matcher matcher = BINDING_ROUTING_KEY.matcher(config);
        while (matcher.find()) {
            bindingKeys.add(resolveOrLiteral(matcher.group(1), config));
        }
        // 业务 binding 3 + DLQ '#' 1 = 4
        assertTrue(bindingKeys.size() >= 4, "反空扫：binding 解析数塌了（实得 " + bindingKeys + "）");

        for (Map.Entry<String, Set<String>> entry : EXPECTED_QUEUE_BINDINGS.entrySet()) {
            for (String routingKey : entry.getValue()) {
                assertTrue(bindingKeys.contains(routingKey),
                        "队列 " + entry.getKey() + " 缺少路由键 \"" + routingKey + "\" 的 binding（实有 " + bindingKeys + "）。"
                                + "少了它，对应事件根本到不了本服务——消费端静默饿死。");
            }
        }
        // 关键拓扑决策：team.# / user.# 宽匹配收整个事件域，im 对 team/user 事件
        // （团队邀请、资料同步等）只认前缀不认具体键；退化成逐键精确枚举是拓扑回归——
        // 新增子键时无人监听、无报错，消息静默丢失。
        assertTrue(bindingKeys.contains(RabbitMqConfig.ROUTING_KEY_TEAM)
                        && bindingKeys.contains(RabbitMqConfig.ROUTING_KEY_USER),
                "team.# / user.# 宽匹配 binding 是 im-service 的既定拓扑（收整个事件域，新增子键零配置直达），"
                        + "退化成逐键精确枚举会让新事件静默丢失");
    }

    @Test
    void 每个业务队列必须配置死信路由_毒消息才有去处() {
        String config = readConfig();
        for (String queueName : EXPECTED_QUEUE_BINDINGS.keySet()) {
            String segment = queueBuilderSegment(config, queueName);
            assertTrue(segment != null, "找不到队列 " + queueName + " 的 QueueBuilder 段");
            // 配置源码里 DLX 参数名可能写常量名（X_DEAD_LETTER_EXCHANGE）而非字面量，两形态都要认
            boolean hasDlx = segment.contains(X_DEAD_LETTER_EXCHANGE)
                    || containsResolved(WITH_ARGUMENT.matcher(segment), X_DEAD_LETTER_EXCHANGE, config);
            assertTrue(hasDlx,
                    "队列 " + queueName + " 缺少 " + X_DEAD_LETTER_EXCHANGE + " 参数——"
                            + "没有它，反序列化失败/处理异常的毒消息会无限重投，永不进 DLQ");
        }
    }

    @Test
    void dlq三件套必须在位_死信可落可回放() {
        String config = readConfig();
        // 死信队列存在
        assertTrue(config.contains(EXPECTED_DLQ) || config.contains("DLQ_QUEUE"),
                "找不到死信队列 " + EXPECTED_DLQ + " 的声明（直接或经 DLQ_QUEUE 常量）");
        // DLX 上的 # 全路由绑定
        List<String> bindingKeys = new ArrayList<>();
        Matcher matcher = BINDING_ROUTING_KEY.matcher(config);
        while (matcher.find()) {
            bindingKeys.add(resolveOrLiteral(matcher.group(1), config));
        }
        assertTrue(bindingKeys.contains("#"),
                "死信队列缺少 # 全路由 binding——DLX 只收不投，死信落进去就再也无法定位/回放");
        // 死信队列自身必须是普通 durable 队列
        Set<String> queueRefs = new TreeSet<>();
        collect(QUEUE_BUILDER_DURABLE.matcher(config), queueRefs);
        assertTrue(resolveAll(queueRefs, config).contains(resolve("DLQ_QUEUE", config)),
                "DLQ 必须经 QueueBuilder.durable 声明（durable 形态，重启不丢）");
    }

    @Test
    void 消费端listener期望与拓扑声明对齐() {
        String config = readConfig();
        // 源码面：本模块 infrastructure/mq 包下全部 @RabbitListener 的 queues → 物理队列名
        Map<String, Integer> actualListenersPerQueue = new LinkedHashMap<>();
        int listenerCount = 0;
        for (Path file : mqMainJavaFiles()) {
            String source = readText(file);
            Matcher matcher = LISTENER_QUEUES.matcher(source);
            while (matcher.find()) {
                listenerCount++;
                String resolved = resolve(matcher.group(1), source + "\n" + config);
                actualListenersPerQueue.merge(resolved, 1, Integer::sum);
            }
        }
        assertTrue(listenerCount == 4,
                "im-service 应有 4 个 @RabbitListener（3 业务 + 1 DLQ 兜底消费者，实得 " + listenerCount + "）");

        for (String queue : actualListenersPerQueue.keySet()) {
            assertTrue(EXPECTED_LISTENERS_PER_QUEUE.containsKey(queue),
                    "listener 消费的队列 " + queue + " 不在拓扑期望内——要么拓扑漏声明（拼写漂移），"
                            + "要么本文件需要随拓扑演进同步更新");
        }
        assertTrue(actualListenersPerQueue.equals(EXPECTED_LISTENERS_PER_QUEUE),
                "listener↔queue 映射漂移：期望 " + EXPECTED_LISTENERS_PER_QUEUE + "，实得 " + actualListenersPerQueue);
    }

    // ==========================================================================
    // 解析工具
    // ==========================================================================

    private static Set<String> resolveAll(Set<String> refs, String source) {
        Set<String> resolved = new TreeSet<>();
        for (String ref : refs) {
            resolved.add(resolve(ref, source));
        }
        return resolved;
    }

    /** 常量解析：本文件 String 常量表 → zxyz-common RabbitMqConstants（随源码走，不写死字面量）。 */
    private static String resolve(String ref, String source) {
        String simple = ref.substring(ref.lastIndexOf('.') + 1);
        Matcher matcher = STRING_CONSTANT.matcher(source);
        while (matcher.find()) {
            if (simple.equals(matcher.group(1))) {
                return matcher.group(2);
            }
        }
        matcher = STRING_CONSTANT.matcher(readText(commonConstantsPath()));
        while (matcher.find()) {
            if (simple.equals(matcher.group(1))) {
                return matcher.group(2);
            }
        }
        throw new AssertionError("无法解析常量 " + ref + "（本文件与 zxyz-common RabbitMqConstants 均未命中）");
    }

    private static String resolveOrLiteral(String token, String source) {
        String trimmed = token.trim();
        if (trimmed.startsWith("\"")) {
            return trimmed.replace("\"", "");
        }
        return resolve(trimmed, source);
    }

    private static boolean containsResolved(Matcher matcher, String argumentName, String source) {
        while (matcher.find()) {
            if (argumentName.equals(resolveOrLiteral(matcher.group(1), source))) {
                return true;
            }
        }
        return false;
    }

    /** 截取「durable(队列名) … build()」的链段（粗粒度即可，够判断 withArgument 是否在链上）。 */
    private static String queueBuilderSegment(String config, String queueName) {
        int start = config.indexOf("durable(" + queueName + ")");
        if (start < 0) {
            Matcher matcher = QUEUE_BUILDER_DURABLE.matcher(config);
            while (matcher.find()) {
                if (queueName.equals(resolve(matcher.group(1), config))) {
                    start = matcher.start();
                    break;
                }
            }
        }
        if (start < 0) {
            return null;
        }
        int end = config.indexOf("build()", start);
        return end > start ? config.substring(start, end) : config.substring(start);
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private static String readConfig() {
        return readText(configPath());
    }

    private static Path configPath() {
        try {
            return moduleDir().resolve("src/main/java/uno/acloud/im/config/RabbitMqConfig.java");
        } catch (Exception e) {
            throw new IllegalStateException("无法定位 RabbitMqConfig.java", e);
        }
    }

    private static List<Path> mqMainJavaFiles() {
        Path mqDir = moduleDir().resolve("src/main/java/uno/acloud/im/infrastructure/mq");
        if (!Files.isDirectory(mqDir)) {
            throw new AssertionError("找不到 infrastructure/mq 目录: " + mqDir);
        }
        try (Stream<Path> stream = Files.walk(mqDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(!files.isEmpty(), "infrastructure/mq 目录反空扫失败：0 个源文件");
            return new ArrayList<>(files);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path commonConstantsPath() {
        return moduleDir().getParent()
                .resolve("zxyz-common/src/main/java/uno/acloud/common/RabbitMqConstants.java");
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file + " 失败", e);
        }
    }

    private static Path moduleDir() {
        try {
            Path testClasses = Paths.get(RabbitMqTopologyContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path dir = testClasses.getParent().getParent();
            if (dir == null || !Files.isRegularFile(dir.resolve("pom.xml"))) {
                throw new IllegalStateException("反推出的模块目录里没有 pom.xml: " + dir);
            }
            return dir;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("无法解析测试类的 class 输出位置", e);
        }
    }
}
