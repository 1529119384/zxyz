package uno.acloud.common.config;

import uno.acloud.satoken.PermissionCache;
import uno.acloud.satoken.RemoteStpInterfaceImpl;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 为不需要自定义 StpInterface 的服务提供默认 Bean。
 * <p>
 * file-service 和 share-service 因重写 getRoleList() 返回空列表，需保留自定义实现。
 * team-service 直接调用本地权限服务，不使用远程基类。
 *（三者均声明了自己的 {@code StpInterface} Bean，由下面的
 * {@code @ConditionalOnMissingBean(StpInterface.class)} 自动让位。）</p>
 *
 * <p>使用 {@code @Value} 读取 {@code app.*} 属性是故意设计：
 * 各服务通过 application.yml 将共享的 {@code services.*} 映射到本地 {@code app.*} 命名空间，
 * 使此自动配置类无需知道具体服务的属性来源。</p>
 *
 * <p><b>关键修复一：不得使用 {@code @ConditionalOnBean(RestClient.class)}。</b>
 * 各服务的启动类以
 * {@code @ComponentScan(basePackages = {"uno.acloud.<svc>", "uno.acloud.common"})}
 * 显式扫描 {@code uno.acloud.common}，而本类位于该包下，因此会被“组件扫描”和
 * {@code AutoConfiguration.imports} <b>两条路径同时收录</b>。Spring 的
 * {@code ConfigurationClassParser} 中，被组件扫描的配置类先入 {@code configurationModel}，
 * 而自动配置类在 defer 阶段后入；且「已存在的非 imported 配置类会覆盖 imported 的」，
 * 因此扫描到的本类会先被处理。{@code @ConditionalOnBean} 的求值时机是
 * {@code REGISTER_BEAN}（即按 {@code configurationModel} 顺序注册 Bean 定义时），
 * 于是 RestClient（由 zxyz-starter 的自动配置提供、注册更晚）尚不存在 → 条件恒为 false
 * → StpInterface Bean 永不创建 → Sa-Token 回退到默认空实现
 * → 全部 {@code @SaCheckRole}/{@code @SaCheckPermission} 永远返回"无角色/无权限"。
 * 这正是 email/admin/im 等管理端接口 403 的根因。
 * <p>改为把 {@code RestClient} 作为<b>方法参数</b>注入：参数解析发生在 Bean
 * <b>实例化</b>阶段（{@code finishBeanFactoryInitialization}），此时所有 Bean 定义均已注册，
 * 与配置类注册顺序无关，故条件可安全删除。
 * {@code RestClient} 由 zxyz-starter 的 {@code restClient}（{@code @LoadBalanced}）提供，
 * 所有配置了 {@code app.team-service.base-url} 的服务都依赖 zxyz-starter，故必然存在；
 * 参数名与 Bean 名一致（{@code restClient}），即使服务另有同名类型 Bean（如
 * project-service 的 emailRestClient/imRestClient）也能按名唯一解析。</p>
 *
 * <p><b>关键修复二：仍需声明为 {@code @AutoConfiguration} 并注册进
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。</b>
 * 若将来某个服务收紧了组件扫描范围（或 {@code AutoConfigurationExcludeFilter} 生效把本类
 * 排除出扫描），只有 imports 注册能保证它仍被加载。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = "uno.acloud.starter.RestClientAutoConfiguration")
@ConditionalOnProperty(name = "app.team-service.base-url")
public class RemoteStpInterfaceAutoConfig {

    @Bean
    @ConditionalOnMissingBean(StpInterface.class)
    public StpInterface stpInterface(RestClient restClient,
                                     PermissionCache permissionCache,
                                     @Value("${app.team-service.base-url}") String teamServiceBaseUrl,
                                     ObjectMapper objectMapper,
                                     @Value("${app.internal-service-token:}") String internalServiceToken,
                                     @Value("${spring.application.name:unknown}") String sourceService,
                                     @Value("${app.internal-service-key:}") String selfServiceKey) {
        return new RemoteStpInterfaceImpl(restClient, teamServiceBaseUrl, objectMapper,
                internalServiceToken, sourceService, selfServiceKey, permissionCache);
    }
}
