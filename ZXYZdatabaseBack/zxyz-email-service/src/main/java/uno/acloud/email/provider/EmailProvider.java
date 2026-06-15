package uno.acloud.email.provider;

import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailSenderSnapshot;

/**
 * 邮件提供者接口
 * <p>
 * 定义统一的邮件发送操作，支持多种后端实现（SMTP、SendGrid 等）。
 * </p>
 */
public interface EmailProvider {

    /**
     * 提供者唯一标识
     *
     * @return 如 "smtp", "sendgrid"
     */
    String providerId();

    /**
     * 提供者显示名称
     *
     * @return 如 "SMTP 邮件服务", "SendGrid"
     */
    String displayName();

    /**
     * 发送邮件
     *
     * @param record 邮件记录
     * @return 发送者快照（包含服务器配置信息）
     */
    EmailSenderSnapshot send(EmailRecord record);

    /**
     * 测试连接
     *
     * @return 测试结果消息
     */
    String testConnection();
}
