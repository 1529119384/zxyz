package uno.acloud.file.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F14（P3 异常处理）：一行脏 provider 数据不得饿死其余 provider 的待删对象。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code deletePendingObjects} 按 {@code storage_provider} 分组遍历，每组开头调
 * {@code registry.getProvider(groupKey)} —— 而它对<b>未注册</b>的 providerId 抛
 * {@link BusinessException}。该异常会从 for 循环中<b>整体冒泡中断</b>：</p>
 * <ul>
 *   <li>只要有一行脏数据（例如已下线但仍留在 {@code file_node.storage_provider} 里的 {@code local}），
 *       这一轮清理在遇到它之后就<b>完全停止</b>；</li>
 *   <li>{@code Map} 的遍历顺序不保证 ⇒ 哪些 provider 被处理、哪些被饿死是<b>不确定</b>的，
 *       表现为「每轮都有对象删不掉」的间歇性泄漏；</li>
 *   <li>观测面上只有一条 warn，运维很难意识到「其余 provider 全被饿死」。</li>
 * </ul>
 *
 * <h2>修复后的语义</h2>
 * <p>取 provider 失败时<b>只跳过该组</b>（该组对象保持 PENDING_DELETE，下一轮仍会被
 * {@code listPendingDeletes} 捞起），其余组照常处理；并按组大小计入
 * {@code file.object.delete.provider.unavailable} 指标用于告警。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileObjectPhysicalDeleteExecutorTest {

    @Mock
    private FileObjectRefMapper fileObjectRefMapper;
    @Mock
    private StorageProviderRegistry registry;

    private MeterRegistry meterRegistry;
    private FileObjectPhysicalDeleteExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        executor = new FileObjectPhysicalDeleteExecutor(fileObjectRefMapper, registry, meterRegistry);
    }

    private static FileObjectRef pending(String objectKey, String provider) {
        FileObjectRef ref = new FileObjectRef();
        ref.setObjectKey(objectKey);
        ref.setStorageProvider(provider);
        ref.setDeleteRetryCount(0);
        return ref;
    }

    private StorageProvider workingProvider(String id) {
        StorageProvider provider = mock(StorageProvider.class);
        lenient().when(provider.providerId()).thenReturn(id);
        return provider;
    }

    /**
     * 核心断言：注册表里没有的 provider 组被跳过，但<b>其余组必须照常删完</b>。
     * <p>这直接对应「一行脏 provider 数据让其余 provider 每轮全部饿死」的修复。</p>
     */
    @Test
    void deletePendingObjects_shouldSkipUnknownProviderGroupAndStillProcessOthers() {
        List<FileObjectRef> pendingRefs = List.of(
                pending("files/good-a", "oss"),
                pending("files/bad-a", "local"),   // 未注册的脏数据
                pending("files/good-b", "oss")
        );
        when(fileObjectRefMapper.listPendingDeletes(anyString(), anyInt())).thenReturn(pendingRefs);

        StorageProvider oss = workingProvider("oss");
        when(registry.getProvider("oss")).thenReturn(oss);
        when(registry.getProvider("local"))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "存储提供者不存在: local"));

        // 两个 oss 对象都成功走完「claim → delete → markDeleted」
        when(fileObjectRefMapper.markDeleting(anyString(), anyString(), anyString())).thenReturn(1);
        when(fileObjectRefMapper.markDeleted(anyString(), anyString(), anyString())).thenReturn(1);

        int success = executor.deletePendingObjects(100);

        assertEquals(2, success, "未注册的 local 组必须被跳过，但两个 oss 对象必须照常删除完成（F14）");
        verify(oss).deleteObject("files/good-a");
        verify(oss).deleteObject("files/good-b");
        // 脏数据组不得被误删（provider 都取不到，绝不能猜一个 provider 去删）
        verify(fileObjectRefMapper, never()).markDeleting(eq("files/bad-a"), anyString(), anyString());
    }

    /** 被跳过的组必须计入可告警指标（否则「整组饿死」在观测面上不可见）。 */
    @Test
    void deletePendingObjects_recordsMetricForSkippedProviderGroup() {
        when(fileObjectRefMapper.listPendingDeletes(anyString(), anyInt()))
                .thenReturn(List.of(pending("files/bad-a", "local"), pending("files/bad-b", "local")));
        when(registry.getProvider("local")).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "不存在"));

        assertEquals(0, executor.deletePendingObjects(100));

        double skipped = meterRegistry.get("file.object.delete.provider.unavailable")
                .tag("provider", "local")
                .counter()
                .count();
        assertEquals(2.0, skipped,
                "指标必须反映被跳过的对象**数量**（2 条），而不是只记一次事件 —— "
                        + "否则无法判断这次跳过影响面有多大");
    }

    /** 未注册 provider 抛出的异常本身不得冒泡（否则整轮任务中断，等价于修复前的行为）。 */
    @Test
    void deletePendingObjects_shouldNotPropagateUnknownProviderException() {
        when(fileObjectRefMapper.listPendingDeletes(anyString(), anyInt()))
                .thenReturn(List.of(pending("files/bad-a", "local")));
        when(registry.getProvider("local")).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "不存在"));

        assertDoesNotThrow(() -> executor.deletePendingObjects(100));
    }

    /** {@code storage_provider} 为 null/空白时按 {@code oss} 兜底（既有行为，不得回退）。 */
    @Test
    void deletePendingObjects_treatsBlankProviderAsOss() {
        when(fileObjectRefMapper.listPendingDeletes(anyString(), anyInt()))
                .thenReturn(List.of(pending("files/blank-provider", "  ")));
        StorageProvider oss = workingProvider("oss");
        when(registry.getProvider("oss")).thenReturn(oss);
        when(fileObjectRefMapper.markDeleting(anyString(), anyString(), anyString())).thenReturn(1);
        when(fileObjectRefMapper.markDeleted(anyString(), anyString(), anyString())).thenReturn(1);

        assertEquals(1, executor.deletePendingObjects(100));
        verify(oss).deleteObject("files/blank-provider");
    }

    /** limit 归一化：非正数必须至少为 1（否则下游 LIMIT 0 会静默什么都不删）。 */
    @Test
    void deletePendingObjects_clampsNonPositiveLimit() {
        when(fileObjectRefMapper.listPendingDeletes(anyString(), anyInt())).thenReturn(List.of());

        executor.deletePendingObjects(0);

        verify(fileObjectRefMapper).listPendingDeletes(anyString(), eq(1));
    }
}
