package uno.acloud.email.application;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.convert.EmailEntityMapper;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.dto.EmailRecordPageVO;
import uno.acloud.email.vo.EmailRecordVO;
import uno.acloud.exception.BusinessException;

import java.util.List;
import java.util.Locale;

@Service
public class EmailRecordQueryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final EmailRecordMapper emailRecordMapper;
    private final EmailEntityMapper emailEntityMapper;

    public EmailRecordQueryService(EmailRecordMapper emailRecordMapper,
                                   EmailEntityMapper emailEntityMapper) {
        this.emailRecordMapper = emailRecordMapper;
        this.emailEntityMapper = emailEntityMapper;
    }

    public EmailRecordPageVO listRecords(String status, String recipient, String businessType, Integer page, Integer pageSize) {
        int safePage = normalizePage(page);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedStatus = normalizeFilter(status, true);
        String normalizedRecipient = normalizeFilter(recipient, false);
        String normalizedBusinessType = normalizeFilter(businessType, true);
        long total = emailRecordMapper.countRecords(normalizedStatus, normalizedRecipient, normalizedBusinessType);
        List<EmailRecordVO> records = emailRecordMapper.listRecords(
                        normalizedStatus,
                        normalizedRecipient,
                        normalizedBusinessType,
                        safePageSize,
                        (safePage - 1) * safePageSize
                ).stream()
                .map(emailEntityMapper::toRecordVO)
                .toList();
        return new EmailRecordPageVO(total, safePage, safePageSize, records);
    }

    public EmailRecordVO getRecord(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件记录不存在");
        }
        EmailRecord record = emailRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件记录不存在");
        }
        return emailEntityMapper.toRecordVO(record);
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? DEFAULT_PAGE : page;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    @Nullable
    private String normalizeFilter(@Nullable String value, boolean upperCase) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return upperCase ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }
}
