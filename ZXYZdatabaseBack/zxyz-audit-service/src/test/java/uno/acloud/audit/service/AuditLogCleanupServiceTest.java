package uno.acloud.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uno.acloud.audit.mapper.OperateLogMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditLogCleanupService} 是 {@code @Scheduled} 任务：<b>它绝不能向外抛异常</b> ——
 * Spring 的定时任务一旦抛出未捕获异常，后续调度会被取消，日志表就会无限增长。
 * 另外 cutoff 必须真的按 {@code retention-days} 算，否则会误删未过期审计数据。
 */
class AuditLogCleanupServiceTest {

    private OperateLogMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(OperateLogMapper.class);
    }

    @Test
    void cleanupExpiredLogs_deletesOlderThanNowMinusRetentionDays() {
        when(mapper.deleteOlderThan(any())).thenReturn(3);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 90);

        LocalDateTime lowerBound = LocalDateTime.now().minusDays(90);
        service.cleanupExpiredLogs();
        LocalDateTime upperBound = LocalDateTime.now().minusDays(90);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deleteOlderThan(captor.capture());
        assertThat(captor.getValue()).isBetween(lowerBound, upperBound);
    }

    @Test
    void cleanupExpiredLogs_honoursConfiguredRetentionDays() {
        when(mapper.deleteOlderThan(any())).thenReturn(0);
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 7);

        service.cleanupExpiredLogs();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).deleteOlderThan(captor.capture());
        assertThat(captor.getValue())
                .isAfter(LocalDateTime.now().minusDays(8))
                .isBefore(LocalDateTime.now().minusDays(6));
    }

    @Test
    void cleanupExpiredLogs_whenMapperThrows_swallowsSoSchedulingSurvives() {
        when(mapper.deleteOlderThan(any())).thenThrow(new RuntimeException("db down"));
        AuditLogCleanupService service = new AuditLogCleanupService(mapper, 30);

        assertThatCode(service::cleanupExpiredLogs).doesNotThrowAnyException();
        verify(mapper).deleteOlderThan(any());
    }
}
