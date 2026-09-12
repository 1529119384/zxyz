package uno.acloud.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestClientAutoConfiguration} 的两个 Bean 都被
 * {@code @ConditionalOnMissingBean(name = "...")} 保护，源码注释明确说这是为了
 * "允许各服务自定义而不误伤其它同类型 Bean"。这里把该契约钉死：
 * <b>同名的用户 Bean 必须让自动配置退让，不同名的必须允许共存</b>。
 *
 * <p>注：父 pom 的 jacoco {@code excludes} 把 {@code uno/acloud/starter/*AutoConfiguration*}
 * 排除在覆盖率分母之外，所以本类测试<b>不计入</b>本模块的 LINE 指标 —— 它的价值是钉死装配契约，
 * 不是刷覆盖率，别因为"改了这里数字没动"就把它删掉。</p>
 */
class RestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class));

    @Test
    void autoConfiguration_registersBuilderAndRestClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("loadBalancedRestClientBuilder");
            assertThat(ctx).hasBean("restClient");
            assertThat(ctx.getBean("loadBalancedRestClientBuilder"))
                    .isInstanceOf(RestClient.Builder.class);
            assertThat(ctx.getBean("restClient")).isInstanceOf(RestClient.class);
        });
    }

    @Test
    void builder_whenUserDefinesSameName_autoConfigBacksOff() {
        runner.withUserConfiguration(SameNameBuilderConfig.class).run(ctx -> {
            // 条件命中 ⇒ 自动配置那个不再注册（否则同名 Bean 定义冲突、上下文起不来）
            assertThat(ctx).hasSingleBean(RestClient.Builder.class);
            assertThat(ctx.getBean("loadBalancedRestClientBuilder"))
                    .isSameAs(SameNameBuilderConfig.CUSTOM);
            assertThat(ctx).hasBean("restClient");
        });
    }

    @Test
    void restClient_whenUserDefinesSameName_autoConfigBacksOff() {
        runner.withUserConfiguration(SameNameRestClientConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(RestClient.class);
            assertThat(ctx.getBean("restClient")).isSameAs(SameNameRestClientConfig.CUSTOM);
        });
    }

    @Test
    void builder_whenUserDefinesDifferentName_autoConfigKeepsItsOwn() {
        // project-service 的 emailRestClient/imRestClient 属于这种场景：
        // 条件按 **name** 判定，不同名 ⇒ 自动配置的 @LoadBalanced Builder 必须保留，
        // 否则 restClient(@LoadBalanced RestClient.Builder) 的注入点会找不到 Bean。
        runner.withUserConfiguration(OtherNameBuilderConfig.class).run(ctx -> {
            assertThat(ctx).hasBean("loadBalancedRestClientBuilder");
            assertThat(ctx).hasBean("myBuilder");
            assertThat(ctx).hasBean("restClient");
        });
    }

    @Test
    void restClient_whenUserDefinesDifferentName_autoConfigKeepsItsOwn() {
        runner.withUserConfiguration(OtherNameRestClientConfig.class).run(ctx -> {
            assertThat(ctx).hasBean("restClient");
            assertThat(ctx).hasBean("myRestClient");
        });
    }

    // ==================== 测试用配置 ====================

    @Configuration(proxyBeanMethods = false)
    static class SameNameBuilderConfig {

        static final RestClient.Builder CUSTOM = RestClient.builder();

        @Bean
        @LoadBalanced
        RestClient.Builder loadBalancedRestClientBuilder() {
            return CUSTOM;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameNameRestClientConfig {

        static final RestClient CUSTOM = RestClient.create();

        @Bean
        RestClient restClient() {
            return CUSTOM;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherNameBuilderConfig {

        /** 刻意不加 @LoadBalanced：否则与自动配置的 Builder 一起造成注入歧义。 */
        @Bean
        RestClient.Builder myBuilder() {
            return RestClient.builder();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherNameRestClientConfig {

        @Bean
        RestClient myRestClient() {
            return RestClient.create();
        }
    }
}
