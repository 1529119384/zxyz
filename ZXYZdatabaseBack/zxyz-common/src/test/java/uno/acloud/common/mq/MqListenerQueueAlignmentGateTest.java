package uno.acloud.common.mq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 门禁（ISSUE/54 §2.3）：全仓 {@code @RabbitListener} 与 Queue bean 名双向对齐。
 *
 * <h2>它防的是什么</h2>
 * 5 服务拓扑 0 测试（54 号 §2.3 已实证）：{@code @RabbitListener(queues = 常量)} 与
 * 各模块 {@code RabbitMqConfig} 的 {@code QueueBuilder.durable(常量)} 是两份独立维护的名字来源，
 * 之间没有任何编译期约束——
 * <ul>
 *   <li><b>拼写漂移</b>：listener 常量指到一个谁都没声明的队列名 ⇒ Spring AMQP 启动期被动声明
 *       一个无 DLX/无 TTL 的裸队列，DLQ 防线静默失效；</li>
 *   <li><b>孤儿队列</b>：Queue bean 声明了却没有任何 listener 消费（或消费方删了配置没删）⇒
 *       消息堆积 24h 后进 DLX，「以为有人消费」实际没人消费。</li>
 * </ul>
 * 本门禁静态解析两侧源码常量，双向 diff 必须为空；不拉 Spring 上下文、不连 RabbitMQ。
 *
 * <h2>怎么扫（零依赖，与 AloneRedisWiringContractTest 同款读盘方式）</h2>
 * 遍历根 pom 模块的 {@code src/main/java}：
 * <ul>
 *   <li>listener 侧：{@code @RabbitListener(queues = 常量)}，常量按
 *       「本文件 → 本模块 RabbitMqConfig → zxyz-common RabbitMqConstants」解析为字面量；</li>
 *   <li>bean 侧：各模块 {@code RabbitMqConfig.java} 里的
 *       {@code QueueBuilder.durable(常量)} 与 {@code new Queue(常量...)}。</li>
 * </ul>
 * 反空扫基数：2026-10-11 全仓 grep 实测 {@code @RabbitListener} <b>14 处</b>
 * （audit 2 / file 2 / share 2 / im 4 / project 3 / team 1），门禁以 ≥14 为下界——
 * 新增 listener 只会抬高基数；扫描面塌掉（解析失效/目录迁移）会响亮失败而非静默恒绿。
 * Queue bean 实测 18 个（业务 12 + DLQ 6），同样设下界。
 *
 * <h2>DLQ 豁免的方向性</h2>
 * {@code *.dlq} 队列的消费端是各服务 DlqConsumer（audit/im）或刻意无消费者（其余服务：
 * 死信保留待人工回放）——因此 {@code 值含 dlq 的 bean} 只参与「listener→bean」方向
 * （listener 指到不存在的队列仍要红），不参与「bean→listener」方向（否则 6 个 DLQ 全是误报）。
 */
class MqListenerQueueAlignmentGateTest {

    // ==========================================================================
    // 口径常量（2026-10-11 grep 实测）
    // ==========================================================================

    /** @RabbitListener 实测 14 处，只增不减的下界。 */
    private static final int MIN_LISTENERS = 14;
    /** Queue bean 实测 18 个（业务 12 + DLQ 6），只增不减的下界。 */
    private static final int MIN_QUEUE_BEANS = 18;

    // ==========================================================================
    // 检测模式
    // ==========================================================================

    /** @RabbitListener 的 queues 属性（常量引用形态；本仓全部 14 处均为该形态）。 */
    private static final Pattern LISTENER_QUEUES =
            Pattern.compile("@RabbitListener\\s*\\(\\s*queues\\s*=\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");

    /** 任何 @RabbitListener 出现（用于「有注解但没解析到 queues 属性」的响亮失败）。 */
    private static final Pattern ANY_LISTENER = Pattern.compile("@RabbitListener\\b");

