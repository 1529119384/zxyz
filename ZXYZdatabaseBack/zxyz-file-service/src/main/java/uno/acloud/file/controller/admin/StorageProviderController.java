package uno.acloud.file.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.file.infrastructure.entity.ServiceProviderConfig;
import uno.acloud.file.infrastructure.mapper.ServiceProviderConfigMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.util.List;
import java.util.Map;

/**
 * 存储提供者管理控制器 —— <b>只读</b>（列出 + 健康检查）。
 *
 * <h2>🔴 F7（P2）：PATCH 写接口已下线（2026-10-03）—— 为什么</h2>
 *
 * <p>本控制器此前还提供 {@code PATCH /api/admin/storage-providers/{providerId}}，
 * 把 {@code enabled} / {@code isDefault} / {@code configJson} 写进 {@code storage_provider_config}。
 * 全仓核查确认：<b>这三个字段没有任何消费方</b>：</p>
 * <ul>
 *   <li>{@link StorageProviderRegistry} 的路由只看 Spring Bean 与静态配置 ——
 *       {@code getProvider(id)} 查的是构造期注入的 Bean 映射；
 *       {@code getDefaultProvider()} 用的是 {@code app.storage.default-provider}（Nacos 配置）；
 *       {@code resolveForFile(node)} 用的是 {@code file_node.storage_provider} 列。
 *       三者都<b>不读</b>本表。</li>
 *   <li>全仓 grep：{@code storage_provider_config} 仅出现在 V2 迁移与实体 {@code @TableName}；
 *       {@code ServiceProviderConfig} / {@code ServiceProviderConfigMapper} 仅被本控制器引用；
 *       {@code config_json} 全仓<b>无任何读取点</b>。</li>
 *   <li>前端 {@code StorageAdmin.vue} 只调用 list + health，<b>没有</b>调用更新接口
 *       （{@code api/storage.js} 里的 {@code updateStorageProvider} 无消费者）。</li>
 * </ul>
 *
 * <p><b>下线理由</b>：写入成功、接口返回 200、列表页如实回显新状态，但<b>真实路由完全不变</b> ——
 * 管理员看着「已禁用」，实际上该 provider 仍在被使用。DB 状态与真实行为脱钩，是对运维的实质性误导。
 * 保留一个「看起来能改、实际不改」的写接口，其误导成本高于收益。</p>
 *
 * <p><b>为什么是下线而不是「让 registry 真正消费」</b>：后者有两个<b>可用性设计</b>前置问题必须先解决，
 * 不适合在「按报告最小改法修复」里顺手做：</p>
 * <ol>
 *   <li>{@code enabled = false} <b>不能</b>影响 {@code resolveForFile} —— 已有文件仍存放在该 provider 上，
 *       禁用若导致读路径抛错，用户的历史文件将全部无法下载（「禁用」被放大成「数据不可用」）；</li>
 *   <li>{@code resolveForFile} 在每次下载时都会被调用，直读 DB 会给热点路径加一次查询，
 *       需先定好缓存/TTL 与 DB 不可用时的降级口径。</li>
 * </ol>
 * <p>等真正要做多 provider 路由时，连同上面两点一起设计。</p>
 *
 * <h2>⚠️ 保留但当前无消费方：实体、Mapper 与表</h2>
 * <p>{@link ServiceProviderConfig}、{@link ServiceProviderConfigMapper} 与
 * {@code storage_provider_config} 表（V2 迁移）<b>刻意保留</b>：</p>
 * <ul>
 *   <li>表结构属 schema 面，删表需要独立的迁移与数据评估，不随接口下线一并做；</li>
 *   <li>{@code toVO()} 仍<b>读取</b>该表回显 {@code enabled}/{@code isDefault}，
 *       因此实体与 Mapper 仍有真实引用（不是死代码）。</li>
 * </ul>
 *
 * <p><b>🔴 这两个字段目前<b>没有任何写入途径</b>（写接口已随 F7 下线），因此列表里显示的值
 * 是<b>恒定的初始值</b>（V2 迁移的种子行 {@code oss: enabled=1, is_default=1}；其余 provider
 * 无配置行时走代码默认 {@code enabled=true, isDefault=false}）—— 它既不代表真实路由状态，
 * 也<b>不代表任何可变更的意图</b>。</b></p>
 *
 * <p>⇒ <b>运维须知</b>：看到 {@code isDefault=是} 或 {@code 启用/禁用} 标签时，
 * <b>不要据此认为自己能改、或以为改过生效了</b>：当前既没有接口能改，改也不影响路由。
 * 该字段仅作为「配置表里曾经记录的意图值」的历史回显保留。</p>
 *
 * <p>真实生效的默认提供者由 {@code app.storage.default-provider}（Nacos 静态配置）决定；
 * 某个具体文件用哪个 provider 由其 {@code file_node.storage_provider} 列决定。
 * 需要确认实际路由时，看这两处，不要看本接口的回显。</p>
 *
 * <h2>回归网</h2>
 * <p>{@code StorageProviderControllerTest} 断言写接口<b>已不再暴露</b>（反射检查无 PATCH 映射方法 +
 * 无 {@code UpdateProviderRequest}），因此「将来有人重新加回写接口」必然触发变红。</p>
 */
