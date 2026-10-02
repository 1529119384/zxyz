package uno.acloud.file.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.service.impl.FileObjectReferenceManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F4（P2，异常处理/MQ 一致性）：用户删除事件幂等键的<b>两段式</b>语义。
 *
 * <h2>修复前的缺陷</h2>
 * <p>旧实现只有一个 {@code TTL = 24h} 的键，且它是「<b>认领</b>」标记而非「<b>完成</b>」标记：
 * {@code setIfAbsent} 成功之后进程若在清理跑完前崩溃/被 kill，键会残留整整 24 小时。
 * 期间所有重投递都命中「重复用户删除事件，跳过处理」，日志还把「半途而废」误报成「重复事件」
 * ⇒ 该用户的个人空间文件最长 24 小时清不掉，且从日志上完全看不出异常。</p>
 *
 * <h2>修复后的语义</h2>
 * <ul>
 *   <li>{@code processing} 键：短 TTL（10 分钟）⇒ 崩溃后最多 10 分钟就能被重投递接管；</li>
 *   <li>{@code done} 键：长 TTL（24 小时），只在清理<b>真正成功</b>后写入 ⇒ 真正的重复投递仍被拦住。</li>
 * </ul>
 *
 * <p>本类用 Mockito 直接观测写入 Redis 的 key/TTL，无需真实 Redis —— 本仓 CI 在无 Docker 时
 * 集成测试整体跳过，若只依赖集成测试，这个缺陷就失去了回归网。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileUserCleanupServiceTest {

    private static final String PROCESSING_PREFIX = "mq:idempotent:user:deleted:file:processing:";
    private static final String DONE_PREFIX = "mq:idempotent:user:deleted:file:done:";

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileObjectReferenceManager fileObjectReferenceManager;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private FileUserCleanupService service;

    @BeforeEach
    void setUp() {
        service = new FileUserCleanupService(fileMapper, fileObjectReferenceManager, stringRedisTemplate);
    }

    /**
     * 首次处理：done 键不存在 ⇒ 应成功认领 processing 键，且 TTL 必须是
     * <b>短</b>的分钟级（而不是修复前那种 24 小时）—— 这正是崩溃后能快速恢复的关键。
     */
    @Test
    void tryAcquire_usesShortProcessingTtlSoCrashRecoversQuickly() {
        when(stringRedisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertTrue(service.tryAcquireIdempotencyKey(7L));

        // 必须写成「短 TTL 的 processing 键」：单位是 MINUTES 且数值远小于 24 小时
        verify(valueOperations).setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.HOURS));
    }

    /**
     * 已完成（done 键在）⇒ 必须直接拒绝，且<b>不得</b>再去碰 processing 键。
     * <p>这条守住「真正的重复投递仍被拦住」——两段式不能退化成「任何投递都重新跑一遍」。</p>
     */
    @Test
    void tryAcquire_returnsFalseWhenCleanupAlreadyCompleted() {
        when(stringRedisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(true);

        assertFalse(service.tryAcquireIdempotencyKey(7L));

        verify(stringRedisTemplate, never()).opsForValue();
        assertTrue(service.isCleanupCompleted(7L));
    }

    /** 并发投递：processing 键已被占 ⇒ setIfAbsent 返回 false/null，认领失败。 */
    @Test
    void tryAcquire_returnsFalseWhenAlreadyInProgress() {
        when(stringRedisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertFalse(service.tryAcquireIdempotencyKey(7L));
        assertFalse(service.isCleanupCompleted(7L), "processing 阶段尚未完成，done 键必须不存在");
    }

    /** {@code setIfAbsent} 返回 null（连接异常等）必须按「未认领」处理，不能当成成功。 */
    @Test
    void tryAcquire_treatsNullResultAsNotAcquired() {
        when(stringRedisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        assertFalse(service.tryAcquireIdempotencyKey(7L),
                "null 不能被当成 true（Boolean.TRUE.equals 才是判据）");
    }

    /**
     * 成功路径：必须先写<b>长 TTL 的 done 键</b>，再删 processing 键。
     * <p>顺序很关键：先写 done 才能保证「两步之间崩溃」时重复投递仍被 done 拦住；
     * 反过来（先删 processing）会出现两个键都不存在的窗口，重复投递会白跑一遍清理。</p>
     */
    @Test
    void markCleanupCompleted_writesLongLivedDoneKeyThenReleasesProcessingKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.markCleanupCompleted(7L);

        verify(valueOperations).set(eq(DONE_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.HOURS));
        verify(stringRedisTemplate).delete(PROCESSING_PREFIX + 7L);

        // done 必须先于 delete 发生
        InOrder inOrder = inOrder(valueOperations, stringRedisTemplate);
        inOrder.verify(valueOperations).set(eq(DONE_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.HOURS));
        inOrder.verify(stringRedisTemplate).delete(PROCESSING_PREFIX + 7L);
    }

    /** 失败回滚路径只释放 processing 键，绝不写 done（否则重投递会被永久跳过）。 */
    @Test
    void releaseIdempotencyKey_neverWritesDoneMarker() {
        service.releaseIdempotencyKey(7L);

        verify(stringRedisTemplate).delete(PROCESSING_PREFIX + 7L);
        verify(stringRedisTemplate, never()).opsForValue();
    }

    /** 清理主体：有文件时收集子树、硬删除、释放 OSS 引用。 */
    @Test
    void cleanupUserPersonalFiles_deletesTreeAndReleasesReferences() {
        when(fileMapper.getPersonalRootFileIds(7L)).thenReturn(List.of(1L));
        when(fileMapper.collectDescendantIds(List.of(1L))).thenReturn(List.of(1L, 2L));
        when(fileMapper.getOssKeysByIds(List.of(1L, 2L))).thenReturn(List.of("uuid-a"));
        when(fileMapper.reallyDeleteByIds(List.of(1L, 2L), 7L)).thenReturn(2);

        service.cleanupUserPersonalFiles(7L);

        verify(fileObjectReferenceManager).releaseReferences(List.of("uuid-a"));
    }

    /** 用户没有个人空间文件时：不得去收集子树，也不得释放引用（避免无意义查询与误调）。 */
    @Test
    void cleanupUserPersonalFiles_noRoots_returnsEarly() {
        when(fileMapper.getPersonalRootFileIds(7L)).thenReturn(List.of());

        service.cleanupUserPersonalFiles(7L);

        verify(fileMapper, never()).collectDescendantIds(anyList());
        verifyNoInteractions(fileObjectReferenceManager);
    }
}