    /** QueueBuilder.durable(常量)。 */
    private static final Pattern QUEUE_BUILDER_DURABLE =
            Pattern.compile("\\bQueueBuilder\\s*\\.\\s*durable\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
    /** new Queue(常量…)。 */
    private static final Pattern NEW_QUEUE =
            Pattern.compile("\\bnew\\s+Queue\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*[,)]");

    /** String 常量定义（本文件内解析用）。 */
    private static final Pattern STRING_CONSTANT =
            Pattern.compile("\\b(?:final\\s+)?String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"");

    /**
     * 注释剥离（<b>等长置换</b>）：把注释内容替换为同长度空格。
     * <p>必须保长——listener 计数与常量解析都在剥离后的文本上做，
     * 避免「javadoc 里提到 @RabbitListener」被误当成真注解造成响亮失败误报。</p>
     */
    private static String blankComments(String source) {
        String out = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
                .matcher(source).replaceAll(m -> " ".repeat(m.group().length()));
        return Pattern.compile("//[^\n]*").matcher(out).replaceAll(m -> " ".repeat(m.group().length()));
    }

    // ==========================================================================
    // 数据模型
    // ==========================================================================

    /** 一条 listener 引用：模块、来源文件、常量引用、解析出的物理队列名。 */
    record ListenerRef(String module, Path file, String constantRef, String resolvedQueue) {
    }

    /** 一个 Queue bean：模块、常量引用、解析出的物理队列名、是否 DLQ。 */
    record QueueBean(String module, String constantRef, String resolvedName, boolean dlq) {
    }

    // ==========================================================================
    // 主门禁
    // ==========================================================================

    @Test
    void listener与QueueBean双向diff必须为空且基数不缩水() {
        Path backendRoot = backendRoot();
        List<ListenerRef> listeners = scanListeners(backendRoot);
        List<QueueBean> queueBeans = scanQueueBeans(backendRoot);

        // ---- 反空扫：两侧解析基数都不能塌 ----
        if (listeners.size() < MIN_LISTENERS) {
            throw new AssertionError("listener 侧反空扫失败：只解析到 " + listeners.size()
                    + " 个 @RabbitListener(queues=…)（2026-10-11 实测 14，下界 " + MIN_LISTENERS + "）。"
                    + "要么扫描逻辑失效，要么 listener 被整体移除——两者都必须响亮失败。\n已解析：\n  - "
                    + joinListeners(listeners));
        }
        if (queueBeans.size() < MIN_QUEUE_BEANS) {
            throw new AssertionError("Queue bean 侧反空扫失败：只解析到 " + queueBeans.size()
                    + " 个（2026-10-11 实测 18，下界 " + MIN_QUEUE_BEANS + "）。\n已解析：\n  - "
                    + joinBeans(queueBeans));
        }

        // ---- 方向一：listener → bean（含 DLQ），指到不存在的队列 ⇒ 拼写漂移 ----
        Set<String> beanNames = new TreeSet<>();
        for (QueueBean bean : queueBeans) {
            beanNames.add(bean.resolvedName());
        }
        List<String> orphans = new ArrayList<>();
        for (ListenerRef listener : listeners) {
            if (!beanNames.contains(listener.resolvedQueue())) {
                orphans.add(listener.module + " 的 " + listener.file.getFileName()
                        + " → " + listener.constantRef + " = \"" + listener.resolvedQueue() + "\"");
            }
        }
        if (!orphans.isEmpty()) {
            throw new AssertionError("【@RabbitListener 指到无人声明的队列（拼写漂移/漏配 Queue bean）】\n  - "
                    + String.join("\n  - ", orphans)
                    + "\n后果：Spring AMQP 会在启动期被动声明该队列——没有 x-dead-letter-exchange、没有 TTL，"
                    + "毒消息不会被送进 DLQ，DLQ 防线静默失效。\n"
                    + "修法：listener 的 queues 常量必须指向本模块 RabbitMqConfig 声明的 Queue bean"
                    + "（或经 RabbitMqConstants 引用统一常量）。");
        }

        // ---- 方向二：bean → listener（DLQ 豁免），声明了没人消费的业务队列 ⇒ 孤儿队列 ----
        Map<String, Set<String>> listenedByValue = new TreeMap<>();
        for (ListenerRef listener : listeners) {
            listenedByValue.computeIfAbsent(listener.resolvedQueue(), k -> new TreeSet<>()).add(listener.module);
        }
        List<String> orphanQueues = new ArrayList<>();
        for (QueueBean bean : queueBeans) {
            if (bean.dlq()) {
                continue; // DLQ 的消费端是 DlqConsumer 或「刻意无消费者、死信保留待回放」，豁免
            }
            if (!listenedByValue.containsKey(bean.resolvedName())) {
                orphanQueues.add(bean.module + " 的 " + bean.constantRef + " = \"" + bean.resolvedName() + "\"");
            }
        }
        if (!orphanQueues.isEmpty()) {
            throw new AssertionError("【孤儿队列：声明了 Queue bean 却没有任何 @RabbitListener 消费】\n  - "
                    + String.join("\n  - ", orphanQueues)
                    + "\n后果：消息进队后无人消费，堆积满 TTL（24h）后全部进 DLX——「以为有人消费」实际没有，"
                    + "且堆积本身吃掉 broker 内存。\n"
                    + "修法：要么补 @RabbitListener，要么删掉 Queue/Binding 声明，"
                    + "要么在配置类注释里写清「刻意无消费者」并同步给本门禁加显式豁免。");
        }

        // ---- 附加漂移防线：同一物理队列名不得被两个模块各自声明（双份声明参数漂移） ----
        Map<String, Set<String>> declarersByValue = new TreeMap<>();
        for (QueueBean bean : queueBeans) {
            declarersByValue.computeIfAbsent(bean.resolvedName(), k -> new TreeSet<>()).add(bean.module);
        }
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : declarersByValue.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicated.add("\"" + entry.getKey() + "\" 被 " + entry.getValue() + " 同时声明");
            }
        }
        if (!duplicated.isEmpty()) {
            throw new AssertionError("【同一物理队列被多个模块重复声明】\n  - "
                    + String.join("\n  - ", duplicated)
                    + "\n两份声明只要 TTL/DLX 参数不一致，RabbitMQ 会拒绝后到者并使重启循环；"
                    + "队列声明必须单点归属（通常归消费方模块）。");
        }
    }

