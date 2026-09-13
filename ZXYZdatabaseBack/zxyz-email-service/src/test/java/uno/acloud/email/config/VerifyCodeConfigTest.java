package uno.acloud.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.util.VerifyCodeHasher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VerifyCodeConfig} 的装配契约测试 —— 钉死「pepper 真的从 {@link EmailProperties}
 * 绑进来」与「没 pepper 就起不来（fail-closed）」两件事。
 *
 * <p>为什么必须有这个测试：{@code VerifyCodeHasher} 是<b>没有 Spring 注解</b>的普通类，
 * 它的实例只能由本配置类的 {@code @Bean} 方法产出。此前所有单测都是直接
 * {@code new VerifyCodeHasher(...)}，<b>没有任何测试证明过 {@code @Bean} 装配真的能拿到 pepper</b>。</p>
 *
 * <p>生产装配路径与本测试等价：启动类 {@code ZxyzEmailApplication} 带
 * {@code @ConfigurationPropertiesScan}（等价于这里的 {@code @EnableConfigurationProperties}），
 * 本配置类落在 {@code @ComponentScan(basePackages = "uno.acloud.email")} 范围内。</p>
 *
 * <p>金标准摘要由 Python {@code hmac} 与 openssl 两套独立实现算出，不是被测代码自产。
 * 与 user-service 侧<b>共用同一个期望值</b>（pepper 相同时两边摘要必须一致）—— 这正是
 * 「两服务口径分叉 ⇒ 自己发的码自己验不过」那条风险的回归防线。</p>
 */
class VerifyCodeConfigTest {

    /** hmac_sha256(key="zxyz:verify-code:v1:test-pepper", msg="123456") 的十六进制。 */
    private static final String GOLDEN_123456 =
            "85d3b15ac42dfd9d5b96457f703bcf60a918292f1aaa8c030c57fe4efac75667";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VerifyCodeConfig.class, PropertiesConfig.class);

    @Test
    void hasherShouldBeWiredFromEmailProperties() {
        runner.withPropertyValues("email.verify-code-pepper=test-pepper")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(VerifyCodeHasher.class);
                    assertThat(ctx.getBean(VerifyCodeHasher.class).hash("123456"))
                            .isEqualTo(GOLDEN_123456);
                });
    }

    @Test
    void contextShouldFailWhenPepperMissing() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("pepper");
        });
    }

    @Test
    void contextShouldFailWhenPepperIsTemplatePlaceholder() {
        runner.withPropertyValues("email.verify-code-pepper=CHANGE_ME_JASYPT_PASSWORD")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    /**
     * 仓库内公开的 dev pepper，若未显式放行，必须把上下文拒之门外。
     *
     * <p>这条是「默认 profile = dev」风险的回归防线：漏设 {@code SPRING_PROFILES_ACTIVE=prod} 时
     * pepper 会取到 {@code dev-only-insecure-pepper}，默认必须失败，而不是静默以弱密钥运行。</p>
     */
    @Test
    void contextShouldFailWhenPepperIsDevPepperWithoutExplicitAllow() {
        runner.withPropertyValues("email.verify-code-pepper=dev-only-insecure-pepper")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("allow-insecure-pepper");
                });
    }

    /** 显式置 {@code email.verify-code-allow-insecure-pepper=true}（dev / test profile 才应如此）后，dev pepper 才被接受。 */
    @Test
    void contextShouldStartWhenDevPepperExplicitlyAllowed() {
        runner.withPropertyValues(
                        "email.verify-code-pepper=dev-only-insecure-pepper",
                        "email.verify-code-allow-insecure-pepper=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(VerifyCodeHasher.class);
                    assertThat(ctx.getBean(VerifyCodeHasher.class).hash("123456"))
                            .matches("[0-9a-f]{64}");
                });
    }

    /**
     * 只登记 {@code @ConfigurationProperties} 类型本身。
     * {@code @EnableConfigurationProperties} 会连带注册 {@code ConfigurationPropertiesBindingPostProcessor}，
     * 所以这里不需要额外的自动配置就能完成绑定。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmailProperties.class)
    static class PropertiesConfig {
    }
}