@Slf4j
@Tag(name = "存储提供者管理", description = "Admin 存储提供者只读查询接口（列出 / 健康检查）")
@RestController
@RequestMapping("/api/admin/storage-providers")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class StorageProviderController {

    private final StorageProviderRegistry registry;
    private final ServiceProviderConfigMapper configMapper;

    public StorageProviderController(StorageProviderRegistry registry,
                                     ServiceProviderConfigMapper configMapper) {
        this.registry = registry;
        this.configMapper = configMapper;
    }

    @Operation(summary = "列出所有存储提供者")
    @GetMapping
    public Result<List<StorageProviderVO>> listAll() {
        List<StorageProvider> providers = registry.getAllEnabledProviders();
        List<StorageProviderVO> voList = providers.stream()
                .map(this::toVO)
                .toList();
        return Result.of(voList);
    }

    @Operation(summary = "存储提供者健康检查")
    @GetMapping("/{providerId}/health")
    public Result<Map<String, Object>> healthCheck(
            @Parameter(description = "提供者标识") @PathVariable String providerId) {

        StorageProvider provider = registry.getProvider(providerId);
        boolean healthy;
        String message;
        try {
            healthy = provider.healthCheck();
            message = healthy ? "提供者正常" : "提供者异常";
        } catch (Exception e) {
            healthy = false;
            message = "提供者异常: " + e.getMessage();
            log.error("存储提供者健康检查失败，providerId: {}", providerId, e);
        }

        return Result.of(Map.of(
                "providerId", providerId,
                "healthy", healthy,
                "message", message
        ));
    }

    private StorageProviderVO toVO(StorageProvider provider) {
        // 从配置表获取配置。⚠️ 只读回显；既**不参与路由决策**，也**没有任何写入途径**
        // （写接口已随 F7 下线）⇒ 这里返回的 enabled/isDefault 是**恒定初始值**，
        // 不要把它当成「可变更、且已生效」的开关（详见类注释的 F7 说明与运维须知）。
        ServiceProviderConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ServiceProviderConfig>()
                        .eq(ServiceProviderConfig::getProviderId, provider.providerId()));

        boolean enabled = config != null ? Boolean.TRUE.equals(config.getEnabled()) : true;
        boolean isDefault = config != null ? Boolean.TRUE.equals(config.getIsDefault()) : false;

        return new StorageProviderVO(
                provider.providerId(),
                provider.displayName(),
                enabled,
                isDefault,
                provider.supportsPresignedUpload(),
                provider.supportsPresignedDownload()
        );
    }

    /**
     * 存储提供者 VO。
     *
     * <p>⚠️ {@code enabled} / {@code isDefault} 是<b>配置表的历史意图值</b>，仅用于展示：
     * 它们<b>不代表真实路由状态</b>（真实路由由 {@link StorageProviderRegistry} 依据
     * Spring Bean 与 {@code app.storage.default-provider} 决定），
     * 而且当前<b>没有任何写入途径</b>（写接口已随 F7 下线）⇒ 这两个值<b>恒为初始值</b>。</p>
     *
     * <p><b>不要</b>把它们当作「可以改、改了会生效」的开关：既无法修改，修改也不影响路由。
     * 需要确认实际 provider 时，请看 {@code app.storage.default-provider}（默认提供者）
     * 与 {@code file_node.storage_provider}（单文件归属）。</p>
     */
    public record StorageProviderVO(
            String providerId,
            String displayName,
            boolean enabled,
            boolean isDefault,
            boolean supportsPresignedUpload,
            boolean supportsPresignedDownload
    ) {}
}
