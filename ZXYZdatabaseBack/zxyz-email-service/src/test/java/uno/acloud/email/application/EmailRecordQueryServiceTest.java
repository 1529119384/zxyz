package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.convert.EmailEntityMapper;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.dto.EmailRecordPageVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailRecordQueryServiceTest {

    @Mock
    private EmailRecordMapper emailRecordMapper;

    @Test
    void listRecordsShouldNormalizeFiltersAndPage() {
        EmailRecord record = new EmailRecord();
        record.setId(9L);
        record.setRecipient("user@example.com");
        record.setSubject("通知");
        record.markSent();
        when(emailRecordMapper.countRecords("SENT", "user@example.com", "SYSTEM_MESSAGE")).thenReturn(1L);
        when(emailRecordMapper.listRecords("SENT", "user@example.com", "SYSTEM_MESSAGE", 20, 0))
                .thenReturn(List.of(record));
        EmailRecordQueryService service = new EmailRecordQueryService(emailRecordMapper, Mappers.getMapper(EmailEntityMapper.class));

        EmailRecordPageVO page = service.listRecords(" sent ", "user@example.com", "system_message", 1, 20);

        assertEquals(1L, page.getTotal());
        assertEquals(9L, page.getRecords().get(0).getId());
        verify(emailRecordMapper).listRecords("SENT", "user@example.com", "SYSTEM_MESSAGE", 20, 0);
    }
}
