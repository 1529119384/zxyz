package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailRecordStatus;
import uno.acloud.email.domain.EmailTemplate;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.infrastructure.EmailTemplateMapper;
import uno.acloud.email.infrastructure.SimpleJavaMailSender;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.Map;

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

    @Mock
    private EmailRecordMapper emailRecordMapper;
    @Mock
    private EmailTemplateMapper emailTemplateMapper;
    @Mock
    private SimpleJavaMailSender simpleJavaMailSender;
    @Mock
    private EmailSendingAvailabilityService emailSendingAvailabilityService;

    @Mock
    private ConfigGetter configGetter;

    @Test
    void sendByTemplateShouldRenderAndInsertPendingRecord() {
        EmailTemplate template = new EmailTemplate();
        template.setTemplateCode("SYSTEM_MESSAGE");
        template.setSubjectTemplate("{{title}}");
        template.setContentHtml("<p>{{content}}</p>");
        when(emailTemplateMapper.getActiveByCode("SYSTEM_MESSAGE")).thenReturn(template);
        when(emailRecordMapper.insert(any(EmailRecord.class))).thenAnswer(invocation -> {
            EmailRecord record = invocation.getArgument(0);
            record.setId(11L);
            return 1;
        });
        when(configGetter.getInt("app.email.max-retry-count", 4)).thenReturn(4);
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
                configGetter
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
                configGetter
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
                configGetter
        );

        assertFalse(service.dispatchRecord(12L));

        verify(emailRecordMapper).markRetry(eq(12L), eq(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE), any(LocalDateTime.class));
        verify(emailRecordMapper, never()).markSent(12L);
    }
}
