package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.PageResult;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.convert.EmailEntityMapper;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.vo.EmailRecordVO;

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

        PageResult<EmailRecordVO> page = service.listRecords(" sent ", "user@example.com", "system_message", 1, 20);

        assertEquals(1L, page.getTotal());
        assertEquals(9L, page.getList().get(0).getId());
        // 信封字段名必须是 list —— 旧实现叫 records，与文件列表/回收站/分享/审计日志四处不一致。
        assertEquals(1, page.getPage().intValue());
        assertEquals(20, page.getPageSize().intValue());
        verify(emailRecordMapper).listRecords("SENT", "user@example.com", "SYSTEM_MESSAGE", 20, 0);
    }

    /**
     * 上限必须与前端 el-pagination 的选项上界（200）一致。
     *
     * <p>旧实现硬编码 {@code MAX_PAGE_SIZE = 100}，而 {@code EMAIL_RECORD_PAGE_SIZE_OPTIONS}
     * 与空间列表共用同一组选项 ⇒ 用户选 200 时后端只按 100 分页、前端却按 200 算总页数，
     * 中后段记录永远翻不到。这条用例把「上限 == PageResult.MAX_PAGE_SIZE」钉死。</p>
     */
    @Test
    void listRecordsClampsPageSizeToGlobalMax() {
        when(emailRecordMapper.countRecords(null, null, null)).thenReturn(1L);
        when(emailRecordMapper.listRecords(null, null, null, PageResult.MAX_PAGE_SIZE, 0))
                .thenReturn(List.of());
        EmailRecordQueryService service =
                new EmailRecordQueryService(emailRecordMapper, Mappers.getMapper(EmailEntityMapper.class));

        PageResult<EmailRecordVO> page = service.listRecords(null, null, null, 1, 5000);

        assertEquals(PageResult.MAX_PAGE_SIZE, page.getPageSize().intValue());
        // 钳制后的值必须真的下发给查询，否则上限只是"回给前端好看"。
        verify(emailRecordMapper).listRecords(null, null, null, PageResult.MAX_PAGE_SIZE, 0);
    }

    @Test
    void listRecordsFallsBackToDefaultPageSizeWhenUnspecified() {
        when(emailRecordMapper.countRecords(null, null, null)).thenReturn(0L);
        when(emailRecordMapper.listRecords(null, null, null, 20, 0)).thenReturn(List.of());
        EmailRecordQueryService service =
                new EmailRecordQueryService(emailRecordMapper, Mappers.getMapper(EmailEntityMapper.class));

        PageResult<EmailRecordVO> page = service.listRecords(null, null, null, null, null);

        assertEquals(1, page.getPage().intValue());
        assertEquals(20, page.getPageSize().intValue());
        assertEquals(0L, page.getTotal().longValue());
        assertEquals(List.of(), page.getList());
    }
}
