package uno.acloud.common.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditBufferRetrySchedulerTest {

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @InjectMocks
    private AuditBufferRetryScheduler scheduler;

    @Test
    void retryBufferedAuditEvents_emptyBuffer_doesNothing() {
        when(auditEventPublisher.getBufferSize()).thenReturn(0);

        scheduler.retryBufferedAuditEvents();

        verify(auditEventPublisher, never()).retryBufferedEvents();
    }

    @Test
    void retryBufferedAuditEvents_withBufferedEvents_callsRetry() {
        when(auditEventPublisher.getBufferSize()).thenReturn(5);
        when(auditEventPublisher.retryBufferedEvents()).thenReturn(3);

        scheduler.retryBufferedAuditEvents();

        verify(auditEventPublisher).retryBufferedEvents();
    }

    @Test
    void retryBufferedAuditEvents_exceptionInRetry_doesNotPropagate() {
        when(auditEventPublisher.getBufferSize()).thenReturn(5);
        when(auditEventPublisher.retryBufferedEvents()).thenThrow(new RuntimeException("Unexpected"));

        // Should not throw
        scheduler.retryBufferedAuditEvents();

        verify(auditEventPublisher).retryBufferedEvents();
    }
}
