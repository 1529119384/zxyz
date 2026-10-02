package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link FileNodePurgeTask} 的定时清理语义（F2，P2 资源泄漏/事务）。
 *
 * <h2>F2：为什么「同事务」这件事必须被测出来</h2>
 * <p>回收站 TTL 到期清理要把两件事做成<b>一个原子步骤</b>：</p>
 * <ol>
 *   <li>{@code reallyDeleteByIds}：把 {@code file_node} 置 {@code deleted = 2}；</li>
 *   <li>{@code releaseReferences}：把 {@code file_object_ref.ref_count} 减 1。</li>
 * </ol>
 * <p>修复前第 2 步在事务<b>之外</b>（第 1 步是自动提交）。两者之间异常/崩溃会留下
 * 「节点已删、ref_count 未减」的形态：ref 行永停 {@code ACTIVE}（{@code refCount >= 1}），
 * 物理删除管道永不拾取，而 {@code OrphanObjectReconcileTask} 只认<b>无 ref 行</b>的孤儿
 * ⇒ 这条泄漏对既有对账任务<b>完全不可见</b>，OSS 对象永久泄漏。</p>
 *
 * <h2>为什么用 Mockito 而不是集成测试</h2>
 * <p>本仓 CI 在无 Docker 时整个 {@code *IntegrationTest} 会被跳过（Testcontainers 起不来），
 * 那样这个缺陷就失去了回归网。用 Mockito 直接观测「{@code releaseReferences} 是否发生在
 * {@code transactionHelper.execute} 的<b>回调内部</b>」，无需数据库即可判定事务边界。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileNodePurgeTaskTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private UsageLedgerMapper usageLedgerMapper;

    @Mock
    private FileObjectReferenceManager fileObjectReferenceManager;

    @Mock
    private TransactionHelper transactionHelper;

    private FileNodePurgeTask task;

    /** 仅当 {@code transactionHelper.execute} 的回调正在执行时为 true。 */
    private final AtomicBoolean insideTransaction = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        task = new FileNodePurgeTask(fileMapper, usageLedgerMapper, fileObjectReferenceManager, transactionHelper);

        // 复刻 TransactionTemplate 的行为：真的执行回调，并把「当前处于事务内」暴露给断言。
        lenient().when(transactionHelper.execute(any())).thenAnswer(invocation -> {
            TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            assertFalse(insideTransaction.get(), "事务不得嵌套进入（同一 rootId 只应开一个事务）");
            insideTransaction.set(true);
            try {
                return callback.doInTransaction(null);
            } finally {
                insideTransaction.set(false);
            }
        });
    }

    private void givenOneExpiredRecycleRoot(long rootId) {
        when(fileMapper.selectRecycleExpiredRootIds(any(Timestamp.class), anyInt())).thenReturn(List.of(rootId));
        when(fileMapper.selectTombstoneExpiredIds(any(Timestamp.class), anyInt())).thenReturn(List.of());
    }

    /**
     * 核心断言：{@code releaseReferences} 必须发生在事务回调<b>内部</b>，
     * 与 {@code reallyDeleteByIds} 同一事务边界 —— 否则中途失败会永久泄漏 OSS 引用。
     */
    @Test
    void recycleCleanup_releasesObjectReferencesInsideTheSameTransaction() {
        givenOneExpiredRecycleRoot(7L);
        List<Long> allIds = List.of(7L, 8L);
        when(fileMapper.collectDescendantIds(List.of(7L))).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(List.of("uuid-a", "uuid-b"));
        when(fileMapper.sumDeletedFileBytesByScopeKey(allIds)).thenReturn(List.of());
        when(fileMapper.reallyDeleteByIds(allIds, null)).thenReturn(2);

        // 关键：在「释放引用」的瞬间检查是否仍在事务内
        AtomicBoolean observedInsideTransaction = new AtomicBoolean(false);
        doAnswer(invocation -> {
            observedInsideTransaction.set(insideTransaction.get());
            return null;
        }).when(fileObjectReferenceManager).releaseReferences(List.of("uuid-a", "uuid-b"));

        task.purgeExpiredNodes();

        verify(fileMapper).reallyDeleteByIds(allIds, null);
        verify(fileObjectReferenceManager).releaseReferences(List.of("uuid-a", "uuid-b"));
        assertTrue(observedInsideTransaction.get(),
                "releaseReferences 必须在 reallyDeleteByIds 的同一事务内执行 —— "
                        + "否则「节点已置 deleted=2 但 ref_count 未减」会永久泄漏 OSS 对象，"
                        + "且 OrphanObjectReconcileTask 只认无 ref 行的孤儿、对此不可见（F2，P2）");
        // 整个 rootId 只开一个事务（不是「删除一个事务 + 释放一个事务」）
        verify(transactionHelper, times(1)).execute(any());
    }

    /** 子节点收集为空时不应开事务、也不应释放任何引用（避免空转与误调）。 */
    @Test
    void recycleCleanup_skipsWhenNoDescendants() {
        givenOneExpiredRecycleRoot(7L);
        when(fileMapper.collectDescendantIds(List.of(7L))).thenReturn(List.of());

        task.purgeExpiredNodes();

        verify(fileMapper, never()).reallyDeleteByIds(anyList(), any());
        verify(fileObjectReferenceManager, never()).releaseReferences(anyCollection());
    }

    /**
     * 释放引用抛异常时：异常必须被 {@code purgeTree} 的 try/catch 吞掉
     * （单个 rootId 失败不得中断整批清理），并且事务语义交给 {@code transactionHelper} 回滚。
     */
    @Test
    void recycleCleanup_doesNotAbortRemainingRootsWhenOneRootFails() {
        when(fileMapper.selectRecycleExpiredRootIds(any(Timestamp.class), anyInt())).thenReturn(List.of(7L, 9L));
        when(fileMapper.selectTombstoneExpiredIds(any(Timestamp.class), anyInt())).thenReturn(List.of());

        List<Long> firstIds = List.of(7L);
        List<Long> secondIds = List.of(9L);
        when(fileMapper.collectDescendantIds(List.of(7L))).thenReturn(firstIds);
        when(fileMapper.collectDescendantIds(List.of(9L))).thenReturn(secondIds);
        when(fileMapper.getOssKeysByIds(firstIds)).thenReturn(List.of("uuid-a"));
        when(fileMapper.getOssKeysByIds(secondIds)).thenReturn(List.of("uuid-c"));
        when(fileMapper.sumDeletedFileBytesByScopeKey(anyList())).thenReturn(List.of());
        when(fileMapper.reallyDeleteByIds(firstIds, null)).thenReturn(1);
        when(fileMapper.reallyDeleteByIds(secondIds, null)).thenReturn(1);
        doThrow(new RuntimeException("file_object_ref 不可用"))
                .when(fileObjectReferenceManager).releaseReferences(List.of("uuid-a"));

        assertDoesNotThrow(() -> task.purgeExpiredNodes());

        // 第二个 root 仍被处理 ⇒ 一行失败不会饿死其余待清理数据
        verify(fileMapper).reallyDeleteByIds(secondIds, null);
        verify(fileObjectReferenceManager).releaseReferences(List.of("uuid-c"));
    }

    /** 墓碑过期清理：按作用域扣减配额后删行（口径与 reallyDelete 一致）。 */
    @Test
    void tombstoneCleanup_releasesQuotaThenDeletesRows() {
        when(fileMapper.selectRecycleExpiredRootIds(any(Timestamp.class), anyInt())).thenReturn(List.of());
        List<Long> tombstoneIds = List.of(11L);
        when(fileMapper.selectTombstoneExpiredIds(any(Timestamp.class), anyInt())).thenReturn(tombstoneIds);
        when(fileMapper.sumDeletedFileBytesByScopeKey(tombstoneIds))
                .thenReturn(List.of(Map.of("scopeKey", "personal:100", "totalBytes", 2048L)));
        when(fileMapper.deleteTombstoneRows(tombstoneIds)).thenReturn(1);

        task.purgeExpiredNodes();

        verify(usageLedgerMapper).decrement("personal:100", 2048L);
        verify(fileMapper).deleteTombstoneRows(tombstoneIds);
    }

    /** 配额扣减失败只告警（由后台对账以 SUM 权威值校正），不得阻断墓碑行清理。 */
    @Test
    void tombstoneCleanup_stillDeletesRowsWhenQuotaReleaseFails() {
        when(fileMapper.selectRecycleExpiredRootIds(any(Timestamp.class), anyInt())).thenReturn(List.of());
        List<Long> tombstoneIds = List.of(11L);
        when(fileMapper.selectTombstoneExpiredIds(any(Timestamp.class), anyInt())).thenReturn(tombstoneIds);
        when(fileMapper.sumDeletedFileBytesByScopeKey(tombstoneIds))
                .thenReturn(List.of(Map.of("scopeKey", "personal:100", "totalBytes", 2048L)));
        when(fileMapper.deleteTombstoneRows(tombstoneIds)).thenReturn(1);
        doThrow(new RuntimeException("usage_ledger 不可用")).when(usageLedgerMapper).decrement(anyString(), anyLong());

        assertDoesNotThrow(() -> task.purgeExpiredNodes());

        verify(fileMapper).deleteTombstoneRows(tombstoneIds);
    }
}
