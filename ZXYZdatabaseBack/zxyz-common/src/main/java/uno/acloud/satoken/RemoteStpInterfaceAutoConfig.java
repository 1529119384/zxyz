package uno.acloud.satoken;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 为「没有自定义 StpInterface」的服务提供默认实现（远程调用 team-service 取角色/权限）。
 * <p>
 * file-service / share-service 因重写 getRoleList() 返回空列表需自定义实现，team-service
 * 直接查本地库不使用远程基类 —— 三者各自声明了 {@code StpInterface} Bean，
 * 由下面 {@code @ConditionalOnMissingBean(StpInterface.class)} 自动让位。</p>
 *
 * <h3>为什么本类必须在 {@code uno.acloud.satoken} 包，而不能放在 {@code uno.acloud.common.config}</h3>
 * <p>各服务启动类都以 {@code @ComponentScan(basePackages = {"uno.acloud.&lt;svc&gt;", "uno.acloud.common"})}
 * 显式扫描 {@code uno.acloud.common}。凡位于该包下的类会被「组件扫描」与
 * {@code AutoConfiguration.imports} <b>两条路径同时收录</b>，而 Spring 的
 * {@code ConfigurationClassParser} 规则是「已存在的非 imported 配置类覆盖 imported 的」——
 * 被扫描到的那份<b>先</b>进 {@code configurationModel}，imports 那份被忽略。
 * 由于 {@code @ConditionalOnBean} 的求值阶段是 {@code REGISTER_BEAN}
 * （按 {@code configurationModel} 顺序注册 Bean 定义时逐个评估），扫描那份求值时
 * {@code RestClient}（由 zxyz-starter 的自动配置提供、注册更晚）尚不存在 → 恒 false
 * → Bean 永不创建 → Sa-Token 回退默认空实现 → 所有
 * {@code @SaCheckRole}/{@code @SaCheckPermission} 报 {@code {"code":4030}}（email/admin/im 403 根因）。
 * <br>放在 {@code uno.acloud.satoken} 后，<b>没有任何服务的 {@code @ComponentScan} 覆盖它</b>
 * （都只扫自身的 {@code uno.acloud.<svc>} 与 {@code uno.acloud.common}），
 * 因此本类只通过 {@code AutoConfiguration.imports} 加载，{@code @AutoConfigureAfter} 才真正生效，
 * 保证排在 {@code RestClientAutoConfiguration} 之后，{@code @ConditionalOnBean(RestClient.class)} 成立。</p>
 *
 * <h3>为什么必须保留 {@code @ConditionalOnBean(RestClient.class)}</h3>
 * <p>gateway 也扫描 {@code uno.acloud.common}、也带 {@code app.team-service.base-url} 属性
 * （来自共享的 {@code application-common.yml}），但它<b>只依赖 zxyz-common、不依赖 zxyz-starter</b>，
 * 因而没有 {@code RestClient} Bean。若去掉此条件，gateway 启动即报
 * 「required a bean of type 'RestClient' that could not be found」并崩溃重启（实测事故）。
 * 有了它，gateway 会安全跳过，而 9 个依赖 zxyz-starter 的服务正常拿到 Bean。</p>
 *
 * <p>依赖注入用「{@code RestClient} 方法参数」：参数解析在实例化阶段，
 * Bean 名 {@code restClient} 与 starter 提供的 Bean 同名，
 * 即便 context 里另有同类型 Bean（project-service 的 emailRestClient/imRestClient）也能按名唯一解析。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = "uno.acloud.starter.RestClientAutoConfiguration")
@ConditionalOnProperty(name = "app.team-service.base-url")
public class RemoteStpInterfaceAutoConfig {

    @Bean
    @ConditionalOnBean(RestClient.class)
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
