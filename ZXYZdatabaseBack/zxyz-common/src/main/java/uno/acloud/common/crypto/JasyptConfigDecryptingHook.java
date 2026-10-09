package uno.acloud.common.crypto;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置面 {@code ENC(...)} 解密钩子（ISSUE/48 §九 9.3：jasypt-spring-boot-starter 的 Boot 4 替代）。
 *
 * <h2>做什么</h2>
 * <p>Environment 后处理阶段扫描各 PropertySource，把值形如 {@code ENC(...)} 的条目包装成
 * <b>惰性解密</b>的只读视图：任何后续读取（{@code @Value}、Binder、{@code getProperty}）
 * 拿到的都是解密后的明文。非 ENC 值原样透传。</p>
 *
 * <h2>什么时候什么都不做（关键设计）</h2>
 * <p>环境里<b>不存在任何 ENC() 值时，本钩子零动作</b>：不读密钥、不包一层、不改源顺序。
 * 因此没有 {@code JASYPT_PASSWORD} 的上下文（绝大多数本地/测试场景）行为与从前完全一致，
 * 只有真的写入了 ENC() 值才会要求密钥（fail-closed）。</p>
 *
 * <h2>顺序</h2>
 * <p>必须排在 {@code ConfigDataEnvironmentPostProcessor}（处理
 * {@code spring.config.import}，含 Nacos 配置拉取）<b>之后</b>——否则看不到配置文件里
 * 的 ENC 值与 {@code jasypt.encryptor.password} 的定义。未显式定序的 EnvironmentPostProcessor
 * 默认 {@link Ordered#LOWEST_PRECEDENCE}，天然在其后；这里仍显式声明，意图自解释。</p>
 *
 * <h2>注册方式（Boot 4.0.8 jar 实测）</h2>
 * <p>{@code META-INF/spring.factories}，key 为 {@code org.springframework.boot.EnvironmentPostProcessor}
 * —— Boot 4 核心模块自身的同名注册仍走该文件（boot core jar 的 spring.factories 含
 * {@code # Environment Post Processors} 段），EnvironmentPostProcessorApplicationListener 按此加载。
 * 注意它与自动配置无关，<b>不</b>写入 {@code AutoConfiguration.imports}。</p>
 *
 * <h2>为什么包装而不是替换</h2>
 * <p>包装保留原 PropertySource 的名称/来源与相对顺序（{@code replace} 原位换芯），
 * OriginTrackedValue 的 Origin（报错定位用）也随值保留。解密失败（典型：密钥错）会带属性名抛出，
 * 而不是返回密文静默流转。</p>
 */
public final class JasyptConfigDecryptingHook implements EnvironmentPostProcessor, Ordered {

    /** 排在配置数据加载之后、其余低阶处理之前（意图明确的“几乎最后”）。 */
    public static final int HOOK_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Override
    public int getOrder() {
        return HOOK_ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!hasEncryptedValues(environment)) {
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        // 先收集后修改：getPropertySources() 的流视图不允许遍历中途增删
        List<EnumerablePropertySource<?>> toWrap = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable && containsEncryptedValue(enumerable)) {
                toWrap.add(enumerable);
            }
        }
        for (EnumerablePropertySource<?> source : toWrap) {
            sources.replace(source.getName(), new DecryptingPropertySource(source, environment));
        }
    }

    private boolean hasEncryptedValues(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable && containsEncryptedValue(enumerable)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEncryptedValue(EnumerablePropertySource<?> source) {
        for (String name : source.getPropertyNames()) {
            if (isEncryptedRaw(source.getProperty(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原始值形态判定：yml 值的真实载体是 {@link OriginTrackedValue}（origin 保留包装），
     * 其 {@code toString()} 才是 {@code ENC(...)} 字面量。
     */
    private static boolean isEncryptedRaw(Object raw) {
        return EncryptedValueDetector.isEncrypted(String.valueOf(raw));
    }

    /**
     * 只读解密视图：{@code getProperty} 返回解密后的明文（保留 Origin），其余全部透传。
     * 包内可见（同包测试用 {@code instanceof} 断言「无 ENC 环境零包装」）。
     */
    static final class DecryptingPropertySource extends EnumerablePropertySource<Object> {

        private final EnumerablePropertySource<?> delegate;

        private final ConfigurableEnvironment environment;

        private DecryptingPropertySource(EnumerablePropertySource<?> delegate, ConfigurableEnvironment environment) {
            super(delegate.getName(), delegate.getSource());
            this.delegate = delegate;
            this.environment = environment;
        }

        @Override
        public String[] getPropertyNames() {
            return delegate.getPropertyNames();
        }

        @Override
        public Object getProperty(String name) {
            Object raw = delegate.getProperty(name);
            if (!isEncryptedRaw(raw)) {
                return raw;
            }
            String decrypted = decrypt(name, String.valueOf(raw));
            Origin origin = Origin.from(raw);
            // yml 值带 Origin（报错定位用）→ 保留；普通 Map 值无 Origin → 直接返回明文
            return origin != null ? OriginTrackedValue.of(decrypted, origin) : decrypted;
        }

        private String decrypt(String propertyName, String encValue) {
            // 防递归：主密钥配置项自身若是 ENC(...)，解密它需要先解密它（StackOverflow）。
            // 主密钥必须以明文配置（环境变量注入），这里显式拒绝而不是挂死。
            if (JasyptPasswordPolicy.PASSWORD_PROPERTY.equals(propertyName)) {
                throw new IllegalStateException("配置项 " + propertyName
                        + " 自身不能是 ENC(...) 密文：主密钥必须以明文配置（推荐环境变量 JASYPT_PASSWORD 注入）。");
            }
            try {
                return encryptor().decrypt(EncryptedValueDetector.unwrap(encValue));
            } catch (org.jasypt.exceptions.EncryptionOperationNotPossibleException e) {
                // 只包「真实解密失败」（典型：密钥不一致/密文被篡改）。密钥校验等 fail-closed
                // 异常（IllegalStateException）必须原样穿透——否则会被这里吞掉重包，
                // 报错指到错误的属性上（实测教训）。
                throw new IllegalStateException(
                        "配置项 " + propertyName + " 的 ENC(...) 密文解密失败（典型原因：JASYPT_PASSWORD"
                                + " 与加密时不一致，或密文被篡改）。为避免密文当明文静默流转，启动中止。"
                                + "排查见 docs/jasypt-key-management.md。", e);
            }
        }

        /**
         * 每次解密时从<b>包装后</b>的环境解析密钥与算法，而不是钩子入口缓存：
         * 密钥可能定义在比“当前遍历点”更晚处理的 PropertySource 里。
         *
         * <p>刻意<b>不走 Binder</b>（不 bind("jasypt.encryptor") 前缀）：Binder 在读取属性值
         * 发生异常时会把它包成 BindException，吞掉我们 fail-closed 的具体原因（实测教训）；
         * 三个平铺键直接 getProperty，异常按原样穿透。</p>
         */
        private org.jasypt.encryption.StringEncryptor encryptor() {
            // 密钥最先读：PASSWORD_PROPERTY 自身是 ENC(...) 时，守卫异常从这里直接抛出
            String password = JasyptPasswordPolicy.requireUsablePassword(
                    environment.getProperty(JasyptPasswordPolicy.PASSWORD_PROPERTY));
            JasyptProperties properties = new JasyptProperties();
            String algorithm = environment.getProperty("jasypt.encryptor.algorithm");
            if (algorithm != null && !algorithm.isBlank()) {
                properties.setAlgorithm(algorithm.trim());
            }
            String ivGenerator = environment.getProperty("jasypt.encryptor.iv-generator-classname");
            if (ivGenerator != null && !ivGenerator.isBlank()) {
                properties.setIvGeneratorClassname(ivGenerator.trim());
            }
            return JasyptEncryptorFactory.create(properties, password);
        }
    }
}
