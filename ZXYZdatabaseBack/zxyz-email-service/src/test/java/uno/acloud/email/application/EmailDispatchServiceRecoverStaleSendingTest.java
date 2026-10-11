package uno.acloud.email.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.infrastructure.EmailTemplateMapper;
import uno.acloud.email.infrastructure.SimpleJavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-2（ISSUE/54）：SENDING 卡死回捞的单元钉。
 *
 * <p>背景：{@code markSending} 把记录置为 SENDING 后若进程崩溃/被杀，记录永远停在
 * SENDING，而 {@code EmailRetryTask} 原本只扫 PENDING —— 该邮件永久滞留，无人恢复。
 * 本组测试把回捞语义钉死：命中回捞 / 未超时跳过 / 超上限放弃，三个分支互斥且由
 * SQL 的 WHERE 条件保证；另钉「N 分钟可配」与 RetryTask 的接入顺序。</p>
 */
@ExtendWith(MockitoExtension.class)
class EmailDispatchServiceRecoverStaleSendingTest {

    /** 默认回捞阈值（email.sending-stale-minutes），与 EmailProperties 默认值一致 */
    private static final int DEFAULT_STALE_MINUTES = 10;

    @Mock
    private EmailRecordMapper emailRecordMapper;

    private EmailDispatchService newService(EmailProperties properties) {
        return new EmailDispatchService(
                emailRecordMapper,
                mock(EmailTemplateMapper.class),
                new EmailTemplateRenderer(),
                mock(SimpleJavaMailSender.class),
                properties,
                mock(EmailSendingAvailabilityService.class),
                Runnable::run,
                4
        );
    }

    @Test
    @DisplayName("回捞命中：超龄未超限的 SENDING 记录被重置回 PENDING")
    void recoverShouldResetStaleSendingBelowAttemptLimit() {
        EmailProperties properties = new EmailProperties();
        when(emailRecordMapper.recoverStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(1);
        when(emailRecordMapper.failStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(0);

        int recovered = newService(properties).recoverStaleSending();

        assertEquals(1, recovered);
        // 阈值必须取自配置（email.sending-stale-minutes），两条 SQL 用同一阈值保证互斥完备
        verify(emailRecordMapper).recoverStaleSending(DEFAULT_STALE_MINUTES);
        verify(emailRecordMapper).failStaleSending(DEFAULT_STALE_MINUTES);
    }

    @Test
    @DisplayName("未超时跳过：阈值内的 SENDING 记录不参与回捞（SQL WHERE 保证，服务层透传 0）")
    void recoverShouldSkipWhenNoRecordStaleEnough() {
        EmailProperties properties = new EmailProperties();
        // 刚进入 SENDING 的正常发送（update_time 未超过阈值）⇒ SQL 条件不命中 ⇒ 0
        when(emailRecordMapper.recoverStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(0);
        when(emailRecordMapper.failStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(0);

        int recovered = newService(properties).recoverStaleSending();

        assertEquals(0, recovered);
        // 无命中时不应有任何其它 mapper 状态写入
        verify(emailRecordMapper, never()).markSending(anyLong());
        verify(emailRecordMapper, never()).markRetry(anyLong(), anyString(), org.mockito.ArgumentMatchers.any());
        verify(emailRecordMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("超上限放弃：attempt_count >= max_attempts 的 SENDING 记录改判 FAILED 而非无限回捞")
    void recoverShouldAbandonWhenAttemptLimitReached() {
        EmailProperties properties = new EmailProperties();
        when(emailRecordMapper.recoverStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(0);
        when(emailRecordMapper.failStaleSending(DEFAULT_STALE_MINUTES)).thenReturn(1);

        int recovered = newService(properties).recoverStaleSending();

        // 返回值只计「回捞重发」；放弃的记录由 failStaleSending 落 FAILED 终态
        assertEquals(0, recovered);
        verify(emailRecordMapper).failStaleSending(DEFAULT_STALE_MINUTES);
    }

    @Test
    @DisplayName("回捞顺序：先重发回捞、后超限放弃（保证日志语义与两分支互斥完备）")
    void recoverShouldRunResetBeforeAbandon() {
        EmailProperties properties = new EmailProperties();
        when(emailRecordMapper.recoverStaleSending(anyInt())).thenReturn(0);
        when(emailRecordMapper.failStaleSending(anyInt())).thenReturn(0);

        newService(properties).recoverStaleSending();

        InOrder inOrder = inOrder(emailRecordMapper);
        inOrder.verify(emailRecordMapper).recoverStaleSending(anyInt());
        inOrder.verify(emailRecordMapper).failStaleSending(anyInt());
    }

    @Test
    @DisplayName("阈值下限：sending-stale-minutes 配成 0 或负数时按 1 分钟执行（防误配置即时回捞在途邮件）")
    void recoverShouldClampStaleMinutesToAtLeastOne() {
        EmailProperties properties = new EmailProperties();
        properties.setSendingStaleMinutes(0);
        when(emailRecordMapper.recoverStaleSending(1)).thenReturn(0);
        when(emailRecordMapper.failStaleSending(1)).thenReturn(0);

        newService(properties).recoverStaleSending();

        verify(emailRecordMapper).recoverStaleSending(1);
        verify(emailRecordMapper).failStaleSending(1);
    }

    @Test
    @DisplayName("回捞阈值可配置：设为 3 分钟时透传 3（N 分钟可配的钉）")
    void recoverShouldUseConfiguredStaleMinutes() {
        EmailProperties properties = new EmailProperties();
        properties.setSendingStaleMinutes(3);
        when(emailRecordMapper.recoverStaleSending(3)).thenReturn(0);
        when(emailRecordMapper.failStaleSending(3)).thenReturn(0);

        newService(properties).recoverStaleSending();

        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(emailRecordMapper).recoverStaleSending(captor.capture());
        assertEquals(3, captor.getValue());
    }

    @Test
    @DisplayName("EmailProperties 默认阈值 = 10 分钟（配置语义漂移钉）")
    void defaultStaleMinutesShouldBeTen() {
        assertEquals(10, new EmailProperties().getSendingStaleMinutes());
    }

    @Test
    @DisplayName("EmailRetryTask 每轮先回捞再重试（接入点钉死，防回捞调用被静默移除）")
    void retryTaskShouldRecoverBeforeDispatch() {
        EmailDispatchService dispatchService = org.mockito.Mockito.mock(EmailDispatchService.class);
        EmailRetryTask task = new EmailRetryTask(dispatchService, new EmailProperties());

        task.retryPendingEmails();

        InOrder inOrder = inOrder(dispatchService);
        inOrder.verify(dispatchService).recoverStaleSending();
        inOrder.verify(dispatchService).dispatchDueRecords(50);
    }

    @Test
    @DisplayName("回捞抛异常由 RetryTask 外层 catch 兜底，不冒泡也不阻断本轮调度循环")
    void retryTaskShouldSwallowRecoveryFailure() {
        EmailDispatchService dispatchService = org.mockito.Mockito.mock(EmailDispatchService.class);
        when(dispatchService.recoverStaleSending()).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> new EmailRetryTask(dispatchService, new EmailProperties()).retryPendingEmails());
        verify(dispatchService, never()).dispatchDueRecords(anyInt());
    }
}
