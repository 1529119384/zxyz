package uno.acloud.autoconfig;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import uno.acloud.common.crypto.JasyptEncryptorFactory;
import uno.acloud.common.crypto.JasyptPasswordPolicy;
import uno.acloud.common.crypto.JasyptProperties;
import uno.acloud.common.crypto.LazyInitStringEncryptor;

/**
 * Jasypt 配置加密的 Boot 4 自建自动配置（ISSUE/48 §九：替代停更的 jasypt-spring-boot-starter）。
 *
 * <h2>提供什么</h2>
 * <ul>
 *   <li>{@link org.jasypt.encryption.StringEncryptor} Bean —— 注入源。
 *       {@code uno.acloud.common.util.JasyptEncryptor}（包装类）与配置面解密钩子
 *       {@code JasyptConfigDecryptingHook} 都从它取加密器，算法/密钥口径全局唯一。</li>
 *   <li>配置面 {@code ENC(...)} 解密由 {@code JasyptConfigDecryptingHook}
 *       （注册于 {@code META-INF/spring.factories}）完成，与本类无关——
 *       EnvironmentPostProcessor 不走自动配置体系。</li>
 * </ul>
 *
 * <h2>为什么 Bean 是惰性的</h2>
 * <p>主密钥校验 fail-closed（缺失/未解析占位符/公开弱值即抛），但校验延迟到<b>首次使用</b>
 * （见 {@link LazyInitStringEncryptor}）：没有 {@code JASYPT_PASSWORD} 的上下文
 * （各服务集成测试、不消费加密配置的服务）只要不碰 ENC() 就照常启动——
 * 与原 starter 的 lazy 语义一致。</p>
 *
 * <h2>为什么本类在 {@code uno.acloud.autoconfig}</h2>
 * <p>同 {@code CacheConfig} 等（C-10）：{@code uno.acloud.common.**} 被各服务
 * {@code @ComponentScan} 覆盖，自动配置放那里会被双路径注册（契约门禁
 * {@code AutoConfigurationImportsContractTest} 零容忍）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(JasyptProperties.class)
public class JasyptPropertiesEncryptorAutoConfiguration {

    /**
     * @param properties  {@code jasypt.encryptor.*} 绑定（算法/IV 生成器；password 字段仅作绑定面存续）
     * @param environment 用于在<b>首次使用时</b>解析密钥——密钥定义（含 Nacos 下发）可能晚于
     *                    Bean 创建，不能在 Bean 创建期读死
     */
    @Bean
    @ConditionalOnMissingBean(org.jasypt.encryption.StringEncryptor.class)
    public org.jasypt.encryption.StringEncryptor jasyptStringEncryptor(JasyptProperties properties,
                                                                       Environment environment) {
        return LazyInitStringEncryptor.of(() -> {
            // 首次使用时重新解析：让 Nacos 动态配置对算法/IV 的覆盖也能生效。
            // 刻意不走 Binder：Binder 在读取属性值发生异常时会包成 BindException，
            // 吞掉密钥校验 fail-closed 的具体原因；三个平铺键直接读，异常原样穿透。
            JasyptProperties effective = new JasyptProperties();
            String algorithm = environment.getProperty("jasypt.encryptor.algorithm");
            if (algorithm != null && !algorithm.isBlank()) {
                effective.setAlgorithm(algorithm.trim());
            }
            String ivGenerator = environment.getProperty("jasypt.encryptor.iv-generator-classname");
            if (ivGenerator != null && !ivGenerator.isBlank()) {
                effective.setIvGeneratorClassname(ivGenerator.trim());
            }
            // 密钥解析顺序：yml/nacos 定义的 jasypt.encryptor.password（其值 ${JASYPT_PASSWORD} 在此刻
            // 才做嵌套占位符解析）→ 环境变量 JASYPT_PASSWORD 兜底（防消费方不引 application-common.yml）
            String password = JasyptPasswordPolicy.requireUsablePassword(firstNonBlank(
                    environment.getProperty(JasyptPasswordPolicy.PASSWORD_PROPERTY),
                    environment.getProperty("JASYPT_PASSWORD")));
            return JasyptEncryptorFactory.create(effective, password);
        });
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank() && !(primary.startsWith("${") && primary.endsWith("}"))) {
            return primary;
        }
        return fallback;
    }
}
