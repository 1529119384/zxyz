package uno.acloud.email.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailSenderSnapshot;
import uno.acloud.email.domain.EmailTemplate;
import uno.acloud.email.infrastructure.EmailRecordMapper;
import uno.acloud.email.infrastructure.EmailTemplateMapper;
import uno.acloud.email.infrastructure.SimpleJavaMailSender;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Slf4j
@Service
@RefreshScope
public class EmailDispatchService {

    private static final int MAX_SUBJECT_LENGTH = 255;
    private static final int MAX_BUSINESS_LENGTH = 64;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final EmailRecordMapper emailRecordMapper;
    private final EmailTemplateMapper emailTemplateMapper;
    private final EmailTemplateRenderer templateRenderer;
    private final SimpleJavaMailSender simpleJavaMailSender;
    private final EmailProperties emailProperties;
    private final EmailSendingAvailabilityService emailSendingAvailabilityService;
    private final Executor emailTaskExecutor;
    /** 邮件最大重试次数，默认 4 次 */
    private final int maxAttempts;

    public EmailDispatchService(EmailRecordMapper emailRecordMapper,
                                EmailTemplateMapper emailTemplateMapper,
                                EmailTemplateRenderer templateRenderer,
                                SimpleJavaMailSender simpleJavaMailSender,
                                EmailProperties emailProperties,
                                EmailSendingAvailabilityService emailSendingAvailabilityService,
                                @Qualifier("emailTaskExecutor") Executor emailTaskExecutor,
                                @Value("${app.email.max-retry-count:4}") int maxAttempts) {
        this.emailRecordMapper = emailRecordMapper;
        this.emailTemplateMapper = emailTemplateMapper;
        this.templateRenderer = templateRenderer;
        this.simpleJavaMailSender = simpleJavaMailSender;
        this.emailProperties = emailProperties;
        this.emailSendingAvailabilityService = emailSendingAvailabilityService;
        this.emailTaskExecutor = emailTaskExecutor;
        this.maxAttempts = maxAttempts;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long send(String recipient,
                     String subject,
                     String contentHtml,
                     String businessType,
                     String businessId,
                     LocalDateTime scheduledTime) {
        emailSendingAvailabilityService.requireSendingAvailable();
        return createPendingRecord(recipient, subject, contentHtml, businessType, businessId, scheduledTime);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Long> sendBatch(List<String> recipients,
                                String subject,
                                String contentHtml,
                                String businessType,
                                String businessId,
                                LocalDateTime scheduledTime) {
        emailSendingAvailabilityService.requireSendingAvailable();
        List<String> normalizedRecipients = normalizeRecipients(recipients);
        if (normalizedRecipients.isEmpty()) {
            return List.of();
        }
        return normalizedRecipients.stream()
                .map(recipient -> createPendingRecord(recipient, subject, contentHtml, businessType, businessId, scheduledTime))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Long> sendBatchByTemplate(List<String> recipients,
                                          String templateCode,
                                          Map<String, ?> variables,
                                          String businessType,
                                          String businessId,
                                          LocalDateTime scheduledTime) {
        emailSendingAvailabilityService.requireSendingAvailable();
        EmailTemplate template = requireTemplate(templateCode);
        String subject = templateRenderer.renderSubject(template.getSubjectTemplate(), variables);
        String contentHtml = templateRenderer.renderHtml(template.getContentHtml(), variables);
        List<String> normalizedRecipients = normalizeRecipients(recipients);
        if (normalizedRecipients.isEmpty()) {
            return List.of();
        }
        return normalizedRecipients.stream()
                .map(recipient -> createPendingRecord(recipient, subject, contentHtml, businessType, businessId, scheduledTime))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long sendByTemplate(String recipient,
                               String templateCode,
                               Map<String, ?> variables,
                               String businessType,
                               String businessId,
                               LocalDateTime scheduledTime) {
        emailSendingAvailabilityService.requireSendingAvailable();
        EmailTemplate template = requireTemplate(templateCode);
        String subject = templateRenderer.renderSubject(template.getSubjectTemplate(), variables);
        String contentHtml = templateRenderer.renderHtml(template.getContentHtml(), variables);
        return createPendingRecord(recipient, subject, contentHtml, businessType, businessId, scheduledTime);
    }

    public int dispatchDueRecords(int limit) {
        int safeLimit = Math.max(1, limit);
        List<EmailRecord> dueRecords = emailRecordMapper.listDuePending(safeLimit);
        int successCount = 0;
        for (EmailRecord record : dueRecords) {
            if (dispatchRecord(record.getId())) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * P2-2（ISSUE/54）：回捞「卡死在 SENDING」的邮件记录。
     *
     * <p>{@code markSending} 把记录置为 SENDING 后若进程崩溃/被杀，该记录永远停在
     * SENDING，而 {@link #dispatchDueRecords(int)} 只扫 PENDING —— 邮件从此永久滞留。
     * 由 {@code EmailRetryTask} 周期性调用本方法：
     * <ul>
     *   <li>超龄（update_time 早于 now - {@code email.sending-stale-minutes}，默认 10 分钟）
     *       且未超重试上限的记录：重置回 PENDING，由随后的 dispatchDueRecords 正常捞起重发；
     *       attempt_count 不再 +1（markSending 已计过一次，重复计数会让重试预算被崩溃额外消耗）；</li>
     *   <li>超龄且已达上限的记录：改判 FAILED 终态，避免「回捞 → 再崩 → 再回捞」死循环。</li>
     * </ul>
     * 返回值为诊断信息（成功重置数），发送成功与否仍以 dispatchDueRecords 的统计为准。
     */
    public int recoverStaleSending() {
        int staleMinutes = Math.max(1, emailProperties.getSendingStaleMinutes());
        int recovered = emailRecordMapper.recoverStaleSending(staleMinutes);
        int abandoned = emailRecordMapper.failStaleSending(staleMinutes);
        if (recovered > 0 || abandoned > 0) {
            log.warn("SENDING 卡死记录回捞完成：重发回捞={}，超限放弃={}", recovered, abandoned);
        }
        return recovered;
    }

    public boolean dispatchRecord(Long recordId) {
        if (recordId == null || emailRecordMapper.markSending(recordId) != 1) {
            return false;
        }
        EmailRecord record = emailRecordMapper.selectById(recordId);
        try {
            EmailSenderSnapshot senderSnapshot = simpleJavaMailSender.send(record);
            if (senderSnapshot != null) {
                emailRecordMapper.updateSenderSnapshot(
                        recordId,
                        senderSnapshot.serverConfigId(),
                        senderSnapshot.serverConfigName(),
                        senderSnapshot.senderUsername()
                );
            }
            emailRecordMapper.markSent(recordId);
            return true;
        } catch (Exception e) {
            handleSendFailure(record, e);
            return false;
        }
    }

    private EmailRecord buildRecord(String recipient,
                                    String subject,
                                    String contentHtml,
                                    String businessType,
                                    String businessId,
                                    LocalDateTime scheduledTime) {
        LocalDateTime now = LocalDateTime.now();
        EmailRecord record = new EmailRecord();
        record.setRecipient(normalizeEmail(recipient));
        record.setSubject(requireText(subject, "邮件主题不能为空", MAX_SUBJECT_LENGTH, "邮件主题不能超过 255 个字符"));
        record.setContentHtml(requireText(contentHtml, "邮件内容不能为空"));
        record.initializeNew(maxAttempts);
        record.setScheduledTime(scheduledTime);
        record.setBusinessType(optionalText(businessType, MAX_BUSINESS_LENGTH, "业务类型不能超过 64 个字符"));
        record.setBusinessId(optionalText(businessId, MAX_BUSINESS_LENGTH, "业务 ID 不能超过 64 个字符"));
        record.setCreateTime(now);
        record.setUpdateTime(now);
        return record;
    }

    private Long createPendingRecord(String recipient,
                                     String subject,
                                     String contentHtml,
                                     String businessType,
                                     String businessId,
                                     LocalDateTime scheduledTime) {
        EmailRecord record = buildRecord(recipient, subject, contentHtml, businessType, businessId, scheduledTime);
        emailRecordMapper.insert(record);
        triggerAsyncIfDue(record);
        return record.getId();
    }

    private EmailTemplate requireTemplate(String templateCode) {
        String normalizedCode = requireText(templateCode, "模板编码不能为空", 64, "模板编码不能超过 64 个字符");
        EmailTemplate template = emailTemplateMapper.getActiveByCode(normalizedCode);
        if (template == null || !template.isUsable()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邮件模板不存在或未启用");
        }
        return template;
    }

    private List<String> normalizeRecipients(List<String> recipients) {
        if (recipients == null) {
            return List.of();
        }
        return recipients.stream()
                .map(this::normalizeEmail)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    private String normalizeEmail(String email) {
        String normalizedEmail = requireText(email, "收件人邮箱不能为空", 255, "收件人邮箱不能超过 255 个字符").toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "收件人邮箱格式不正确");
        }
        return normalizedEmail;
    }

    private void triggerAsyncIfDue(EmailRecord record) {
        if (!emailProperties.isAsync() || record.getScheduledTime() != null && record.getScheduledTime().isAfter(LocalDateTime.now())) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // B-14（2026-10-03）：CLAUDE.md 强制「afterCommit 回调必须 try-catch」——
                    // 此刻事务已提交、记录已落库为 PENDING；提交线程池被拒（原 CallerRuns 改 Abort 后
                    // 队列满会抛 RejectedExecutionException）绝不能冒泡进 Spring 事务同步链。
                    // 记录保持 PENDING，由 EmailRetryTask 定时 dispatchDueRecords 兜底发送。
                    try {
                        emailTaskExecutor.execute(() -> dispatchRecord(record.getId()));
                    } catch (Exception e) {
                        log.warn("邮件异步发送任务提交失败（记录保持 PENDING，等待定时重试兜底）：recordId={}",
                                record.getId(), e);
                    }
                }
            });
            return;
        }
        // 无事务上下文的同步提交路径同样不得把拒绝异常抛给调用方（B-14）
        try {
            emailTaskExecutor.execute(() -> dispatchRecord(record.getId()));
        } catch (Exception e) {
            log.warn("邮件异步发送任务提交失败（记录保持 PENDING，等待定时重试兜底）：recordId={}",
                    record.getId(), e);
        }
    }

    private void handleSendFailure(EmailRecord record, Exception e) {
        String failureReason = normalizeFailureReason(e);
        if (!record.canRetry()) {
            record.markFailed(failureReason);
            emailRecordMapper.markFailed(record.getId(), failureReason);
            log.warn("邮件发送最终失败：recordId={}, recipient={}, reason={}", record.getId(), record.getRecipient(), failureReason, e);
            return;
        }
        record.scheduleRetry(resolveNextRetryTime(record.getAttemptCount()));
        emailRecordMapper.markRetry(record.getId(), failureReason, record.getNextRetryTime());
        log.warn("邮件发送失败，等待重试：recordId={}, recipient={}, attempt={}/{}",
                record.getId(), record.getRecipient(), record.getAttemptCount(), record.getMaxAttempts(), e);
    }

    private LocalDateTime resolveNextRetryTime(int attemptCount) {
        int delayMinutes = switch (attemptCount) {
            case 1 -> 1;
            case 2 -> 5;
            default -> 15;
        };
        return LocalDateTime.now().plusMinutes(delayMinutes);
    }

    private String normalizeFailureReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }
}
