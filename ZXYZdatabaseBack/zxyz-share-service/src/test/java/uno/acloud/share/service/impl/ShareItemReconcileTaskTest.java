package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ShareItemReconcileTask} 单测。
 * <p>本类最重要的一条用例是 {@link #reconcile_shouldTreatMissingAndHardDeletedAsOrphanButKeepRecycleBin()}：
 * 它钉住「回收站里的文件不算孤儿」这个口径。这条口径一旦被改错，打开自动删除后会把用户回收站里的
 * 文件从分享中永久清掉（不可逆）—— 而单看代码很难发现，所以必须有测试守着。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShareItemReconcileTaskTest {

    @Mock
    private ShareMapper shareMapper;
    @Mock
    private ShareFileServiceClient fileServiceClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    // ==================== 辅助 ====================

    private ShareItemReconcileTask task(boolean enabled,
                                        boolean deletionEnabled,
                                        long intervalMs,
                                        int batchSize,
                                        long lockTtlSeconds) {
        return new ShareItemReconcileTask(shareMapper, fileServiceClient, stringRedisTemplate,
                enabled, deletionEnabled, intervalMs, batchSize, lockTtlSeconds);
    }

    private ShareItemReconcileTask defaultTask(boolean deletionEnabled) {
        return task(true, deletionEnabled, 1_800_000L, 500, 1500L);
    }

    /** 让锁获取成功（否则任务会提前返回，测不到主逻辑）。 */
    private void stubLockAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.TRUE);
    }

    private ShareItem item(long id, long shareId, Long fileId) {
        ShareItem item = new ShareItem();
        item.setId(id);
        item.setShareId(shareId);
        item.setFileId(fileId);
        item.setFileType(1);
        return item;
    }

    private ShareFileProjection projection(long fileId, Integer deleted) {
        ShareFileProjection projection = new ShareFileProjection();
        projection.setId(fileId);
        projection.setDeleted(deleted);
        return projection;
    }

    // ==================== 核心口径 ====================

    /**
     * 四种状态各一条：行不存在 / deleted=2 / deleted=1（回收站）/ deleted=0（正常）。
     * 只有前两个算孤儿；灰度期（deletion-enabled=false）只告警、不删除。
     */
    @Test
    void reconcile_shouldTreatMissingAndHardDeletedAsOrphanButKeepRecycleBin() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 500)).thenReturn(List.of(
                item(1L, 100L, 11L),   // file 行不存在 → 孤儿
                item(2L, 100L, 12L),   // deleted=2 彻底删除 → 孤儿
                item(3L, 200L, 13L),   // deleted=1 回收站 → 保留
                item(4L, 200L, 14L))); // deleted=0 正常 → 跳过量
        // 注意：返回集里故意**没有** fileId=11，模拟「行不存在」。
        when(fileServiceClient.getShareFileProjectionsWithDeleted(anyList())).thenReturn(List.of(
                projection(12L, 2),
                projection(13L, 1),
                projection(14L, 0)));

        defaultTask(false).reconcileShareItems();

        // 灰度期：绝不删除
        verify(shareMapper, never()).deleteShareItemsByFileIds(anyList());
    }

    /** 打开删除开关后，只按「孤儿 file_id」删，回收站与正常文件必须不在其中。 */
    @Test
    void reconcile_shouldDeleteOnlyOrphanFileIdsWhenDeletionEnabled() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 500)).thenReturn(List.of(
                item(1L, 100L, 11L),
                item(2L, 100L, 12L),
                item(3L, 200L, 13L),
                item(4L, 200L, 14L)));
        when(fileServiceClient.getShareFileProjectionsWithDeleted(anyList())).thenReturn(List.of(
                projection(12L, 2),
                projection(13L, 1),
                projection(14L, 0)));

        defaultTask(true).reconcileShareItems();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(shareMapper).deleteShareItemsByFileIds(captor.capture());
        assertEquals(List.of(11L, 12L), captor.getValue(),
                "只应删除「行不存在(11)」与「已彻底删除(12)」；回收站(13)与正常(14)必须保留");
    }

    /** 上游异常时必须整轮中止，绝不能把「查不到」当「行不存在」去删。 */
    @Test
    void reconcile_shouldNotDeleteAnythingWhenUpstreamFails() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 500)).thenReturn(List.of(item(1L, 100L, 11L)));
        when(fileServiceClient.getShareFileProjectionsWithDeleted(anyList()))
                .thenThrow(new IllegalStateException("file-service 抖动"));

        // 异常被外层吞掉，任务本身不抛（否则调度器会记一次失败并影响后续轮次的可观测性）
        defaultTask(true).reconcileShareItems();

        verify(shareMapper, never()).deleteShareItemsByFileIds(anyList());
    }

    // ==================== 分页 ====================

    /** 游标推进：满批继续、不满批停止；第二次调用的 lastId 应为第一批最后一行的 id。 */
    @Test
    void reconcile_shouldAdvanceCursorUntilShortBatch() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 2)).thenReturn(List.of(
                item(1L, 100L, 11L),
                item(2L, 100L, 12L)));
        when(shareMapper.listItemsByCursor(2L, 2)).thenReturn(List.of(item(3L, 100L, 13L)));
        when(fileServiceClient.getShareFileProjectionsWithDeleted(anyList())).thenReturn(List.of(
                projection(11L, 0),
                projection(12L, 0),
                projection(13L, 0)));

        task(true, false, 1_800_000L, 2, 1500L).reconcileShareItems();

        verify(shareMapper).listItemsByCursor(2L, 2);
        // 第二批只有 1 条（< 2）⇒ 不再继续查询
        verify(shareMapper, never()).listItemsByCursor(3L, 2);
    }

    // ==================== 开关与锁 ====================

    @Test
    void reconcile_shouldDoNothingWhenDisabled() {
        task(false, true, 1_800_000L, 500, 1500L).reconcileShareItems();

        verifyNoInteractions(shareMapper);
        verifyNoInteractions(fileServiceClient);
    }

    @Test
    void reconcile_shouldSkipWhenLockNotAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.FALSE);

        defaultTask(true).reconcileShareItems();

        verifyNoInteractions(shareMapper);
        verifyNoInteractions(fileServiceClient);
    }

    // ==================== 配置钳制 ====================

    /**
     * 锁 TTL 必须严格小于调度间隔：否则一轮异常退出后锁会一直卡到 TTL 过期，任务静默停摆。
     * 间隔 60s、TTL 配了 1500s ⇒ 应被钳到 1s（60 - 60，下限 1）。
     */
    @Test
    void constructor_shouldClampLockTtlStrictlyBelowInterval() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(Boolean.TRUE);
        when(shareMapper.listItemsByCursor(anyLong(), anyInt())).thenReturn(List.of());

        ShareItemReconcileTask task = task(true, false, 60_000L, 500, 1500L);
        task.reconcileShareItems();

        verify(valueOperations).setIfAbsent(anyString(), anyString(), eq(1L), eq(TimeUnit.SECONDS));
    }

    /** 非法批大小（0 / 负数）会让 SQL 的 LIMIT 变成无意义值，统一按 1 处理。 */
    @Test
    void constructor_shouldTreatNonPositiveBatchSizeAsOne() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 1)).thenReturn(List.of());

        task(true, false, 1_800_000L, 0, 1500L).reconcileShareItems();

        verify(shareMapper).listItemsByCursor(0L, 1);
    }

    /** fileId 为 null 的脏行不能把整轮对账带崩（历史数据/异常写入都可能产生）。 */
    @Test
    void reconcile_shouldTolerateNullFileId() {
        stubLockAcquired();
        when(shareMapper.listItemsByCursor(0L, 500)).thenReturn(List.of(
                item(1L, 100L, null),
                item(2L, 100L, 12L)));
        when(fileServiceClient.getShareFileProjectionsWithDeleted(anyList())).thenReturn(List.of());

        defaultTask(true).reconcileShareItems();

        // fileId=12 因「不在返回集里」被判成行不存在 → 删除；null 行不影响流程
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(shareMapper).deleteShareItemsByFileIds(captor.capture());
        assertEquals(List.of(12L), captor.getValue());
    }
}
