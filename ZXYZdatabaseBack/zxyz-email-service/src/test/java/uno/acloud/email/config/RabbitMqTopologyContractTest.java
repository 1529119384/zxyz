package uno.acloud.email.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 拓扑契约（ISSUE/54 §2.3-C3，email-service 实例）：
 * <b>钉死「零拓扑」事实</b>——email-service 目前不消费任何 RabbitMQ 队列、不声明任何
 * exchange/queue/binding（2026-10-14 从全模块源码实读钉死），邮件投递走 SMTP 直连。
 *
 * <h2>它防的是什么</h2>
 * 5 服务拓扑 0 测试（54 号实证）里，email-service 是唯一「连拓扑本身都不存在」的服务：
 * pom 仍声明 {@code spring-rabbit} 依赖（自动装配会建 RabbitConnectionFactory，
 * 但没有任何 @RabbitListener / RabbitMqConfig）。这个空态本身就是契约——
 * 若未来有人给 email 接 MQ 消费，必须让本测试随拓扑演进同步收紧，而不是静默长出一个
 * 无人审视、无 DLQ 兜底的消费者。本测试钉死：
 * <ul>
 *   <li>主源码树零 @RabbitListener（出现即消费者未过审，必须先补拓扑声明 + DLQ）；</li>
 *   <li>主源码树零拓扑声明（QueueBuilder/TopicExchange/Binding 三件套均不允许）；</li>
 *   <li>主源码树零 RabbitTemplate 发送面；</li>
 *   <li>主源码树零 amqp import（防止从旁路引入）。</li>
 * </ul>
 * 反向断言同样 <b>fail-closed</b>：源码树扫不到文件时直接失败，而不是把「扫空」
 * 误读成「干净」。
 */
class RabbitMqTopologyContractTest {

    private static final Pattern RABBIT_LISTENER =
            Pattern.compile("@RabbitListener\\s*\\(");
    private static final Pattern TOPOLOGY_DECL =
            Pattern.compile("\\bQueueBuilder\\s*\\.|\\bnew\\s+TopicExchange\\(|\\bBindingBuilder\\s*\\.");
    private static final Pattern RABBIT_TEMPLATE =
            Pattern.compile("\\bRabbitTemplate\\b");
    private static final Pattern AMQP_IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?org\\.springframework\\.amqp\\.", Pattern.MULTILINE);

    // ==========================================================================
    // 门禁
    // ==========================================================================

    @Test
    void 主源码树零rabbit监听_出现即消费者未过审() {
        List<String> hits = scanMainJava(RABBIT_LISTENER, "@RabbitListener");
        assertTrue(hits.isEmpty(),
                "email-service 契约为零 MQ 拓扑，但出现了 @RabbitListener: " + hits
                        + "——接 MQ 前必须先补 RabbitMqConfig 拓扑声明（含 x-dead-letter-exchange）"
                        + "与 DLQ 三件套，并同步本契约测试");
    }

    @Test
    void 主源码树零拓扑声明_queue_exchange_binding均不允许() {
        List<String> hits = scanMainJava(TOPOLOGY_DECL, "QueueBuilder/TopicExchange/BindingBuilder");
        assertTrue(hits.isEmpty(),
                "email-service 契约为零 MQ 拓扑，但出现了拓扑声明: " + hits
                        + "——先评审拓扑决策（队列名、路由键、DLX 归属）再落码，并同步本契约测试");
    }

    @Test
    void 主源码树零rabbit发送面() {
        List<String> hits = scanMainJava(RABBIT_TEMPLATE, "RabbitTemplate");
        assertTrue(hits.isEmpty(),
                "email-service 契约为零 MQ 拓扑，但出现了 RabbitTemplate 引用: " + hits
                        + "——若确需 MQ 出口，先评审拓扑并同步本契约测试");
    }

    @Test
    void 主源码树零amqp引用_防旁路引入() {
        List<String> hits = scanMainJava(AMQP_IMPORT, "org.springframework.amqp import");
        assertTrue(hits.isEmpty(),
                "email-service 主源码树出现 org.springframework.amqp 引用: " + hits
                        + "——这是拓扑外溢的第一步，先评审再落码");
    }

    // ==========================================================================
    // 扫描工具
    // ==========================================================================

    /**
     * 扫描全主源码树，返回命中文件清单。
     * fail-closed：树为空/不可达直接断言失败，不把「扫不到」当「干净」。
     */
    private static List<String> scanMainJava(Pattern pattern, String label) {
        Path root = moduleDir().resolve("src/main/java/uno/acloud/email");
        assertTrue(Files.isDirectory(root), "找不到主源码树: " + root + "——探针塌了，必须红");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(files.size() >= 10, "主源码树反空扫失败：仅 " + files.size() + " 个源文件");
            for (Path file : files) {
                String source = readText(file);
                if (pattern.matcher(source).find()) {
                    hits.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hits;
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
