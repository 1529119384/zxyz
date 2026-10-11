package uno.acloud.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uno.acloud.audit.mapper.OperateLogMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditLogCleanupService} 是 {@code @Scheduled} 任务：<b>它绝不能向外抛异常</b> ——
 * Spring 的定时任务一旦抛出未捕获异常，后续调度会被取消，日志表就会无限增长。
 * 另外 cutoff 必须真的按 {@code retention-days} 算，否则会误删未过期审计数据。
 *
 * <p>54 号审计 🟡 起改为批次协议（{@code DELETE ... LIMIT 1000} 循环），
 * 本类同时钉住批次循环与退出条件 —— 「单次大 DELETE」回归到这里会立刻变红。</p>
 */
class AuditLogCleanupServiceTest {

    private OperateLogMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(OperateLogMapper.class);
    }

    @Test
    void cleanupExpiredLogs_deletesOlderThanNowMinusRetentionDays() {
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(3);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        LocalDateTime lowerBound = LocalDateTime.now().minusDays(90);
        service.cleanupExpiredLogs();
        LocalDateTime upperBound = LocalDateTime.now().minusDays(90);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deleteOlderThanBatch(captor.capture(), anyInt());
        assertThat(captor.getValue()).isBetween(lowerBound, upperBound);
    }

    @Test
    void cleanupExpiredLogs_honoursConfiguredRetentionDays() {
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(0);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 7);

        service.cleanupExpiredLogs();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deleteOlderThanBatch(captor.capture(), anyInt());
        assertThat(captor.getValue())
                .isAfter(LocalDateTime.now().minusDays(8))
                .isBefore(LocalDateTime.now().minusDays(6));
    }

    @Test
    void cleanupExpiredLogs_whenMapperThrows_swallowsSoSchedulingSurvives() {
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenThrow(new RuntimeException("db down"));
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 30);

        assertThatCode(service::cleanupExpiredLogs).doesNotThrowAnyException();
        verify(mapper).deleteOlderThanBatch(any(), anyInt());
    }

    // ==================== 54 号审计 🟡：批次协议 ====================

    @Test
    void cleanupExpiredLogs_usesBatchLimit1000PerDelete() {
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(0);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        service.cleanupExpiredLogs();

        // 单批上限必须是 BATCH_LIMIT(1000)：写死别的大数字 = 大事务回归
        verify(mapper).deleteOlderThanBatch(any(), eq(AuditLogCleanupService.BATCH_LIMIT));
    }

    @Test
    void cleanupExpiredLogs_zeroRowsOnFirstBatch_stopsAfterSingleDelete() {
        // 首批即 0（空跑）：只允许发一条 DELETE，不许无谓空转
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(0);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        service.cleanupExpiredLogs();

        verify(mapper, times(1)).deleteOlderThanBatch(any(), anyInt());
    }

    @Test
    void cleanupExpiredLogs_partialBatch_stopsWithoutExtraDelete() {
        // 2400 行过期：1000 + 1000 + 400 —— 第三批 400 < LIMIT 说明删完了，
        // 不得再发第 4 条 DELETE（多一条幂等 DELETE 虽无害，但协议上要干净退出）
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(1000, 1000, 400);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        service.cleanupExpiredLogs();

        verify(mapper, times(3)).deleteOlderThanBatch(any(), anyInt());
    }

    @Test
    void cleanupExpiredLogs_fullBatchesThenZero_stopsOnZero() {
        // 恰好整数批：1000 + 1000 + 0（第 3 批返回 0 = 已删完，退出）
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(1000, 1000, 0);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        service.cleanupExpiredLogs();

        verify(mapper, times(3)).deleteOlderThanBatch(any(), anyInt());
    }

    @Test
    void cleanupExpiredLogs_boundByMaxBatches_evenIfMapperNeverReturnsZero() {
        // 兜底防线：mapper 永远返回满批（理论上的持续写入场景）时，
        // 循环必须在 MAX_BATCHES 截断 —— 绝不允许无限循环吃掉调度线程
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(AuditLogCleanupService.BATCH_LIMIT);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        service.cleanupExpiredLogs();

        verify(mapper, times(AuditLogCleanupService.MAX_BATCHES)).deleteOlderThanBatch(any(), anyInt());
    }

    @Test
    void cleanupExpiredLogs_batchFailure_doesNotRollbackEarlierBatches() {
        // 分批的核心收益：前两批已各自提交（mock 层面无法验证 commit，但可验证
        // 「第 3 批抛异常」不会让循环重试或继续，且任务不向外抛）
        when(mapper.deleteOlderThanBatch(any(), anyInt())).thenReturn(1000, 1000)
                .thenThrow(new RuntimeException("lock wait timeout"));
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        assertThatCode(service::cleanupExpiredLogs).doesNotThrowAnyException();
        verify(mapper, times(3)).deleteOlderThanBatch(any(), anyInt());
    }
}
