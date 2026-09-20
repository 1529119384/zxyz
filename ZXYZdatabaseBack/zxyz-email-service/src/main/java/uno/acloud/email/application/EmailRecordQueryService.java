package uno.acloud.email.application;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.convert.EmailEntityMapper;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.vo.EmailRecordVO;
import uno.acloud.exception.BusinessException;

import java.util.List;
import java.util.Locale;

@Service
public class EmailRecordQueryService {

    /** 邮件记录的历史默认页长（与其他列表一样，默认值按接口口径，上限交给 PageResult）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final EmailRecordMapper emailRecordMapper;
    private final EmailEntityMapper emailEntityMapper;

    public EmailRecordQueryService(EmailRecordMapper emailRecordMapper,
                                   EmailEntityMapper emailEntityMapper) {
        this.emailRecordMapper = emailRecordMapper;
        this.emailEntityMapper = emailEntityMapper;
    }

    /**
     * 邮件发送记录（分页）。
     *
     * <p>信封自 07-P2-4 起由本服务自带的 {@code EmailRecordPageVO} 换成 {@link PageResult}。
     * 这是本批里<b>唯一真正的契约变化</b>：列表字段名从 {@code records} 变成 {@code list}
     * （与文件列表、回收站、分享、审计日志四处对齐）。前端 {@code EmailRecordPanel.vue}
     * 已同批改为 {@code list} 优先、回落 {@code records}，部署窗口期内不会出现「记录全空」。</p>
     *
     * <p>顺带把页长上限从硬编码的 100 提到 {@link PageResult#MAX_PAGE_SIZE}（前端选项上界 200），
     * 并把原来的 {@code normalizePageSize(pageSize, ...)}（拼写少个 s，与后端其他类型不一致）去掉。</p>
     */
    public PageResult<EmailRecordVO> listRecords(String status, String recipient, String businessType, Integer page, Integer pageSize) {
        int safePage = PageResult.normalizePage(page);
        int safePageSize = PageResult.normalizePageSize(pageSize, DEFAULT_PAGE_SIZE);
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
        return PageResult.of(safePage, safePageSize, total, records);
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

    @Nullable
    private String normalizeFilter(@Nullable String value, boolean upperCase) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return upperCase ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }
}
