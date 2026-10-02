package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailRecordStatus;
import uno.acloud.email.domain.EmailTemplate;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.infrastructure.EmailTemplateMapper;
import uno.acloud.email.infrastructure.SimpleJavaMailSender;
import uno.acloud.exception.BusinessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailDispatchServiceTest {

    /** 对应 @Value("${app.email.max-retry-count:4}") 的注入值 */
    private static final int MAX_RETRY_COUNT = 4;

    @Mock
    private EmailRecordMapper emailRecordMapper;
    @Mock
    private EmailTemplateMapper emailTemplateMapper;
    @Mock
    private SimpleJavaMailSender simpleJavaMailSender;
    @Mock
    private EmailSendingAvailabilityService emailSendingAvailabilityService;

    @Test
    void sendByTemplateShouldRenderAndInsertPendingRecord() {
        EmailTemplate template = new EmailTemplate();
        template.setTemplateCode("SYSTEM_MESSAGE");
        template.setSubjectTemplate("{{title}}");
        template.setContentHtml("<p>{{content}}</p>");
        template.setStatus(0); // status=0 表示启用，isUsable() 才返回 true
        when(emailTemplateMapper.getActiveByCode("SYSTEM_MESSAGE")).thenReturn(template);
        when(emailRecordMapper.insert(any(EmailRecord.class))).thenAnswer(invocation -> {
            EmailRecord record = invocation.getArgument(0);
            record.setId(11L);
            return 1;
        });
        EmailProperties properties = new EmailProperties();
        properties.setAsync(false);
        EmailDispatchService service = new EmailDispatchService(
                emailRecordMapper,
                emailTemplateMapper,
                new EmailTemplateRenderer(),
                simpleJavaMailSender,
                properties,
                emailSendingAvailabilityService,
                Runnable::run,
                MAX_RETRY_COUNT
        );

        Long recordId = service.sendByTemplate(
                "USER@example.com",
                "SYSTEM_MESSAGE",
                Map.of("title", "标题", "content", "<通知>"),
                "SYSTEM",
                "1",
                null
        );

        assertEquals(11L, recordId);
        ArgumentCaptor<EmailRecord> recordCaptor = ArgumentCaptor.forClass(EmailRecord.class);
        verify(emailRecordMapper).insert(recordCaptor.capture());
        EmailRecord record = recordCaptor.getValue();
        assertEquals("user@example.com", record.getRecipient());
        assertEquals("标题", record.getSubject());
        assertEquals("<p>&lt;通知&gt;</p>", record.getContentHtml());
        assertEquals(EmailRecordStatus.PENDING, record.getStatus());
        // 最大重试次数来自 @Value 注入的 app.email.max-retry-count
        assertEquals(MAX_RETRY_COUNT, record.getMaxAttempts());
        verify(emailSendingAvailabilityService).requireSendingAvailable();
    }

    @Test
    void sendShouldRejectWhenSendingDisabledAndNotCreateRecord() {
        BusinessException disabled = new BusinessException(
                ErrorCode.BAD_REQUEST,
                EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE
        );
        doThrow(disabled).when(emailSendingAvailabilityService).requireSendingAvailable();
        EmailProperties properties = new EmailProperties();
        EmailDispatchService service = new EmailDispatchService(
                emailRecordMapper,
                emailTemplateMapper,
                new EmailTemplateRenderer(),
                simpleJavaMailSender,
                properties,
                emailSendingAvailabilityService,
                Runnable::run,
                MAX_RETRY_COUNT
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.send("user@example.com", "主题", "<p>内容</p>", null, null, null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE, exception.getMessage());
        verifyNoInteractions(emailRecordMapper);
    }

    @Test
    void dispatchRecordShouldRetryWhenSenderRejectsDisabledState() {
        EmailRecord record = new EmailRecord();
        record.setId(12L);
        record.setRecipient("user@example.com");
        record.setAttemptCount(1);
        record.setMaxAttempts(4);
        when(emailRecordMapper.markSending(12L)).thenReturn(1);
        when(emailRecordMapper.selectById(12L)).thenReturn(record);
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE))
                .when(simpleJavaMailSender)
                .send(record);
        EmailProperties properties = new EmailProperties();
        EmailDispatchService service = new EmailDispatchService(
                emailRecordMapper,
                emailTemplateMapper,
                new EmailTemplateRenderer(),
                simpleJavaMailSender,
                properties,
                emailSendingAvailabilityService,
                Runnable::run,
                MAX_RETRY_COUNT
        );

        assertFalse(service.dispatchRecord(12L));

        verify(emailRecordMapper).markRetry(eq(12L), eq(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE), any(LocalDateTime.class));
        verify(emailRecordMapper, never()).markSent(12L);
    }

    // ==================== B-14：afterCommit 提交路径被拒绝执行时不得冒泡 ====================

    /**
     * B-14（2026-10-03）：记录已落库、事务已提交后，若线程池拒绝任务
     * （B-14 同批把 CallerRunsPolicy 改为 Abort——请求线程不该亲自发 SMTP），
     * 该拒绝异常绝不能从 afterCommit 回调冒泡进 Spring 事务同步链。
     * 记录保持 PENDING，由 EmailRetryTask 的定时 dispatchDueRecords 兜底重试。
     */
    @Test
    void sendShouldNotPropagateRejectedExecutionFromAfterCommit() {
        EmailTemplate template = new EmailTemplate();
        template.setTemplateCode("SYSTEM_MESSAGE");
        template.setSubjectTemplate("{{title}}");
        template.setContentHtml("<p>{{content}}</p>");
        template.setStatus(0);
        when(emailTemplateMapper.getActiveByCode("SYSTEM_MESSAGE")).thenReturn(template);
        when(emailRecordMapper.insert(any(EmailRecord.class))).thenAnswer(invocation -> {
            EmailRecord record = invocation.getArgument(0);
            record.setId(21L);
            return 1;
        });
        EmailProperties properties = new EmailProperties();
        properties.setAsync(true);
        // 模拟线程池队列已满 + Abort 策略：提交即抛 RejectedExecutionException
        java.util.concurrent.Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("email-send pool exhausted");
        };
        EmailDispatchService service = new EmailDispatchService(
                emailRecordMapper,
                emailTemplateMapper,
                new EmailTemplateRenderer(),
                simpleJavaMailSender,
                properties,
                emailSendingAvailabilityService,
                rejectingExecutor,
                MAX_RETRY_COUNT
        );

        TransactionSynchronizationManager.initSynchronization();
        try {
            // 无事务上下文时走同步执行路径（triggerAsyncIfDue 的 else 分支），同样不得冒泡
            Long recordId = service.sendByTemplate(
                    "user@example.com", "SYSTEM_MESSAGE",
                    Map.of("title", "标题", "content", "内容"),
                    "SYSTEM", "1", null);

            assertEquals(21L, recordId);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronization sync =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            // 关键断言：afterCommit 内部吞掉拒绝异常，不向事务同步链传播
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(sync::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
