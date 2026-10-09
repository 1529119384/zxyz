package uno.acloud.satoken;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sa-Token 会话存储「alone-redis 接线」契约门禁。
 *
 * <h2>它防的是什么（一个已查实的真实缺陷）</h2>
 * 目标是把 Sa-Token 会话从「各服务自己的业务库」迁到统一的 {@code db9}，做法是**每个自持会话的
 * 服务**引入 {@code sa-token-alone-redis-by-spring-boot4}（Boot 3 时代坐标为
 * {@code sa-token-alone-redis}，Boot 4 迁移时跟平，见 {@link #ARTIFACT_ALONE_REDIS}）并在共享配置里写
 * {@code sa-token.alone-redis.*}（本轮落地时是 9 个；门禁不写死这个数字，见下方「设计原则」）。
 * 但 <b>{@code sa-token-alone-redis-by-spring-boot4} 1.46.0 在 {@code pattern: single} 路径会无条件执行
 * {@code new org.apache.commons.pool2.impl.GenericObjectPoolConfig()}</b>；而 {@code commons-pool2}
 * 在本 reactor 里<b>只有 {@code zxyz-gateway} 一个模块声明过</b>：
 * <ul>
 *   <li>{@code alone-redis} 自己的 pom 里 {@code sa-token-redis-template} / {@code commons-pool2}
 *       都是 {@code <optional>true</optional>} ⇒ <b>不传递</b>；</li>
 *   <li>{@code io.lettuce:lettuce-core} 对 {@code commons-pool2} 同样是 {@code optional} ⇒ 不传递。</li>
 * </ul>
 * ⇒ 只加 {@code sa-token-alone-redis-by-spring-boot4} 而不补 {@code commons-pool2}，其余 8 个服务会在<b>启动时</b>
 * 抛 {@code NoClassDefFoundError}。这类「漏配一条依赖」在编译期与单测里都发现不了 ——
 * 本门禁把它变成常驻断言。
 *
 * <h2>⚠️ 本改造的副作用（写在这里以免被忽略）</h2>
 * {@code LettuceConnectionConfiguration.createBuilder()} 走 {@code isPoolEnabled()}，其实现是
 * {@code enabled != null ? enabled : COMMONS_POOL2_AVAILABLE}。也就是说<b>补上 pool2 会让主 Redis
 * 客户端从「无池」变成「有池」</b> —— {@code application-common.yml} 里
 * {@code spring.data.redis.lettuce.pool.max-active: 8} 等配置会从「写了但不起作用」变成<b>真生效</b>。
 * 这是有意接受的副作用，但必须在 review 时被看见，故在此显式点明。
 *
 * <h2>为什么断言写在 {@code zxyz-common}</h2>
 * 本门禁只读盘上的 {@code pom.xml}、{@code application-common.yml} 与 {@code nacos-config/} 文本，
 * <b>不加载任何插件的类</b>，因此放在依赖最少的 {@code zxyz-common} 即可运行，不需要全量 reactor 构建。
 * （该制品是否已下载到本地仓库与本门禁无关：首次落地时它确实缺失，测试仍可编译并运行。）
 *
 * <h2>YAML 怎么解析的（为什么可靠）</h2>
 * 不引第三方 YAML 库，改为<b>自带的缩进感知扫描</b>：按缩进维护一个 section 栈，把每个标量解析成
 * <b>点分路径</b>（如 {@code sa-token.alone-redis.database}）。因此 {@code sa-token.redis.prefix} 与
 * {@code sa-token.alone-redis.database} <b>不会互相串味</b> —— 这是「点分路径」而非「全文 contains」
 * 的关键区别（{@code G3} 用例专门证明这一点，含「被注释掉的键不得进入结果」）。
 *
 * <p><b>为什么不用 snakeyaml（已实测，非猜测）</b>：{@code org.yaml:snakeyaml:2.4} 确实<b>在</b>
 * test classpath 上 —— 但它是经 {@code org.springframework.boot:spring-boot-starter-validation}
 * 传递进来的，而该 starter 在本模块是 {@code <scope>provided</scope>}。也就是说这份可用性是
 * <b>一个 provided 依赖的偶然产物</b>：谁把那个 scope 一改，本门禁就会「编译失败」而不是「断言失败」。
 * 门禁自身不该依赖这种脆弱传递链，故选择零依赖实现，并对真实
 * {@code application-common.yml} 实测（解析出 90 个键，层级与占位符均正确）。</p>
 *
 * <h2>反空扫与自检</h2>
 * 每个门禁都有「扫描结果非空」的前置断言；另有 {@code G1/G2/G3} 用例把检查逻辑喂给
 * 已知违规/已知合规/已知识别盲区样本，证明它<b>真的会失败</b>且不会误报 ——
 * 没有这一条，「通过」只能说明它没报错，不能说明它会报错。
 *
 * <h2>设计原则：表达不变量，而不是枚举快照</h2>
 * 本门禁刻意<b>不</b>断言「声明 {@code sa-token-redis-template} 的模块恰好是某 N 个」。
 * 那样的白名单会把**正常演进**（新增服务）判成违规，逼开发者「为了加服务而改测试」——
 * 而本仓的教训正是「为了让测试变绿而改测试」会制造假绿灯（守卫被改掉且无人察觉）。
 *
 * <h2>★ 会话接线不变量（权威表述）</h2>
 * <blockquote>
 * <b>每一个可部署服务模块，必须显式二选一</b>：<br>
 * ① <b>声明完整三件套</b> —— {@code sa-token-redis-template} + {@code sa-token-alone-redis-by-spring-boot4}
 *    + {@code commons-pool2}（⇒ 参与会话共享，会话落 db9）；<br>
 * ② <b>或登记在显式豁免名单里</b>（⇒ 书面声明自己永不参与会话共享）。
 * </blockquote>
 * 其中「可部署服务模块」的判据 = 存在 {@code src/main/resources/application.yml}。
 * <p>配套的三条结构性约束：</p>
 * <ol>
 *   <li><b>正向全覆盖（门禁 H）</b>：可部署服务必须满足上面①或②；两者皆不满足 ⇒ 红。
 *       这条堵的是「新服务忘了引依赖 ⇒ 静默落内存 DAO」的空档。</li>
 *   <li><b>豁免自净（门禁 H 规则 2）</b>：在豁免名单里却已三件套齐全 ⇒ 红（陈旧豁免）。</li>
 *   <li><b>豁免有效（门禁 H 规则 3）</b>：豁免名单含不存在或非可部署的模块名 ⇒ 红（防拼写失效）。</li>
 * </ol>
 * <p>在「可部署服务」这一层之上，另有两条只针对**已接线模块**的规则：</p>
 * <ul>
 *   <li><b>正向（B/C 项）</b>：凡声明了 {@code redis-template} 的模块，必须同时具备
 *       {@code alone-redis} + {@code commons-pool2}。该规则**天然对新模块生效**，无需登记模块名。</li>
 *   <li><b>反向（A 项）</b>：{@code zxyz-audit-service} / {@code zxyz-common} / {@code zxyz-starter}
 *       这三个**架构例外**模块不得混入（详见 {@link #FORBIDDEN_REDIS_TEMPLATE_MODULES}）。</li>
 * </ul>
 * 于是「新增一个守规矩的服务」自动通过，「新增一个漏接依赖的服务」被 H（缺三件套）或 B/C（接了
 * {@code redis-template} 但漏接其余）点名拦下，「例外模块混入」被 A 点名拦下 ——
 * 三者都<b>不需要修改本测试文件</b>。
 *
 * <p><b>演进史（供后人判断这道门禁的鉴别力，勿当作「当前状态」）</b>：
 * 本测试按「测试先行」落地 —— 首次提交时 pom/yml <b>尚未</b>接线，B/C/D/E 四条断言<b>确实为红</b>
 * （实测 `Tests run: 9, Failures: 4`，报错逐一点名缺失模块与缺失键）。
 * 接线完成后（父 pom 加版本管理、各服务加依赖、共享配置加 `alone-redis` 段）转为
 * `Tests run: 9, Failures: 0`；后续加固依次加入 nacos 落点断言（E2）、可扩展性改造
 * （A 由枚举快照改为禁入不变量）与全覆盖断言（H），现为 11 个用例。
 * 这条「先红后绿」的演进可作回归基线：
 * 若某次改动让本测试变红，说明接线被破坏，而不是「门禁本来就是红的」。</p>
 */
class AloneRedisWiringContractTest {

    // ==========================================================================
    // 期望值（口径来源见类注释）
    // ==========================================================================

    /**
     * <b>禁入集合</b>：这些模块**不得**声明 {@code sa-token-redis-template}。这是**不可扩张的架构例外**，
     * 不是「当前有哪些模块」的快照 —— 二者是本门禁的核心区别。
     *
     * <h2>为什么用「禁入」而不是「白名单枚举 9 个」</h2>
     * 白名单式断言（「声明者为且仅为这 9 个」）会把**正常演进**判成违规：本项目必然要加服务，
     * 新服务只要提供 HTTP 登录态就得引 {@code sa-token-redis-template} —— 那样门禁立刻变红，
     * 开发者必须先改测试才能加服务。而「为了让测试变绿而改测试」正是本仓反复吃过亏的模式
     * （改完测试，守卫就失效了，且没人会发现）。
     * <p>本门禁改为表达**不变量**：「任何自持会话的模块都必须三件套齐全（B/C 项）」+
     * 「这三个例外模块不得混入（A 项）」。于是：</p>
     * <ul>
     *   <li>新增一个**按规矩接线**的服务 ⇒ A 自动通过，<b>无需改任何测试</b>；</li>
     *   <li>新增一个**漏接 alone-redis / commons-pool2** 的服务 ⇒ B/C 变红并点名（防静默退化）；</li>
     *   <li>这三个例外模块混入 ⇒ A 变红并点名。</li>
     * </ul>
     *
     * <h2>三个例外各自为什么必须是例外</h2>
     * <ul>
     *   <li>{@code zxyz-audit-service}：纯 MQ 消费者，无 HTTP 端点、0 个会话调用点。它混入后会得到
     *       「半截 Sa-Token」形态（有 DAO 但无人用），而一旦将来加了带 {@code @Log} 的 Controller，
     *       会话就会静默落到内存 DAO —— 与它现在「刻意不引」的防御姿态相反。</li>
     *   <li>{@code zxyz-common} / {@code zxyz-starter}：**库模块**，不独立启动，DAO 由宿主服务提供。
     *       库模块若自持 DAO 配置，会把存储实现强加给所有宿主，破坏「common 只定义接口
     *       （{@code AuthServicePort}）、宿主决定实现」的分层。</li>
     * </ul>
     * <p><b>⚠️ 扩张本集合需要架构评审</b>：往这里加一个模块，等于宣布「该模块永久不参与会话共享」。
     * 若只是「暂时不接」，请让它走 B/C 的正向路径，而不是塞进这个例外名单。</p>
     */
    private static final Set<String> FORBIDDEN_REDIS_TEMPLATE_MODULES = Set.of(
            "zxyz-audit-service",
            "zxyz-common",
            "zxyz-starter");

    /**
     * <b>显式豁免名单</b>：这些**可部署服务**刻意不参与 Sa-Token 会话共享，因此允许三件套为空。
     *
     * <p>它是门禁 H 的「二选一」的另一半：每个可部署服务**要么**三件套齐全，**要么**在这里登记。
     * 没有这个名单，「服务忘了引依赖」与「服务刻意不用会话」在静态扫描下**长得一模一样** ——
     * 门禁将无法既拦住前者、又不误伤后者。</p>
     *
     * <h2>⚠️ 这个名单必须保持精确，它有两个反向断言在盯着</h2>
     * <ul>
     *   <li><b>陈旧豁免</b>：名单里的模块若**已**三件套齐全 ⇒ 门禁红。说明它已经开始参与会话共享，
     *       必须从名单移除 —— 否则名单会慢慢变成「关于现状的谎言」，而文档性谎言比没有文档更糟。</li>
     *   <li><b>无效条目</b>：名单里若出现不存在、或不是可部署服务的模块名 ⇒ 门禁红。
     *       防的是拼写错误（如 {@code zxyz-audit} 少个后缀）让规则静默失效。</li>
     * </ul>
     *
     * <h2>为什么 audit-service 是唯一合法的豁免</h2>
     * 它是纯 MQ 消费者：无 HTTP 端点、main 代码 0 个会话调用点、不提供登录态。
     * 它只引 {@code sa-token-core}（定义侧），刻意不引任何 DAO 集成 —— 见其 pom 里的禁令注释。
     * <p><b>新服务不得「顺手」登记到这里来绕过门禁</b>：若一个服务确实提供 HTTP 登录态，
     * 它就必须走三件套路径。往这里加名字等于宣布「本服务永不参与会话共享」，需要架构评审。</p>
     */
    private static final Set<String> NO_SESSION_SERVICE_MODULES = Set.of(
            "zxyz-audit-service");

    /** 判定「可部署服务模块」的依据：存在 {@code src/main/resources/application.yml}。 */
    private static final String DEPLOYABLE_MARKER = "src/main/resources/application.yml";

    private static final String ARTIFACT_REDIS_TEMPLATE = "sa-token-redis-template";
    /**
     * Boot 4 迁移跟平（ISSUE/48 §8.7.3）：boot4 系坐标为 {@code sa-token-alone-redis-by-spring-boot4}
     * （Central 实测存在，latest 1.46.0 与 spring-boot3 系同版本）。门禁语义不变：
     * 三件套组成、禁入集合、豁免名单、db9 落位、version 管理口径全部原样。
     */
    private static final String ARTIFACT_ALONE_REDIS = "sa-token-alone-redis-by-spring-boot4";
    private static final String ARTIFACT_COMMONS_POOL2 = "commons-pool2";

    /** 「三件套」：自持会话 DAO 所需的完整依赖组合。 */
    private static final Set<String> SESSION_TRIO = Set.of(
            ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS, ARTIFACT_COMMONS_POOL2);

    /** 与 {@code sa-token-redis-template} 互斥的另一套 DAO 实现：两套并存会导致 Sa-Token 双 DAO 冲突。 */
    private static final List<String> FORBIDDEN_DAO_ARTIFACTS =
            List.of("sa-token-redisson", "sa-token-alone-redisson");

    /** 会话统一落在 db9（与各业务库隔离，避免清业务库时连带清掉全部登录态）。 */
    private static final String ALONE_REDIS_DATABASE = "9";

    private static final String ALONE_REDIS_PREFIX = "sa-token.alone-redis";
    private static final String SPRING_REDIS_PREFIX = "spring.data.redis";

    /**
     * <b>测试夹具，不是门禁常量</b>：G1/G2 自检用的「一批守法服务」样本。
     *
     * <p>刻意与门禁解耦 —— 门禁 A 只看 {@link #FORBIDDEN_REDIS_TEMPLATE_MODULES}，
     * **不关心**具体有哪些模块。本夹具只是为了让自检样本看起来像真实形态，
     * 它的成员随仓库演进可以随时增删，**不会**影响门禁判定。</p>
     */
    private static final Set<String> SAMPLE_COMPLIANT_MODULES = Set.of(
            "zxyz-gateway",
            "zxyz-project-service",
            "zxyz-im-service",
            "zxyz-email-service",
            "zxyz-user-service",
            "zxyz-share-service",
            "zxyz-file-service",
            "zxyz-team-service",
            "zxyz-admin-service");

    /** 模拟「未来新增的一个守法服务」——用于证明门禁不阻挡正常演进。 */
    private static final String SAMPLE_NEW_SERVICE = "zxyz-fake-new-service";

    private static final Pattern MODULE_TAG = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT_ID_TAG = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern VERSION_TAG = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern EXCLUSIONS_BLOCK = Pattern.compile("<exclusions>.*?</exclusions>", Pattern.DOTALL);
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::([^}]*))?}$");

    // ==========================================================================
    // 数据模型
    // ==========================================================================

    /**
     * 一个模块的「直接依赖」快照。
     *
     * @param module           模块名（目录名）
     * @param artifacts        顶层 {@code <dependencies>} 里声明的 artifactId 集合
     * @param declaredVersions artifactId → 模块内<b>显式</b>写的 version（未写则不含该键）
     */
    record ModuleDeps(String module, Set<String> artifacts, Map<String, String> declaredVersions) {
    }

    // ==========================================================================
    // 门禁 A：【反向不变量】自持会话 DAO 的模块不得包含架构例外模块
    // ==========================================================================

    @Test
    void A_架构例外模块不得声明saTokenRedisTemplate() {
        Map<String, ModuleDeps> deps = readAllModuleDeps();
        Set<String> actual = modulesDeclaringOrEmpty(deps, ARTIFACT_REDIS_TEMPLATE);

        checkRedisTemplateModuleSet(actual);
    }

    /**
     * 反向不变量：① 反空扫（集合非空）；② 实际集合与 {@link #FORBIDDEN_REDIS_TEMPLATE_MODULES} **无交集**。
     *
     * <p>刻意**不**断言「恰好 N 个」：数量与成员都会随正常演进变化，门禁只关心
     * 「谁**不该**出现」这个不变量。集合大小只用于反空扫与报错信息。</p>
     */
    private static void checkRedisTemplateModuleSet(Set<String> actual) {
        if (actual.isEmpty()) {
            throw new AssertionError("门禁反空扫失败：从根 pom 的 <modules> 出发，"
                    + "没有任何模块被扫到声明了 " + ARTIFACT_REDIS_TEMPLATE
                    + " —— 要么模块清单读取失效，要么依赖扫描逻辑失效。"
                    + "宁可响亮失败，也不要『扫不到就算通过』。");
        }
        Set<String> offenders = new TreeSet<>(actual);
        offenders.retainAll(FORBIDDEN_REDIS_TEMPLATE_MODULES);

        if (!offenders.isEmpty()) {
            throw new AssertionError("以下模块属于「不得自持会话 DAO」的架构例外，"
                    + "却声明了 " + ARTIFACT_REDIS_TEMPLATE + "：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n禁入名单（不可扩张）：" + new TreeSet<>(FORBIDDEN_REDIS_TEMPLATE_MODULES)
                    + "\n  · zxyz-audit-service —— 纯 MQ 消费者，无 HTTP 端点、0 会话调用点；"
                    + "混入后会得到「半截 Sa-Token」，将来加带 @Log 的 Controller 会静默落内存 DAO。"
                    + "\n  · zxyz-common / zxyz-starter —— 库模块，不独立启动；"
                    + "存储实现应由宿主服务决定，库模块不得把 DAO 强加给所有宿主。"
                    + "\n若确实要让某模块参与会话共享，请确认它**是**可独立启动的服务，"
                    + "并同时补齐 B/C 要求的三件套；"
                    + "若只是为了「暂时」绕过，请走正向路径，不要扩张本例外名单。"
                    + "\n注意：本断言**不限制**新增服务 —— 新服务只要按规矩接线即自动通过，无需改测试。"
                    + "当前扫描到 " + actual.size() + " 个模块：" + new TreeSet<>(actual));
        }
    }

    // ==========================================================================
    // 门禁 B：凡有 redis-template 的模块，必须同时有 alone-redis，且不写 version
    // ==========================================================================

    @Test
    void B_上述模块必须同时声明saTokenAloneRedis() {
        Map<String, ModuleDeps> deps = readAllModuleDeps();
        Set<String> targets = modulesDeclaring(deps, ARTIFACT_REDIS_TEMPLATE);

        checkAloneRedisDeclared(targets, deps);
    }

    private static void checkAloneRedisDeclared(Set<String> targetModules, Map<String, ModuleDeps> deps) {
        List<String> offenders = new ArrayList<>();
        List<String> versionOffenders = new ArrayList<>();
        for (String module : new TreeSet<>(targetModules)) {
            ModuleDeps moduleDeps = deps.get(module);
            if (moduleDeps == null) {
                offenders.add(module + " (模块 pom 未读到)");
                continue;
            }
            if (!moduleDeps.artifacts().contains(ARTIFACT_ALONE_REDIS)) {
                offenders.add(module);
            } else if (moduleDeps.declaredVersions().containsKey(ARTIFACT_ALONE_REDIS)) {
                versionOffenders.add(module + " → <version>"
                        + moduleDeps.declaredVersions().get(ARTIFACT_ALONE_REDIS) + "</version>");
            }
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("以下 " + offenders.size() + " 个模块声明了 " + ARTIFACT_REDIS_TEMPLATE
                    + "，却没有同时声明 " + ARTIFACT_ALONE_REDIS + "：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n这些模块的 Sa-Token 会话仍在各自的业务库中，未迁到统一 db9。"
                    + "\n修法：在各模块 pom 的 <dependencies> 里补 "
                    + "<groupId>cn.dev33</groupId><artifactId>" + ARTIFACT_ALONE_REDIS + "</artifactId>"
                    + "（<b>不要写 version</b>，由父 pom 的 dependencyManagement 统一管理）。");
        }
        if (!versionOffenders.isEmpty()) {
            throw new AssertionError("以下模块为 " + ARTIFACT_ALONE_REDIS + " 显式写了 version，"
                    + "绕过了父 pom 的统一版本管理（版本会随 sa-token.version 漂移）：\n  - "
                    + String.join("\n  - ", versionOffenders)
                    + "\n修法：删掉模块内的 <version>，改由父 pom dependencyManagement 提供。");
        }
    }

    // ==========================================================================
    // 门禁 C：【本次缺陷】上述模块必须同时声明 commons-pool2
    // ==========================================================================

    @Test
    void C_上述模块必须同时声明commonsPool2否则启动即崩() {
        Map<String, ModuleDeps> deps = readAllModuleDeps();
        Set<String> targets = modulesDeclaring(deps, ARTIFACT_REDIS_TEMPLATE);

        checkCommonsPool2Declared(targets, deps);
    }

    private static void checkCommonsPool2Declared(Set<String> targetModules, Map<String, ModuleDeps> deps) {
        List<String> offenders = new ArrayList<>();
        for (String module : new TreeSet<>(targetModules)) {
            ModuleDeps moduleDeps = deps.get(module);
            if (moduleDeps == null || !moduleDeps.artifacts().contains(ARTIFACT_COMMONS_POOL2)) {
                offenders.add(module);
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("【已知真实缺陷】以下 " + offenders.size() + " 个模块缺少 "
                    + ARTIFACT_COMMONS_POOL2 + " ⇒ 引入 " + ARTIFACT_ALONE_REDIS + " 后会在启动时崩溃：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n根因：sa-token-alone-redis-by-spring-boot4 1.46.0 在 pattern=single 路径无条件执行"
                    + " new org.apache.commons.pool2.impl.GenericObjectPoolConfig()，"
                    + "而它的 pom 把 commons-pool2 标为 <optional>true</optional> ⇒ 不传递；"
                    + "io.lettuce:lettuce-core 对 commons-pool2 同样是 optional ⇒ 也不传递。"
                    + "结果：启动期 NoClassDefFoundError: org/apache/commons/pool2/impl/GenericObjectPoolConfig。"
                    + "\n修法：在上述模块 pom 补 <groupId>org.apache.commons</groupId>"
                    + "<artifactId>" + ARTIFACT_COMMONS_POOL2 + "</artifactId>（version 由 spring-boot 父 pom 管理）。"
                    + "\n⚠️ 副作用（有意接受，但需在 review 时可见）：补上 pool2 后，"
                    + "LettuceConnectionConfiguration.isPoolEnabled() 的 COMMONS_POOL2_AVAILABLE 会从 false 变 true，"
                    + "主 Redis 客户端从「无池」变成「有池」⇒ "
                    + "spring.data.redis.lettuce.pool.* 的配置从「不起作用」变成「真生效」。");
        }
    }

    // ==========================================================================
    // 门禁 H：【正向全覆盖不变量】每个可部署服务必须「三件套齐全 或 显式豁免」
    // ==========================================================================

    @Test
    void H_可部署服务必须有会话三件套或显式豁免() {
        Map<String, ModuleDeps> deps = readAllModuleDeps();
        Set<String> deployable = readDeployableModules(deps);

        checkDeployableServicesDeclareSessionDeps(deployable, deps, NO_SESSION_SERVICE_MODULES);
    }

    /**
     * <b>本仓的会话接线条不变量（权威表述）</b>：
     * <blockquote>
     * 每一个<b>可部署服务模块</b>，必须<b>显式二选一</b>：<br>
     * ① 声明完整三件套 —— {@code sa-token-redis-template} + {@code sa-token-alone-redis-by-spring-boot4}
     *    + {@code commons-pool2}（⇒ 参与会话共享，会话落 db9）；<br>
     * ② 或登记在<b>显式豁免名单</b>（{@link #NO_SESSION_SERVICE_MODULES}）里（⇒ 声明自己不参与会话共享）。
     * </blockquote>
     *
     * <p>判据来源：「可部署服务模块」= 存在 {@code src/main/resources/application.yml}（见
     * {@link #DEPLOYABLE_MARKER}）；「三件套齐全」= 该模块 pom 的顶层依赖同时含三个 artifactId。</p>
     *
     * <h2>为什么需要这条（补的是 A/B/C 都覆盖不到的空档）</h2>
     * 门禁 B/C 只扫「**已声明** {@code redis-template} 的模块」，门禁 A 只拦禁入集合。
     * 因此两者都**看不见**「完全不声明任何 DAO 依赖、却在代码里调 {@code StpUtil} / 用鉴权注解」
     * 的新服务 —— 它会静默落到内存 DAO（{@code SaTokenDaoDefaultImpl}）：无报错、重启即失效、
     * 与 db9 不通。本门禁用「模块清单全覆盖 + 显式豁免」把这条缝堵上：
     * <b>忘了引依赖会被抓，刻意不用会话则必须留下书面记录</b>。
     *
     * <h2>三条判定</h2>
     * <ol>
     *   <li>可部署 + 三件套不全 + 不在豁免名单 ⇒ <b>红</b>（点名模块与缺失的 artifact）。</li>
     *   <li>可部署 + 在豁免名单 + 却三件套齐全 ⇒ <b>红</b>（陈旧豁免，名单已成谎言）。</li>
     *   <li>豁免名单含不存在或非可部署的模块名 ⇒ <b>红</b>（防拼写错误让规则静默失效）。</li>
     * </ol>
     * 另有反空扫：可部署服务集合为 0 ⇒ 红（否则判据失效即恒绿）。
     */
    private static void checkDeployableServicesDeclareSessionDeps(
            Set<String> deployableModules,
            Map<String, ModuleDeps> deps,
            Set<String> exemptModules) {

        // ① 反空扫：判据一旦失效（扫不到任何可部署服务），本门禁会恒绿 —— 必须响亮失败
        if (deployableModules.isEmpty()) {
            throw new AssertionError("门禁反空扫失败：没有识别出任何「可部署服务模块」"
                    + "（判据 = 存在 " + DEPLOYABLE_MARKER + "）。"
                    + "要么根 pom <modules> 读取失效，要么判据本身失效 —— "
                    + "此时本门禁会恒绿，比不写还危险。宁可响亮失败。");
        }

        // ② 规则 3：豁免名单的每个条目都必须真实存在且可部署
        Set<String> invalidExemptions = new TreeSet<>(exemptModules);
        invalidExemptions.removeAll(deployableModules);
        if (!invalidExemptions.isEmpty()) {
            throw new AssertionError("豁免名单 " + new TreeSet<>(exemptModules) + " 里有无效条目："
                    + invalidExemptions + "\n"
                    + "每个条目必须是「根 pom 已登记、且存在 " + DEPLOYABLE_MARKER + "」的可部署服务。"
                    + "常见原因：拼写错误（如漏掉 -service 后缀）、模块已删除/改名，"
                    + "或把库模块（zxyz-common / zxyz-starter）写进来。"
                    + "受影响的条目会让规则对它静默失效 —— 该服务既不接线也不被检查。"
                    + "\n当前可部署服务：" + new TreeSet<>(deployableModules));
        }

        // ③ 规则 1 + 规则 2：对每个可部署服务做「二选一」判定
        Set<String> missingTrio = new TreeSet<>();
        Set<String> staleExemptions = new TreeSet<>();
        Map<String, Set<String>> missingDetail = new LinkedHashMap<>();

        for (String module : new TreeSet<>(deployableModules)) {
            ModuleDeps moduleDeps = deps.get(module);
            Set<String> declared = moduleDeps == null ? Set.of() : moduleDeps.artifacts();
            Set<String> missingArtifacts = new TreeSet<>(SESSION_TRIO);
            missingArtifacts.removeAll(declared);
            boolean trioComplete = missingArtifacts.isEmpty();
            boolean exempt = exemptModules.contains(module);

            if (!trioComplete && !exempt) {
                // 规则 1：既不接线、也没登记豁免 ⇒ 空档（忘了引依赖）
                missingTrio.add(module);
                missingDetail.put(module, missingArtifacts);
            } else if (trioComplete && exempt) {
                // 规则 2：陈旧豁免（已接线却还在豁免名单里）
                staleExemptions.add(module);
            }
        }

        if (!missingTrio.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (String module : missingTrio) {
                detail.append("\n  - ").append(module)
                        .append(" ⇒ 缺: ").append(missingDetail.get(module));
            }
            throw new AssertionError("【会话接线条空档】以下可部署服务既未接线、也未登记豁免：" + detail
                    + "\n不变量：每个可部署服务模块（有 " + DEPLOYABLE_MARKER + "）必须显式二选一 ——"
                    + "\n  ① 声明三件套 " + new TreeSet<>(SESSION_TRIO) + "（⇒ 会话落 db9）；或"
                    + "\n  ② 登记进豁免名单 " + NO_SESSION_SERVICE_MODULES + "（⇒ 声明不参与会话共享）。"
                    + "\n为什么不能放过：这类模块在静态扫描下与「刻意不用会话」无法区分，但它一旦调用"
                    + " StpUtil.* / 鉴权注解（含 @Log 切面间接调用），会话会<b>静默</b>落到内存 DAO"
                    + "（SaTokenDaoDefaultImpl）—— 无报错、重启即失效、与 db9 不通。"
                    + "\n修法：若该服务提供 HTTP 登录态 ⇒ 在它的 pom 补上述三件套（版本由父 pom / Boot BOM 管理）；"
                    + "若它确实不参与会话共享 ⇒ 把它加入 " + NO_SESSION_SERVICE_MODULES + " 并写明理由。");
        }

        if (!staleExemptions.isEmpty()) {
            throw new AssertionError("【陈旧豁免】以下模块已在豁免名单 " + NO_SESSION_SERVICE_MODULES
                    + " 中，却已声明完整三件套：" + new TreeSet<>(staleExemptions)
                    + "\n含义：这些服务<b>已经开始参与会话共享</b>，豁免理由不再成立 —— "
                    + "必须把它们从豁免名单移除，否则名单会慢慢变成「关于现状的谎言」，"
                    + "而文档性谎言比没有文档更糟（后来者会据此误判该服务不用会话）。"
                    + "\n修法：从 " + NO_SESSION_SERVICE_MODULES + " 删掉上述模块（它们已由 B/C 项覆盖）。");
        }
    }

    // ==========================================================================
    // 门禁 D：父 pom dependencyManagement 必须用 ${sa-token.version} 统一管理
    // ==========================================================================

    @Test
    void D_父pom必须以saTokenVersion统一管理aloneRedis版本() {
        checkAloneRedisManagedInParent(readText(backendRoot().resolve("pom.xml")));
    }

    private static void checkAloneRedisManagedInParent(String parentPomXml) {
        String cleaned = stripXmlComments(parentPomXml);
        String management = extractSection(cleaned, "dependencyManagement");
        if (management == null) {
            throw new AssertionError("父 pom 里找不到 <dependencyManagement> 段 —— 无法统一管理依赖版本。");
        }
        Matcher matcher = DEPENDENCY_BLOCK.matcher(management);
        String foundVersion = null;
        while (matcher.find()) {
            String block = EXCLUSIONS_BLOCK.matcher(matcher.group(1)).replaceAll("");
            Matcher artifactId = ARTIFACT_ID_TAG.matcher(block);
            if (!artifactId.find() || !ARTIFACT_ALONE_REDIS.equals(artifactId.group(1).trim())) {
                continue;
            }
            Matcher version = VERSION_TAG.matcher(block);
            foundVersion = version.find() ? version.group(1).trim() : "";
            break;
        }

        if (foundVersion == null) {
            throw new AssertionError("父 pom 的 <dependencyManagement> 里没有声明 " + ARTIFACT_ALONE_REDIS
                    + " ⇒ 各模块要么被迫自己写 version，要么无法解析该依赖。\n"
                    + "修法：在 <dependencyManagement><dependencies> 中补一条，"
                    + "version 必须复用既有属性 <version>${sa-token.version}</version>，"
                    + "禁止新引一个版本属性（会让 Sa-Token 各组件版本各自漂移）。");
        }
        if (!"${sa-token.version}".equals(foundVersion)) {
            throw new AssertionError(ARTIFACT_ALONE_REDIS + " 在父 pom dependencyManagement 里的 version 是 <"
                    + foundVersion + ">，但要求必须复用既有属性 <${sa-token.version}>（当前 sa-token 系列统一为 1.46.0）。"
                    + "\n若确实需要独立版本，请先确认 Sa-Token 各组件版本兼容性，"
                    + "并同步修改本门禁 —— 不要静默引入第二个版本属性。");
        }

        // 反向：不允许为此新增一个版本属性（例如 <sa-token-alone-redis-by-spring-boot4.version>；
        // 下方匹配是子串式 contains("alone-redis")，坐标改名后同样被拦）
        String properties = extractSection(cleaned, "properties");
        if (properties != null) {
            Matcher propertyMatcher = Pattern.compile("<([A-Za-z0-9_.\\-]+)>\\s*([^<]*)\\s*</\\1>")
                    .matcher(properties);
            while (propertyMatcher.find()) {
                String name = propertyMatcher.group(1);
                if (name.contains("alone-redis") || name.contains("aloneRedis")) {
                    throw new AssertionError("父 pom <properties> 里新引入了版本属性 <" + name + "> = "
                            + propertyMatcher.group(2).trim() + ">。"
                            + "本改造要求复用既有 ${sa-token.version}，不新增版本属性 —— "
                            + "否则 Sa-Token 与 alone-redis 的版本会各自演化，升级时漏改一处即出现运行时 NoSuchMethodError。");
                }
            }
        }
    }

    // ==========================================================================
    // 门禁 E：application-common.yml 的 sa-token.alone-redis 必须存在且与主 Redis 同源
    // ==========================================================================

    @Test
    void E_共享配置里aloneRedis必须存在且与主Redis同源() {
        Path configFile = moduleDir().resolve("src").resolve("main").resolve("resources")
                .resolve("application-common.yml");
        if (!Files.isRegularFile(configFile)) {
            throw new AssertionError("找不到共享配置文件: " + configFile
                    + "（本门禁依赖它作为 sa-token.alone-redis 的唯一真源）");
        }
        Map<String, String> flat = flattenYaml(readLines(configFile));

        // 反空扫：解析器必须至少能读出已知存在的键，否则说明 YAML 扫描失效
        if (!flat.containsKey("sa-token.token-storage-mode") && !flat.containsKey("sa-token.redis.prefix")) {
            throw new AssertionError("YAML 解析结果里连 sa-token.token-storage-mode / sa-token.redis.prefix "
                    + "都读不到 ⇒ 缩进扫描逻辑失效。已解析到 " + flat.size() + " 个键，样例: "
                    + flat.keySet().stream().limit(10).toList()
                    + "。宁可响亮失败，也不要让门禁空扫。");
        }

        Map<String, String> aloneRedis = subMap(flat, ALONE_REDIS_PREFIX + ".");
        Map<String, String> springRedis = subMap(flat, SPRING_REDIS_PREFIX + ".");

        checkAloneRedisConfig(aloneRedis, springRedis);
    }

    // ==========================================================================
    // 门禁 E2：alone-redis 不得出现在 nacos-config（落点回归防护）
    // ==========================================================================

    /**
     * 落点断言：{@code nacos-config/*.yml} 里**不得**出现 {@code alone-redis} 键。
     *
     * <h2>为什么这条是必要的（T4 §E-1）</h2>
     * 本改造把 {@code sa-token.alone-redis} 放在 {@code application-common.yml}，理由见该文件注释：
     * <b>gateway 不 import nacos 的 {@code zxyz-static.yml}</b>（只 import common + dynamic），
     * 而 9 个 servlet 服务都 import static。若有人「顺手」把它补进 nacos-static，
     * 会出现：<b>写会话的服务读 db9、校验会话的 gateway 读 db0（主库）</b>
     * ⇒ 全站登录失效，而两侧配置各自看都「没问题」。
     *
     * <p>注意这不是「配置不该放 nacos」的一般性主张 —— 只是这一条键的**落点契约**：
     * 它的两个消费方 import 面不同，必须落在双方都读的那一份里。</p>
     *
     * <p>扫描范围：{@code backendRoot} 的父目录（= 仓库根）下的 {@code nacos-config/*.yml}。
     * 与 {@code scripts/check-nacos-config-sync.py} 的 {@code --nacos-dir} 默认值一致。</p>
     */
    @Test
    void E2_aloneRedis不得出现在nacos配置里() {
        Path nacosDir = backendRoot().getParent() == null
                ? null
                : backendRoot().getParent().resolve("nacos-config");
        if (nacosDir == null || !Files.isDirectory(nacosDir)) {
            throw new AssertionError("找不到 nacos-config 目录: " + nacosDir
                    + "（本门禁依赖它来守住 alone-redis 的落点契约）。"
                    + "若目录位置变了，请同步修改本测试而不是删掉它。");
        }
        List<Path> files;
        try (java.util.stream.Stream<Path> stream = Files.list(nacosDir)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法列出 " + nacosDir, e);
        }
        if (files.isEmpty()) {
            throw new AssertionError("nacos-config 下没有 *.yml ⇒ 扫描失效，"
                    + "此时任何「通过」都不可信（反空扫）");
        }

        Map<String, String> hits = new LinkedHashMap<>();
        for (Path file : files) {
            String text = readText(file);
            // 只认「键定义」形态（行首可空白 + 可选的 alone-redis:），忽略注释与文档性提及
            Matcher matcher = Pattern.compile("(?m)^\\s*alone-redis\\s*:").matcher(text);
            if (matcher.find()) {
                hits.put(file.getFileName().toString(), "第 " + lineNumberOf(text, matcher.start()) + " 行");
            }
        }
        checkNoAloneRedisInNacos(hits, files.size());
    }

    /** 纯函数形式，供 G1 自检直接喂样本。 */
    private static void checkNoAloneRedisInNacos(Map<String, String> hits, int scannedFileCount) {
        if (scannedFileCount <= 0) {
            throw new AssertionError("反空扫失败：扫描到的 nacos 文件数为 " + scannedFileCount);
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("nacos-config 里出现了 alone-redis 键：" + hits
                    + "\n禁止理由：gateway 不 import nacos 的 zxyz-static.yml（它只有 common + dynamic），"
                    + "而 9 个 servlet 服务都 import static。若把 alone-redis 写进 nacos，"
                    + "gateway 会继续用主库（db0）校验会话、业务服务却把会话写进 db9"
                    + " ⇒ 全站登录失效，且两侧配置各自看都「正常」。"
                    + "\n正确位置：zxyz-common/src/main/resources/application-common.yml 的 sa-token.alone-redis"
                    + "（所有 10 个服务都 import 该文件）。");
        }
    }

    /** 由字符偏移求行号（1 起），仅用于把违规位置报出来。 */
    private static int lineNumberOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void checkAloneRedisConfig(Map<String, String> aloneRedis, Map<String, String> springRedis) {
        if (aloneRedis.isEmpty()) {
            throw new AssertionError("application-common.yml 里没有 " + ALONE_REDIS_PREFIX + ".* 配置段。\n"
                    + "没有它，alone-redis 插件会回退到 spring.data.redis.*（即各服务自己的业务库），"
                    + "会话迁移实际未发生 —— 而启动不会报错，属于「静默不生效」。\n"
                    + "需要补的键（示例，占位符必须与 " + SPRING_REDIS_PREFIX + ".* 同源）：\n"
                    + "  sa-token:\n"
                    + "    alone-redis:\n"
                    + "      database: 9\n"
                    + "      host: ${REDIS_HOST:localhost}\n"
                    + "      port: ${REDIS_PORT:6379}\n"
                    + "      password: ${REDIS_PASSWORD:}");
        }

        String database = aloneRedis.get(ALONE_REDIS_PREFIX + ".database");
        if (database == null) {
            throw new AssertionError("缺少键 " + ALONE_REDIS_PREFIX + ".database —— "
                    + "不指定 db 会落到主 Redis 的 db0（与 REDIS_DATABASE 默认值相同），"
                    + "会话与业务缓存混库，运维无法独立清理。已存在的 alone-redis 键: " + aloneRedis.keySet());
        }
        String resolvedDatabase = defaultValueOf(database);
        if (!ALONE_REDIS_DATABASE.equals(resolvedDatabase)) {
            throw new AssertionError(ALONE_REDIS_PREFIX + ".database 解析出的默认值是 <" + resolvedDatabase
                    + ">（原始写法: " + database + "），要求必须是 " + ALONE_REDIS_DATABASE
                    + " —— 这是本次迁移的目标库号（与各业务库、与主 Redis db0 隔离）。"
                    + "若写成占位符，其默认值也必须是 " + ALONE_REDIS_DATABASE + "。");
        }

        List<String> offenders = new ArrayList<>();
        for (String key : List.of("host", "port", "password")) {
            String aloneKey = ALONE_REDIS_PREFIX + "." + key;
            String springKey = SPRING_REDIS_PREFIX + "." + key;
            String aloneValue = aloneRedis.get(aloneKey);
            String springValue = springRedis.get(springKey);
            if (aloneValue == null) {
                offenders.add(aloneKey + " 缺失（主 Redis 的同名键是 " + springKey + " = " + springValue + "）");
                continue;
            }
            if (springValue == null) {
                offenders.add(springKey + " 在主 Redis 段缺失，无法判定同源");
                continue;
            }
            String aloneVariable = variableNameOf(aloneValue);
            String springVariable = variableNameOf(springValue);
            if (aloneVariable == null || !aloneVariable.equals(springVariable)) {
                offenders.add(aloneKey + " = " + aloneValue + " 与 " + springKey + " = " + springValue
                        + " 不同源（应使用同一个 REDIS_* 环境变量）");
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("Sa-Token alone-redis 的连接参数必须与主 Redis 同源"
                    + "（同一套 REDIS_* 环境变量），否则容器里会连到不同的 Redis 实例/端口：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n特别地：不允许硬编码 localhost / 6379 —— "
                    + "容器内 Redis 主机名是 compose 注入的 REDIS_HOST，硬编码会让会话静默连不上。");
        }
    }

    // ==========================================================================
    // 门禁 F：不得出现 redisson 版 Sa-Token DAO（双 DAO 冲突）
    // ==========================================================================

    @Test
    void F_全仓不得出现redisson版SaTokenDao() {
        Map<String, ModuleDeps> deps = readAllModuleDeps();
        Map<String, Set<String>> artifactsByModule = new LinkedHashMap<>();
        for (ModuleDeps moduleDeps : deps.values()) {
            artifactsByModule.put(moduleDeps.module(), moduleDeps.artifacts());
        }
        // 根 pom 也纳入（它可能把某个 DAO 实现放进全局 <dependencies>）
        artifactsByModule.putAll(readArtifactsOf(backendRoot().resolve("pom.xml"), "zxyz-backend(根 pom)"));

        checkNoRedissonDao(artifactsByModule);
    }

    private static void checkNoRedissonDao(Map<String, Set<String>> artifactsByModule) {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : artifactsByModule.entrySet()) {
            for (String artifact : entry.getValue()) {
                if (FORBIDDEN_DAO_ARTIFACTS.contains(artifact)) {
                    offenders.add(entry.getKey() + " → " + artifact);
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("检测到 redisson 版 Sa-Token DAO 依赖：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\nSa-Token 同时存在两套 DAO 实现（redis-template 与 redisson）会导致会话读写走不同实现，"
                    + "表现为「登录成功但下一次请求查不到会话」这类极难定位的问题。"
                    + "本仓统一使用 " + ARTIFACT_REDIS_TEMPLATE + " + " + ARTIFACT_ALONE_REDIS + "，禁止引入上述依赖。");
        }
    }

    // ==========================================================================
    // 门禁 G：自检 —— 证明上面每个检查「真的会失败」，且合规样本不误报
    // ==========================================================================

    @Test
    void G1_门禁自检_违规样本必须失败并点名违规项() {
        // 样本 1：缺失 commons-pool2（本次真实缺陷的形态）
        ModuleDeps lackingPool2 = new ModuleDeps("zxyz-fake-service",
                Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS), Map.of());
        AssertionError pool2Error = assertThrowsAssertionError(
                () -> checkCommonsPool2Declared(Set.of("zxyz-fake-service"),
                        Map.of("zxyz-fake-service", lackingPool2)),
                "缺 commons-pool2 的样本必须让门禁变红");
        assertMessageNames(pool2Error, "zxyz-fake-service", ARTIFACT_COMMONS_POOL2);

        // 样本 2：缺 sa-token-alone-redis-by-spring-boot4
        ModuleDeps lackingAloneRedis = new ModuleDeps("zxyz-fake-service",
                Set.of(ARTIFACT_REDIS_TEMPLATE), Map.of());
        AssertionError aloneRedisError = assertThrowsAssertionError(
                () -> checkAloneRedisDeclared(Set.of("zxyz-fake-service"),
                        Map.of("zxyz-fake-service", lackingAloneRedis)),
                "缺 sa-token-alone-redis-by-spring-boot4 的样本必须让门禁变红");
        assertMessageNames(aloneRedisError, "zxyz-fake-service", ARTIFACT_ALONE_REDIS);

        // 样本 3：模块内私自写 version
        ModuleDeps ownVersion = new ModuleDeps("zxyz-fake-service",
                Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS, ARTIFACT_COMMONS_POOL2),
                Map.of(ARTIFACT_ALONE_REDIS, "1.46.0"));
        AssertionError versionError = assertThrowsAssertionError(
                () -> checkAloneRedisDeclared(Set.of("zxyz-fake-service"), Map.of("zxyz-fake-service", ownVersion)),
                "模块内私自写 version 的样本必须让门禁变红");
        assertMessageNames(versionError, "zxyz-fake-service", "version");

        // 样本 4：父 pom 未管理版本
        AssertionError parentError = assertThrowsAssertionError(
                () -> checkAloneRedisManagedInParent("<project><dependencyManagement><dependencies>"
                        + "<dependency><groupId>cn.dev33</groupId><artifactId>sa-token-web</artifactId>"
                        + "<version>1.46.0</version></dependency></dependencies></dependencyManagement></project>"),
                "父 pom 缺 alone-redis 版本管理必须让门禁变红");
        assertMessageNames(parentError, ARTIFACT_ALONE_REDIS, "dependencyManagement");

        // 样本 5：父 pom 用了错误的 version 字面量
        AssertionError literalVersionError = assertThrowsAssertionError(
                () -> checkAloneRedisManagedInParent("<project><dependencyManagement><dependencies>"
                        + "<dependency><groupId>cn.dev33</groupId><artifactId>" + ARTIFACT_ALONE_REDIS + "</artifactId>"
                        + "<version>1.46.0</version></dependency></dependencies></dependencyManagement></project>"),
                "父 pom 里写死 version 字面量必须让门禁变红");
        assertMessageNames(literalVersionError, "sa-token.version");

        // 样本 6a：架构例外模块（audit）混入 ⇒ 必须红并点名
        // 注意：这里**只**传「9 个守法服务 + audit」，audit 在禁入集合里 ⇒ A 项红。
        Set<String> withAudit = new LinkedHashSet<>(SAMPLE_COMPLIANT_MODULES);
        withAudit.add("zxyz-audit-service");
        AssertionError auditMixedInError = assertThrowsAssertionError(
                () -> checkRedisTemplateModuleSet(withAudit),
                "架构例外模块 audit 混入必须让门禁变红");
        assertMessageNames(auditMixedInError, "zxyz-audit-service");

        // 样本 6b【本次改造的核心：可扩展性正向对照】
        // 「9 个守法服务 + 一个假设新增的第 10 个服务」⇒ **必须不红**。
        // 改造前这里会红（因为断言「恰好 9 个」）；改造后只做禁入检查，新服务自动放行。
        // 这条与 6a 成对照：同样「多一个模块」，audit 红、守法新服务绿 —— 证明门禁
        // 区分的是「模块性质」而不是「模块数量」。
        Set<String> withNewService = new LinkedHashSet<>(SAMPLE_COMPLIANT_MODULES);
        withNewService.add(SAMPLE_NEW_SERVICE);
        try {
            checkRedisTemplateModuleSet(withNewService);
        } catch (AssertionError error) {
            throw new AssertionError("可扩展性回归：新增一个守法服务（" + SAMPLE_NEW_SERVICE
                    + "）后门禁变红了 —— 说明 A 项又退化成了「枚举快照」，"
                    + "会逼着开发者『为了加服务而改测试』。本轮改造的核心目的就是消除这一点。\n"
                    + "实际报错：" + error.getMessage());
        }

        // 样本 6c：库模块（common / starter）混入同样必须红 —— 禁入集合的三个成员逐一验证
        for (String libraryModule : List.of("zxyz-common", "zxyz-starter")) {
            Set<String> withLibrary = new LinkedHashSet<>(SAMPLE_COMPLIANT_MODULES);
            withLibrary.add(libraryModule);
            AssertionError libraryError = assertThrowsAssertionError(
                    () -> checkRedisTemplateModuleSet(withLibrary),
                    "库模块 " + libraryModule + " 混入必须让门禁变红");
            assertMessageNames(libraryError, libraryModule);
        }

        // 样本 6d：反空扫 —— 空集合必须响亮失败（不得静默通过）
        AssertionError emptyModuleScan = assertThrowsAssertionError(
                () -> checkRedisTemplateModuleSet(new LinkedHashSet<>()),
                "扫到 0 个模块时必须变红（反空扫）");
        assertMessageNames(emptyModuleScan, "反空扫");

        // 样本 6e【未来最可能的真实失误】：新增服务、也引了 redis-template，
        // 但**漏接 alone-redis** ⇒ B 项必须红并点名该新服务。
        // 这条是「A 项放行 + B/C 项兜底」组合的关键证明：A 不再用数量拦人，
        // 但漏接依赖依然会被 B/C 抓住 —— 可扩展性没有以「失去保护」为代价。
        Map<String, ModuleDeps> newServiceMissingAloneRedis = new LinkedHashMap<>();
        newServiceMissingAloneRedis.put(SAMPLE_NEW_SERVICE, new ModuleDeps(SAMPLE_NEW_SERVICE,
                Set.of(ARTIFACT_REDIS_TEMPLATE), Map.of()));
        AssertionError newServiceError = assertThrowsAssertionError(
                () -> checkAloneRedisDeclared(Set.of(SAMPLE_NEW_SERVICE), newServiceMissingAloneRedis),
                "新增服务漏接 alone-redis 必须让门禁变红");
        assertMessageNames(newServiceError, SAMPLE_NEW_SERVICE, ARTIFACT_ALONE_REDIS);

        // 样本 6f：新增服务接了 alone-redis 但漏接 commons-pool2 ⇒ C 项必须红
        Map<String, ModuleDeps> newServiceMissingPool2 = new LinkedHashMap<>();
        newServiceMissingPool2.put(SAMPLE_NEW_SERVICE, new ModuleDeps(SAMPLE_NEW_SERVICE,
                Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS), Map.of()));
        AssertionError newServicePool2Error = assertThrowsAssertionError(
                () -> checkCommonsPool2Declared(Set.of(SAMPLE_NEW_SERVICE), newServiceMissingPool2),
                "新增服务漏接 commons-pool2 必须让门禁变红");
        assertMessageNames(newServicePool2Error, SAMPLE_NEW_SERVICE, ARTIFACT_COMMONS_POOL2);

        // 样本 7：alone-redis 配置段整体缺失
        AssertionError missingConfigError = assertThrowsAssertionError(
                () -> checkAloneRedisConfig(Map.of(), Map.of("spring.data.redis.host", "${REDIS_HOST:localhost}")),
                "缺 alone-redis 配置段必须让门禁变红");
        assertMessageNames(missingConfigError, ALONE_REDIS_PREFIX);

        // 样本 8：database 写错
        AssertionError wrongDatabaseError = assertThrowsAssertionError(
                () -> checkAloneRedisConfig(
                        Map.of(ALONE_REDIS_PREFIX + ".database", "0",
                                ALONE_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                ALONE_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                ALONE_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}"),
                        Map.of(SPRING_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                SPRING_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                SPRING_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}")),
                "database 不是 9 必须让门禁变红");
        assertMessageNames(wrongDatabaseError, ALONE_REDIS_PREFIX + ".database");

        // 样本 9：硬编码 host（与主 Redis 不同源）
        AssertionError hardcodedHostError = assertThrowsAssertionError(
                () -> checkAloneRedisConfig(
                        Map.of(ALONE_REDIS_PREFIX + ".database", ALONE_REDIS_DATABASE,
                                ALONE_REDIS_PREFIX + ".host", "localhost",
                                ALONE_REDIS_PREFIX + ".port", "6379",
                                ALONE_REDIS_PREFIX + ".password", ""),
                        Map.of(SPRING_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                SPRING_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                SPRING_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}")),
                "硬编码 host/port 必须让门禁变红");
        assertMessageNames(hardcodedHostError, "同源");

        // 样本 10：混入 redisson DAO
        AssertionError redissonError = assertThrowsAssertionError(
                () -> checkNoRedissonDao(Map.of("zxyz-fake-service",
                        Set.of(ARTIFACT_REDIS_TEMPLATE, "sa-token-redisson"))),
                "引入 sa-token-redisson 必须让门禁变红");
        assertMessageNames(redissonError, "zxyz-fake-service", "sa-token-redisson");

        // 样本 11（T4 §E-2）：alone-redis 键存在、但 host/port/password 全部缺失
        // —— 覆盖 checkAloneRedisConfig 的「缺失连接参数」分支（与「整段缺失」是不同的分支）
        AssertionError missingHostError = assertThrowsAssertionError(
                () -> checkAloneRedisConfig(
                        Map.of(ALONE_REDIS_PREFIX + ".database", ALONE_REDIS_DATABASE),
                        Map.of(SPRING_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                SPRING_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                SPRING_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}")),
                "有 database 但缺 host/port/password 的样本必须让门禁变红");
        assertMessageNames(missingHostError, ALONE_REDIS_PREFIX + ".host");

        // 样本 12（T4 §E-2）：host/port/password 齐全、但 database 键整个缺失
        AssertionError missingDatabaseError = assertThrowsAssertionError(
                () -> checkAloneRedisConfig(
                        Map.of(ALONE_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                ALONE_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                ALONE_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}"),
                        Map.of(SPRING_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                                SPRING_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                                SPRING_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}")),
                "缺 database 键的样本必须让门禁变红");
        assertMessageNames(missingDatabaseError, ALONE_REDIS_PREFIX + ".database");

        // 样本 13（T4 §E-1）：alone-redis 被误放进 nacos-config
        AssertionError nacosPlacementError = assertThrowsAssertionError(
                () -> checkNoAloneRedisInNacos(Map.of("zxyz-static.yml", "第 42 行"), 11),
                "alone-redis 出现在 nacos 必须让门禁变红");
        assertMessageNames(nacosPlacementError, "zxyz-static.yml", "gateway");

        // 样本 14（反空扫）：nacos 文件数为 0 时必须响亮失败，不得静默通过
        AssertionError emptyScanError = assertThrowsAssertionError(
                () -> checkNoAloneRedisInNacos(Map.of(), 0),
                "扫描到 0 个 nacos 文件时必须变红（反空扫）");
        assertMessageNames(emptyScanError, "反空扫");

        // ---- 门禁 H（T13）的五条自检 ----

        // 样本 15【规则 1：空档】可部署服务、三件套不全、又不在豁免名单 ⇒ 必须红并点名 + 列出缺失项
        // 注：豁免名单传空集 —— 规则 3（豁免有效性）先于规则 1 执行，
        //     若这里传含 audit 的真实名单，而本样本的可部署集合里没有 audit，会先触发规则 3。
        Map<String, ModuleDeps> bareService = new LinkedHashMap<>();
        bareService.put(SAMPLE_NEW_SERVICE, new ModuleDeps(SAMPLE_NEW_SERVICE, Set.of(), Map.of()));
        AssertionError bareServiceError = assertThrowsAssertionError(
                () -> checkDeployableServicesDeclareSessionDeps(
                        Set.of(SAMPLE_NEW_SERVICE), bareService, Set.of()),
                "可部署服务既未接线也未豁免必须让门禁变红（这是本次要堵的空档）");
        assertMessageNames(bareServiceError, SAMPLE_NEW_SERVICE, ARTIFACT_REDIS_TEMPLATE);

        // 样本 16：同上，但三件套只缺一件（更接近真实失误：接了 redis-template 忘补 pool2）
        Map<String, ModuleDeps> partialService = new LinkedHashMap<>();
        partialService.put(SAMPLE_NEW_SERVICE, new ModuleDeps(SAMPLE_NEW_SERVICE,
                Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS), Map.of()));
        AssertionError partialServiceError = assertThrowsAssertionError(
                () -> checkDeployableServicesDeclareSessionDeps(
                        Set.of(SAMPLE_NEW_SERVICE), partialService, Set.of()),
                "可部署服务缺三件套中任一件必须让门禁变红");
        assertMessageNames(partialServiceError, SAMPLE_NEW_SERVICE, ARTIFACT_COMMONS_POOL2);

        // 样本 17【规则 2：陈旧豁免】在豁免名单里、却已三件套齐全 ⇒ 必须红并点名
        Map<String, ModuleDeps> staleExemptService = new LinkedHashMap<>();
        staleExemptService.put("zxyz-audit-service", new ModuleDeps("zxyz-audit-service",
                Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS, ARTIFACT_COMMONS_POOL2), Map.of()));
        AssertionError staleExemptionError = assertThrowsAssertionError(
                () -> checkDeployableServicesDeclareSessionDeps(
                        Set.of("zxyz-audit-service"), staleExemptService, NO_SESSION_SERVICE_MODULES),
                "已接线却仍在豁免名单必须让门禁变红（陈旧豁免）");
        assertMessageNames(staleExemptionError, "zxyz-audit-service", "陈旧");

        // 样本 18【规则 3：无效豁免】豁免名单里有不存在的模块名 ⇒ 必须红并点名
        Map<String, ModuleDeps> oneService = new LinkedHashMap<>();
        oneService.put(SAMPLE_NEW_SERVICE, new ModuleDeps(SAMPLE_NEW_SERVICE, SESSION_TRIO, Map.of()));
        AssertionError invalidExemptionError = assertThrowsAssertionError(
                () -> checkDeployableServicesDeclareSessionDeps(
                        Set.of(SAMPLE_NEW_SERVICE), oneService, Set.of("zxyz-typo-service")),
                "豁免名单含不存在模块名必须让门禁变红（防拼写错误静默失效）");
        assertMessageNames(invalidExemptionError, "zxyz-typo-service", "无效条目");

        // 样本 19（反空扫）：可部署服务集合为 0 时必须响亮失败
        AssertionError emptyDeployableScan = assertThrowsAssertionError(
                () -> checkDeployableServicesDeclareSessionDeps(
                        Set.of(), Map.of(), NO_SESSION_SERVICE_MODULES),
                "识别出 0 个可部署服务时必须变红（反空扫）");
        assertMessageNames(emptyDeployableScan, "反空扫");
    }

    @Test
    void G2_门禁自检_合规样本必须全绿不误报() {
        // 【本次改造的可扩展性直接证据】合规样本 = 现有 9 个守法服务 + 一个**假设新增**的服务，
        // 且该新服务三件套齐全。改造前 A 项会因「多了一个」而红；改造后必须全绿。
        Set<String> compliantModules = new TreeSet<>(SAMPLE_COMPLIANT_MODULES);
        compliantModules.add(SAMPLE_NEW_SERVICE);
        Map<String, ModuleDeps> compliantDeps = new LinkedHashMap<>();
        for (String module : compliantModules) {
            compliantDeps.put(module, new ModuleDeps(module,
                    Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_ALONE_REDIS, ARTIFACT_COMMONS_POOL2),
                    Map.of()));
        }
        // 自检前置：样本里必须真的有那个新服务，否则这条「可扩展性证据」是空的
        if (!compliantDeps.containsKey(SAMPLE_NEW_SERVICE)) {
            throw new AssertionError("自检样本构造失效：合规集合里没有 " + SAMPLE_NEW_SERVICE
                    + " ⇒ 本条无法证明「新增服务不被拦」，属于空跑");
        }
        if (compliantDeps.size() <= SAMPLE_COMPLIANT_MODULES.size()) {
            throw new AssertionError("自检样本构造失效：合规集合没有比基准多出模块"
                    + "（实际 " + compliantDeps.size() + " vs 基准 " + SAMPLE_COMPLIANT_MODULES.size()
                    + "）⇒ 无法证明可扩展性");
        }

        // 十二条检查逐一跑在合规样本上，任何一条误报都会让本用例失败
        checkRedisTemplateModuleSet(compliantModules);
        checkAloneRedisDeclared(compliantModules, compliantDeps);
        checkCommonsPool2Declared(compliantModules, compliantDeps);
        checkNoRedissonDao(Map.of("zxyz-gateway", Set.of(ARTIFACT_REDIS_TEMPLATE, ARTIFACT_COMMONS_POOL2)));

        // 门禁 H 的合规面：① 全部三件套齐全、无豁免 ⇒ 不误报；
        // ② 豁免模块（三件套为空）⇒ 也不误报。两条合起来覆盖「二选一」的两个合法分支。
        checkDeployableServicesDeclareSessionDeps(compliantModules, compliantDeps, Set.of());
        Map<String, ModuleDeps> exemptOnlySample = new LinkedHashMap<>();
        exemptOnlySample.put("zxyz-audit-service", new ModuleDeps("zxyz-audit-service", Set.of(), Map.of()));
        checkDeployableServicesDeclareSessionDeps(
                Set.of("zxyz-audit-service"), exemptOnlySample, NO_SESSION_SERVICE_MODULES);

        checkAloneRedisManagedInParent("<project>"
                + "<properties><sa-token.version>1.46.0</sa-token.version></properties>"
                + "<dependencyManagement><dependencies>"
                + "<dependency><groupId>cn.dev33</groupId><artifactId>" + ARTIFACT_ALONE_REDIS + "</artifactId>"
                + "<version>${sa-token.version}</version></dependency>"
                + "</dependencies></dependencyManagement></project>");

        Map<String, String> compliantSpringRedis = Map.of(
                SPRING_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                SPRING_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                SPRING_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}");
        Map<String, String> compliantAloneRedis = Map.of(
                ALONE_REDIS_PREFIX + ".database", ALONE_REDIS_DATABASE,
                ALONE_REDIS_PREFIX + ".host", "${REDIS_HOST:localhost}",
                ALONE_REDIS_PREFIX + ".port", "${REDIS_PORT:6379}",
                ALONE_REDIS_PREFIX + ".password", "${REDIS_PASSWORD:}");
        checkAloneRedisConfig(compliantAloneRedis, compliantSpringRedis);

        // 落点门禁的合规面：nacos 里没有 alone-redis ⇒ 必须放行（且扫描数>0）
        checkNoAloneRedisInNacos(Map.of(), 11);

        // 占位符形式写 database=9 同样应放行
        Map<String, String> placeholderDatabase = new LinkedHashMap<>(compliantAloneRedis);
        placeholderDatabase.put(ALONE_REDIS_PREFIX + ".database", "${REDIS_ALONE_DATABASE:9}");
        checkAloneRedisConfig(placeholderDatabase, compliantSpringRedis);
    }

    @Test
    void G3_门禁自检_YAML解析必须是点分路径而非全文包含() {
        List<String> sample = List.of(
                "sa-token:",
                "  token-storage-mode: redis",
                "  redis:",
                "    prefix: ${SA_TOKEN_REDIS_PREFIX:satoken:}",
                "spring:",
                "  data:",
                "    redis:",
                "      host: ${REDIS_HOST:localhost}",
                "      # database: 0   ← 注释不得被当成配置",
                "      port: ${REDIS_PORT:6379}",
                "",
                "sa-token-alone:",
                "  elsewhere: 7");

        Map<String, String> flat = flattenYaml(sample);

        assertEqualsValue("redis", flat.get("sa-token.token-storage-mode"),
                "sa-token.token-storage-mode 应解析为 redis");
        assertEqualsValue("${SA_TOKEN_REDIS_PREFIX:satoken:}", flat.get("sa-token.redis.prefix"),
                "sa-token.redis.prefix 应解析出完整占位符（值内含冒号，不能按第一个冒号截断）");
        assertEqualsValue("${REDIS_HOST:localhost}", flat.get("spring.data.redis.host"),
                "spring.data.redis.host 应解析为占位符");
        assertEqualsValue("${REDIS_PORT:6379}", flat.get("spring.data.redis.port"),
                "spring.data.redis.port 应解析为占位符");
        assertEqualsValue("7", flat.get("sa-token-alone.elsewhere"),
                "同名末段但不带 sa-token. 前缀的键应各自独立");
        if (flat.containsKey("spring.data.redis.database")) {
            throw new AssertionError("被注释掉的 database 行不应进入解析结果 —— "
                    + "注释处理失效会让门禁把「注释里的配置」当成真配置");
        }
        if (flat.containsKey(ALONE_REDIS_PREFIX + ".database")) {
            throw new AssertionError("样本里根本没有 " + ALONE_REDIS_PREFIX + ".database，"
                    + "却解析出了该路径 ⇒ 出现了跨 section 串味（这正是不能用 contains 的原因）");
        }
    }

    // ==========================================================================
    // 扫描与工具（读盘）
    // ==========================================================================

    private static Path moduleDir() {
        try {
            Path testClasses = Paths.get(AloneRedisWiringContractTest.class.getProtectionDomain()
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

    /**
     * 判定可部署服务：模块目录下存在 {@code src/main/resources/application.yml}。
     *
     * <p><b>为什么不用「有 resources 目录」</b>：{@code zxyz-common} 与 {@code zxyz-starter} 都是
     * <b>有 resources 目录、但无 {@code application.yml}</b> 的库模块（它们只有 {@code application-common.yml}
     * 之类的被引用配置，没有自己的启动配置）。用「有 resources」当判据会把这两个库模块误判为可部署服务，
     * 进而要求它们三件套齐全 —— 而它们恰恰是「不得自持 DAO」的架构例外，门禁会自相矛盾。
     * <p>本判据与 {@code scripts/check-nacos-config-sync.py} 的消费方识别口径一致（本仓既有约定）。</p>
     *
     * <p>返回的是「模块名 → 是否可部署」的判定结果，仅包含根 pom 里登记、且 pom.xml 可读的模块。</p>
     */
    private static Set<String> readDeployableModules(Map<String, ModuleDeps> deps) {
        Set<String> deployable = new LinkedHashSet<>();
        for (String module : deps.keySet()) {
            if (Files.isRegularFile(backendRoot().resolve(module).resolve(DEPLOYABLE_MARKER))) {
                deployable.add(module);
            }
        }
        return deployable;
    }

    /** 读根 pom 的 {@code <modules>}，再逐模块读 pom 的直接依赖。 */
    private static Map<String, ModuleDeps> readAllModuleDeps() {
        Path rootPom = backendRoot().resolve("pom.xml");
        List<String> modules = readModuleNames(rootPom);
        Map<String, ModuleDeps> result = new LinkedHashMap<>();
        for (String module : modules) {
            Path modulePom = backendRoot().resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(modulePom)) {
                continue;
            }
            result.put(module, readModuleDeps(module, readText(modulePom)));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("没有读到任何模块 pom —— 根 pom <modules> 或路径反推失效");
        }
        return result;
    }

    private static List<String> readModuleNames(Path rootPom) {
        String xml = stripXmlComments(readText(rootPom));
        Matcher matcher = MODULE_TAG.matcher(xml);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("在 " + rootPom + " 里没有解析到任何 <module>");
        }
        return names;
    }

    /** 只取模块<b>顶层</b> {@code <dependencies>}：先剔除 dependencyManagement / build / profiles，避免误收。 */
    static ModuleDeps readModuleDeps(String module, String pomXml) {
        String xml = stripXmlComments(pomXml);
        for (String section : List.of("dependencyManagement", "build", "profiles", "reporting")) {
            xml = removeSection(xml, section);
        }
        return new ModuleDeps(module, collectArtifacts(xml), collectVersions(xml));
    }

    private static Map<String, Set<String>> readArtifactsOf(Path pomXml, String label) {
        String xml = stripXmlComments(readText(pomXml));
        return Map.of(label, collectArtifacts(xml));
    }

    private static Set<String> collectArtifacts(String xml) {
        Set<String> artifacts = new LinkedHashSet<>();
        Matcher matcher = DEPENDENCY_BLOCK.matcher(xml);
        while (matcher.find()) {
            String block = EXCLUSIONS_BLOCK.matcher(matcher.group(1)).replaceAll("");
            Matcher artifactId = ARTIFACT_ID_TAG.matcher(block);
            if (artifactId.find()) {
                artifacts.add(artifactId.group(1).trim());
            }
        }
        return artifacts;
    }

    private static Map<String, String> collectVersions(String xml) {
        Map<String, String> versions = new LinkedHashMap<>();
        Matcher matcher = DEPENDENCY_BLOCK.matcher(xml);
        while (matcher.find()) {
            String block = EXCLUSIONS_BLOCK.matcher(matcher.group(1)).replaceAll("");
            Matcher artifactId = ARTIFACT_ID_TAG.matcher(block);
            if (!artifactId.find()) {
                continue;
            }
            Matcher version = VERSION_TAG.matcher(block);
            if (version.find()) {
                versions.put(artifactId.group(1).trim(), version.group(1).trim());
            }
        }
        return versions;
    }

    /**
     * 与 {@link #modulesDeclaring} 相同，但**不抛异常**：空集合原样返回。
     *
     * <p>门禁 A 需要自己掌握「反空扫」的报错文案（它要区分「扫不到」与「扫到但违规」两种失败），
     * 且 G2 的合规样本会喂入合成集合，故这里不替调用方做判断。</p>
     */
    private static Set<String> modulesDeclaringOrEmpty(Map<String, ModuleDeps> deps, String artifact) {
        Set<String> modules = new LinkedHashSet<>();
        for (ModuleDeps moduleDeps : deps.values()) {
            if (moduleDeps.artifacts().contains(artifact)) {
                modules.add(moduleDeps.module());
            }
        }
        return modules;
    }

    private static Set<String> modulesDeclaring(Map<String, ModuleDeps> deps, String artifact) {
        Set<String> modules = new LinkedHashSet<>();
        for (ModuleDeps moduleDeps : deps.values()) {
            if (moduleDeps.artifacts().contains(artifact)) {
                modules.add(moduleDeps.module());
            }
        }
        if (modules.isEmpty()) {
            throw new AssertionError("反空扫失败：没有任何模块声明 " + artifact
                    + " —— 依赖扫描逻辑可能已失效，此时任何「通过」都不可信");
        }
        return modules;
    }

    // ==========================================================================
    // YAML 缩进扫描（不引第三方库；解析成点分路径，避免 contains 式误判）
    // ==========================================================================

    /**
     * 把 YAML 子集解析为「点分路径 → 标量值」。
     *
     * <p>只覆盖本仓配置文件用到的形态：映射 + 2 空格缩进 + 标量值（含 {@code ${...}} 占位符与值内冒号）。
     * 列表项（{@code - xxx}）与注释行被跳过。section 栈保证路径正确 ——
     * 于是 {@code sa-token.redis.prefix} 不会与 {@code sa-token.alone-redis.*} 串味。</p>
     */
    static Map<String, String> flattenYaml(List<String> lines) {
        Map<String, String> flat = new LinkedHashMap<>();
        Deque<Section> stack = new ArrayDeque<>();
        for (String rawLine : lines) {
            String line = stripYamlComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.equals("---") || trimmed.startsWith("---")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            int indent = indentOf(line);
            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                stack.pop();
            }
            if (value.isEmpty()) {
                stack.push(new Section(indent, key));
                continue;
            }
            StringBuilder path = new StringBuilder();
            List<Section> ordered = new ArrayList<>(stack);
            java.util.Collections.reverse(ordered);
            for (Section section : ordered) {
                path.append(section.name()).append('.');
            }
            flat.put(path + key, unquote(value));
        }
        return flat;
    }

    /** {@code '#'} 起始的注释（引号内的 {@code #} 不算）。本仓配置里注释均在行首或值后带空格。 */
    private static String stripYamlComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private record Section(int indent, String name) {
    }

    /** 取 {@code ${VAR:default}} 里的环境变量名；非占位符返回 {@code null}。 */
    static String variableNameOf(String expression) {
        Matcher matcher = PLACEHOLDER.matcher(expression.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** 取 {@code ${VAR:default}} 的默认值；非占位符则原样返回字面量。 */
    static String defaultValueOf(String expression) {
        Matcher matcher = PLACEHOLDER.matcher(expression.trim());
        if (!matcher.matches()) {
            return expression.trim();
        }
        return matcher.group(2) == null ? "" : matcher.group(2);
    }

    private static Map<String, String> subMap(Map<String, String> flat, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    // ==========================================================================
    // XML / 断言工具
    // ==========================================================================

    private static String stripXmlComments(String xml) {
        return XML_COMMENT.matcher(xml).replaceAll("");
    }

    /** 取顶层 {@code <tag>...</tag>} 的内容；不存在返回 null（非贪婪，够用于本仓 pom 形态）。 */
    private static String extractSection(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 删除顶层 {@code <tag>...</tag>} 整段（用于把模块 pom 收敛到「顶层 dependencies」）。 */
    private static String removeSection(String xml, String tag) {
        return Pattern.compile("<" + tag + ">.*?</" + tag + ">", Pattern.DOTALL).matcher(xml).replaceAll("");
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + path, e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + path, e);
        }
    }

    private static AssertionError assertThrowsAssertionError(Runnable action, String reason) {
        try {
            action.run();
        } catch (AssertionError error) {
            return error;
        }
        throw new AssertionError("自检失败：" + reason + " —— 但它没有抛 AssertionError，"
                + "说明这条门禁是「恒绿」的，等于不存在");
    }

    private static void assertMessageNames(AssertionError error, String... requiredFragments) {
        String message = String.valueOf(error.getMessage());
        for (String fragment : requiredFragments) {
            if (!message.contains(fragment)) {
                throw new AssertionError("自检失败：门禁报错信息里没有点名 <" + fragment + ">，"
                        + "排查者无法据此定位。实际消息：\n" + message);
            }
        }
    }

    private static void assertEqualsValue(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + "；期望 <" + expected + ">，实际 <" + actual + ">");
        }
    }
}
