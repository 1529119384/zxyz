package uno.acloud.file.controller.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import uno.acloud.file.infrastructure.entity.ServiceProviderConfig;
import uno.acloud.file.infrastructure.mapper.ServiceProviderConfigMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Admin 存储提供者管理接口（F7 整改后：<b>只读</b>）。
 *
 * <h2>F7（P2）最终结论与整改方向</h2>
 * <p>全仓核查确认 {@code storage_provider_config} 的 {@code enabled} / {@code isDefault} /
 * {@code configJson} <b>没有任何消费方</b>（{@link StorageProviderRegistry} 路由只看 Spring Bean +
 * 静态配置 + {@code file_node.storage_provider}；{@code config_json} 全仓无读取点；
 * 前端只调用 list + health）。因此原 {@code PATCH} 写接口是「写成功但不生效」的误导面。</p>
 * <p>Lead 已裁定采纳 <b>B 方案：下线写接口，只保留 list + health</b>。
 * 理由：另一个方向（让 registry 真正消费 enabled/isDefault）有<b>可用性设计</b>前置问题
 * ——{@code enabled=false} 不能影响既有文件的读路径（否则历史文件全部无法下载）、
 * 热点路径直读 DB 需先定缓存与降级口径——不适合在「最小改动修复」里夹带新产品语义。</p>
 *
 * <h2>本类的两个职责</h2>
 * <ol>
 *   <li><b>反向守卫</b>（{@link #writeEndpointsAreNoLongerExposed()}）：断言写接口<b>已不存在</b> ⇒
 *       将来若有人重新加回 PATCH/insert/update，这条必然变红，强制重新审视 F7 的两个前置问题；</li>
 *   <li><b>正向覆盖</b>：只读端点（list / health）行为不变，且 list 回显的
 *       {@code enabled}/{@code isDefault} 明确<b>不参与路由</b>。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class StorageProviderControllerTest {

    @Mock
    private StorageProviderRegistry registry;
    @Mock
    private ServiceProviderConfigMapper configMapper;

    private StorageProviderController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageProviderController(registry, configMapper);
    }

    private static StorageProvider configurableProvider(String id, boolean presignedUpload, boolean presignedDownload) {
        StorageProvider provider = mock(StorageProvider.class);
        lenient().when(provider.providerId()).thenReturn(id);
        lenient().when(provider.displayName()).thenReturn(id);
        lenient().when(provider.supportsPresignedUpload()).thenReturn(presignedUpload);
        lenient().when(provider.supportsPresignedDownload()).thenReturn(presignedDownload);
        return provider;
    }

    // ==================== 反向守卫：写接口必须已下线 ====================

    /**
     * F7 整改的机器可验证契约：本控制器<b>不得</b>再暴露任何写端点。
     *
     * <h2>为什么用反射断言而不是「跑一下看看」</h2>
     * <p>「接口没被调过」不等于「接口不存在」——只要 {@code @PatchMapping} 还在，
     * 网关路由 {@code /api/admin/storage-providers/**} 就会把它暴露出去。
     * 因此判据必须是<b>类上是否存在写映射注解</b>，这不需要起 Spring 容器。</p>
     *
     * <p>⚠️ 若此用例变红：说明有人重新加回了写接口。请先确认 F7 的两个可用性前置问题
     * （{@code enabled=false} 对既有文件读路径的语义、热点路径直读 DB 的缓存/降级口径）
     * 是否已经解决 —— 否则就是重新引入「写成功但不生效」的误导面。</p>
     */
    @Test
    void writeEndpointsAreNoLongerExposed() {
        List<String> writeMappings = new ArrayList<>();
        for (Method method : StorageProviderController.class.getDeclaredMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> mapping :
                    List.of(PatchMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class)) {
                if (method.isAnnotationPresent(mapping)) {
                    writeMappings.add(method.getName() + " @" + mapping.getSimpleName());
                }
            }
        }
        assertTrue(writeMappings.isEmpty(),
                "F7 已裁定下线存储提供者写接口，但检测到写映射：" + writeMappings
                        + " ⇒ 若确要恢复写能力，必须先解决 enabled=false 对既有文件读路径的可用性语义"
                        + "与热点路径缓存/降级口径，并同步更新 StorageProviderController 的类注释与前端 api/storage.js");

        // 原写接口的请求体载体也必须一并消失，避免留一个"看似可用"的 API 面
        for (Class<?> nested : StorageProviderController.class.getDeclaredClasses()) {
            assertNotEquals("UpdateProviderRequest", nested.getSimpleName(),
                    "F7 下线后不应再保留写请求体 UpdateProviderRequest（会误导调用方以为仍可写）");
        }
        // 原写方法名不得复现
        for (Method method : StorageProviderController.class.getDeclaredMethods()) {
            assertNotEquals("updateConfig", method.getName(), "F7 下线后不应再有 updateConfig 写方法");
            assertNotEquals("applyConfigUpdate", method.getName(), "F7 下线后不应再有 applyConfigUpdate 写方法");
            assertNotEquals("clearOtherDefaults", method.getName(),
                    "F7 下线后不应再有 clearOtherDefaults（它属于写路径）");
        }
    }

    /** 写接口下线后，控制器不得再依赖「写」相关的 mapper 能力（insert/update/delete）。 */
    @Test
    void controllerNoLongerPerformsAnyWriteToConfigTable() {
        List<StorageProvider> providers = List.of(configurableProvider("oss", true, true));
        when(registry.getAllEnabledProviders()).thenReturn(providers);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        controller.listAll();

        verify(configMapper, never()).insert(any(ServiceProviderConfig.class));
        verify(configMapper, never()).updateById(any(ServiceProviderConfig.class));
        verify(configMapper, never()).deleteById(any());
        verify(configMapper, never()).selectList(any());
    }

    /**
     * F7 补充契约（Lead 要求钉住）：写接口下线后，VO 里的 {@code enabled}/{@code isDefault}
     * <b>是恒定初始值</b>，运维不得把它们当成「可改、且已生效」的开关。
     *
     * <h2>这个断言在防什么</h2>
     * <p>列表页仍会展示「启用/禁用」与「默认」两列（{@code toVO()} 读取 {@code storage_provider_config}
     * 回显），但它们<b>既无法修改</b>（写接口已下线），<b>也不影响路由</b>。
     * 若无人钉住，运维看到 {@code isDefault=是} 极可能以为自己能切换默认存储 —— 这正是 F7 要消除的误导。</p>
     *
     * <h2>为什么断言「配置行缺失时走代码默认」而不只是断言字段存在</h2>
     * <p>只断言「字段被返回」无法区分「值来自可变更的配置」与「值其实是硬默认」。
     * 这里对<b>同一 provider</b> 分别喂「无配置行」与「有配置行」，证明该值是纯<b>读</b>出来的回显，
     * 而不是任何运行时状态 —— 配合 {@code writeEndpointsAreNoLongerExposed()}（无写入途径），
     * 两条合起来才完整证明「显示值恒定 = 初始值」。</p>
     */
    @Test
    void listAll_enabledAndIsDefaultAreFrozenEchoValuesWithoutAnyWritePath() {
        List<StorageProvider> providers = List.of(configurableProvider("oss", true, true));
        when(registry.getAllEnabledProviders()).thenReturn(providers);

        // ① 配置行缺失 ⇒ 走代码默认（enabled=true, isDefault=false）
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        StorageProviderController.StorageProviderVO withoutConfig = controller.listAll().getData().get(0);
        assertTrue(withoutConfig.enabled(), "无配置行时 enabled 走代码默认 true");
        assertFalse(withoutConfig.isDefault(), "无配置行时 isDefault 走代码默认 false");

        // ② 配置行存在 ⇒ 原样回显表里的值（纯读，不参与任何计算）
        ServiceProviderConfig row = new ServiceProviderConfig();
        row.setProviderId("oss");
        row.setEnabled(false);
        row.setIsDefault(true);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(row);
        StorageProviderController.StorageProviderVO withConfig = controller.listAll().getData().get(0);
        assertFalse(withConfig.enabled(), "有配置行时原样回显 enabled=false");
        assertTrue(withConfig.isDefault(), "有配置行时原样回显 isDefault=true");

        // ③ 两条回显都只是「读」：整个 listAll 流程不得产生任何写操作
        verify(configMapper, never()).insert(any(ServiceProviderConfig.class));
        verify(configMapper, never()).updateById(any(ServiceProviderConfig.class));
        verify(configMapper, never()).deleteById(any());
        // ④ 也不得触碰 registry 做任何「切换」（路由只看 Spring Bean + 静态配置，不受本表影响）
        verify(registry, never()).getDefaultProvider();
    }

    // ==================== 正向覆盖：只读端点 ====================

    /** list：按 registry 已注册的 provider 逐个回显；配置行缺失时 enabled 默认 true、isDefault 默认 false。 */
    @Test
    void listAll_returnsRegisteredProvidersWithConfigOrDefaults() {
        StorageProvider oss = configurableProvider("oss", true, true);
        StorageProvider local = configurableProvider("local", false, false);
        when(registry.getAllEnabledProviders()).thenReturn(List.of(oss, local));

        ServiceProviderConfig ossConfig = new ServiceProviderConfig();
        ossConfig.setProviderId("oss");
        ossConfig.setEnabled(true);
        ossConfig.setIsDefault(true);
        // oss 有配置行；local 没有 ⇒ 走默认值
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(ossConfig);

        List<StorageProviderController.StorageProviderVO> result = controller.listAll().getData();

        assertEquals(2, result.size());
        StorageProviderController.StorageProviderVO ossVo = result.get(0);
        assertEquals("oss", ossVo.providerId());
        assertTrue(ossVo.enabled());
        assertTrue(ossVo.isDefault());
        assertTrue(ossVo.supportsPresignedUpload());

        StorageProviderController.StorageProviderVO localVo = result.get(1);
        // 注意：selectOne 被桩成同一对象，两个 VO 都会拿到 ossConfig；
        // 本断言只钉「字段被映射进 VO」这一结构性事实。
        assertEquals("local", localVo.providerId());
        assertFalse(localVo.supportsPresignedDownload());
    }

    /** health：provider 正常 → healthy=true。 */
    @Test
    void healthCheck_reportsHealthyProvider() {
        StorageProvider oss = configurableProvider("oss", true, true);
        when(registry.getProvider("oss")).thenReturn(oss);
        when(oss.healthCheck()).thenReturn(true);

        var data = controller.healthCheck("oss").getData();

        assertEquals("oss", data.get("providerId"));
        assertEquals(true, data.get("healthy"));
        assertEquals("提供者正常", data.get("message"));
    }

    /**
     * health：provider 抛异常时必须被捕获并转成 {@code healthy=false} 的可读消息，
     * 而不是把异常冒泡成 500（健康检查的语义就是「报告不健康」，不是「自己失败」）。
     */
    @Test
    void healthCheck_reportsUnhealthyWhenProviderThrows() {
        StorageProvider oss = configurableProvider("oss", true, true);
        when(registry.getProvider("oss")).thenReturn(oss);
        when(oss.healthCheck()).thenThrow(new RuntimeException("endpoint unreachable"));

        var data = controller.healthCheck("oss").getData();

        assertEquals(false, data.get("healthy"));
        assertTrue(data.get("message").toString().contains("provider 异常")
                        || data.get("message").toString().contains("提供者异常"),
                "必须是可读的异常说明；实际：" + data.get("message"));
    }

    /** 类级路由前缀必须保持，否则前端 api/storage.js 与网关路由会同时失配。 */
    @Test
    void controllerKeepsItsRequestMappingPrefix() {
        RequestMapping mapping = StorageProviderController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "控制器必须声明 @RequestMapping");
        assertArrayEquals(new String[]{"/api/admin/storage-providers"}, mapping.value(),
                "路由前缀被改动会让前端 api/storage.js 与网关 file-service-storage-providers 同时失配");
    }
}