    // ==========================================================================
    // 扫描实现
    // ==========================================================================

    /** 扫全部模块的 @RabbitListener；有注解但解析不出 queues 属性 ⇒ 响亮失败（不留盲区）。 */
    private static List<ListenerRef> scanListeners(Path backendRoot) {
        List<ListenerRef> result = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : mainJavaFilesByModule(backendRoot).entrySet()) {
            String module = entry.getKey();
            Path moduleConfig = moduleRabbitMqConfig(backendRoot, module);
            String configSource = moduleConfig == null ? "" : blankComments(readText(moduleConfig));
            for (Path file : entry.getValue()) {
                String source = blankComments(readText(file));
                Matcher any = ANY_LISTENER.matcher(source);
                if (!any.find()) {
                    continue;
                }
                Matcher queues = LISTENER_QUEUES.matcher(source);
                int parsed = 0;
                while (queues.find()) {
                    parsed++;
                    String constantRef = queues.group(1);
                    result.add(new ListenerRef(module, file, constantRef,
                            resolveConstant(constantRef, source, configSource)));
                }
                if (parsed < countOccurrences(source, "@RabbitListener")) {
                    throw new AssertionError("存在本门禁解析不了的 @RabbitListener 形态: " + module
                            + " → " + file.getFileName()
                            + "（当前只识别 @RabbitListener(queues = 常量) 单参形态）。"
                            + "宁可响亮失败，也不要留下扫描盲区。");
                }
            }
        }
        return result;
    }

    /** 扫全部 RabbitMqConfig.java 的 Queue bean 声明。 */
    private static List<QueueBean> scanQueueBeans(Path backendRoot) {
        List<QueueBean> result = new ArrayList<>();
        for (String module : readModuleNames(backendRoot.resolve("pom.xml"))) {
            Path config = moduleRabbitMqConfig(backendRoot, module);
            if (config == null) {
                continue;
            }
            String source = blankComments(readText(config));
            Set<String> refs = new LinkedHashSet<>();
            collect(QUEUE_BUILDER_DURABLE.matcher(source), refs);
            collect(NEW_QUEUE.matcher(source), refs);
            for (String ref : refs) {
                String name = resolveConstant(ref, source, "");
                result.add(new QueueBean(module, ref, name, name.toLowerCase().contains("dlq")));
            }
        }
        return result;
    }

    /** 常量解析顺序：本文件 → 本模块 RabbitMqConfig → zxyz-common RabbitMqConstants。 */
    static String resolveConstant(String constantRef, String ownSource, String moduleConfigSource) {
        String value = lookupIn(constantRef, ownSource);
        if (value == null) {
            value = lookupIn(constantRef, moduleConfigSource);
        }
        if (value == null) {
            value = lookupIn(constantRef, COMMON_CONSTANTS_SOURCE);
        }
        if (value == null || value.isBlank()) {
            throw new AssertionError("无法解析常量 " + constantRef
                    + "（已尝试：本文件 → 本模块 RabbitMqConfig → zxyz-common RabbitMqConstants）。"
                    + "队列名常量必须落在三处之一，禁止内联字面量绕过本门禁。");
        }
        return value;
    }

    private static final String COMMON_CONSTANTS_SOURCE = readText(
            moduleDirStatic().getParent().resolve("zxyz-common")
                    .resolve("src").resolve("main").resolve("java").resolve("uno")
                    .resolve("acloud").resolve("common").resolve("RabbitMqConstants.java"));

    private static String lookupIn(String constantRef, String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String simpleName = constantRef.substring(constantRef.lastIndexOf('.') + 1);
        Matcher matcher = STRING_CONSTANT.matcher(source);
        while (matcher.find()) {
            if (simpleName.equals(matcher.group(1))) {
                return matcher.group(2);
            }
        }
        return null;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static Path moduleRabbitMqConfig(Path backendRoot, String module) {
        Path configDir = backendRoot.resolve(module).resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(configDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(configDir)) {
            return stream.filter(p -> p.getFileName().toString().equals("RabbitMqConfig.java"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("遍历 " + configDir + " 失败", e);
        }
    }

    private static Map<String, List<Path>> mainJavaFilesByModule(Path backendRoot) {
        Map<String, List<Path>> byModule = new LinkedHashMap<>();
        for (String module : readModuleNames(backendRoot.resolve("pom.xml"))) {
            Path mainJava = backendRoot.resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(mainJava)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(mainJava)) {
                List<Path> files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList();
                if (!files.isEmpty()) {
                    byModule.put(module, new ArrayList<>(files));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("遍历 " + mainJava + " 失败", e);
            }
        }
        if (byModule.isEmpty()) {
            throw new AssertionError("反空扫失败：没有读到任何模块的 main 源码 —— 路径反推失效");
        }
        return byModule;
    }

    private static String joinListeners(List<ListenerRef> listeners) {
        Set<String> lines = new TreeSet<>();
        for (ListenerRef listener : listeners) {
            lines.add(listener.module + " → " + listener.file.getFileName() + " → "
                    + listener.constantRef + " = \"" + listener.resolvedQueue() + "\"");
        }
        return String.join("\n  - ", lines);
    }

    private static String joinBeans(List<QueueBean> beans) {
        Set<String> lines = new TreeSet<>();
        for (QueueBean bean : beans) {
            lines.add(bean.module + " → " + bean.constantRef + " = \"" + bean.resolvedName() + "\""
                    + (bean.dlq() ? " [DLQ]" : ""));
        }
        return String.join("\n  - ", lines);
    }

    private static List<String> readModuleNames(Path rootPom) {
        Matcher matcher = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>").matcher(readText(rootPom));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("在 " + rootPom + " 里没有解析到任何 <module>");
        }
        return names;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file + " 失败", e);
        }
    }

    private static Path moduleDir() {
        return moduleDirStatic();
    }

    private static Path moduleDirStatic() {
        try {
            Path testClasses = Paths.get(MqListenerQueueAlignmentGateTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path dir = testClasses.getParent().getParent();
            if (dir == null || !Files.isRegularFile(dir.resolve("pom.xml"))) {
                throw new IllegalStateException("反推出的模块目录里没有 pom.xml: " + dir);
            }
            return dir;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("无法解析测试类的 class 输出位置", e);
        }
    }

    private static Path backendRoot() {
        Path root = moduleDir().getParent();
        if (root == null || !Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalStateException("反推出的后端根目录里没有 pom.xml: " + root);
        }
        return root;
    }

    // ==========================================================================
    // G1 自检：解析器的正反样本
    // ==========================================================================

    @Test
    void G1_解析自检_listener与bean形态及豁免方向() {
        // listener 形态解析
        Matcher m = LISTENER_QUEUES.matcher("@RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)");
        assertTrue(m.find() && "RabbitMqConfig.QUEUE_USER_EVENTS".equals(m.group(1)),
                "单参 queues 形态必须能解析");
        // 非目标形态不应误捕
        assertTrue(!LISTENER_QUEUES.matcher("@RabbitListener(bindings = @QueueBinding(...))").find(),
                "bindings 形态不得误捕（它会被 ANY_LISTENER 的响亮失败拦住，交给人工接规）");
        // bean 形态解析
        Matcher b1 = QUEUE_BUILDER_DURABLE.matcher("QueueBuilder.durable(QUEUE_USER_EVENTS)");
        assertTrue(b1.find() && "QUEUE_USER_EVENTS".equals(b1.group(1)), "QueueBuilder.durable 必须能解析");
        Matcher b2 = NEW_QUEUE.matcher("return new Queue(DLQ_QUEUE);");
        assertTrue(b2.find() && "DLQ_QUEUE".equals(b2.group(1)), "new Queue(单参) 必须能解析");
        // DLQ 豁免判定
        assertTrue(new QueueBean("x", "Y", "zxyz.share.dlq", true).dlq(), "dlq 值必须判为 DLQ");
        assertTrue(!new QueueBean("x", "Y", "zxyz.share.user-events", false).dlq(), "业务队列不得误判为 DLQ");
        // 常量解析链：本文件优先于模块 config；两处皆无 ⇒ 响亮失败
        String own = "static final String A = \"x.y\";";
        String config = "public static final String A = \"x.z\";";
        assertTrue("x.y".equals(resolveConstant("A", own, config)), "本文件常量优先于模块 config");
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> resolveConstant("B", own, config), "两处都找不到的常量必须响亮失败");
    }
}
