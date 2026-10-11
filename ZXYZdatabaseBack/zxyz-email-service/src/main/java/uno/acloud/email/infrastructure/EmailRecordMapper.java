package uno.acloud.email.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.email.domain.EmailRecord;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EmailRecordMapper extends BaseMapper<EmailRecord> {

    @Select("""
            SELECT id, recipient, subject, status, failure_reason, attempt_count, max_attempts,
                   next_retry_time, scheduled_time, sent_time, server_config_id, server_config_name,
                   sender_username, business_type, business_id, create_time, update_time
            FROM email_record
            WHERE status = 'PENDING'
              AND (scheduled_time IS NULL OR scheduled_time <= NOW(3))
              AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))
            ORDER BY COALESCE(next_retry_time, scheduled_time, create_time), id
            LIMIT #{limit}
            """)
    List<EmailRecord> listDuePending(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, recipient, subject, status, failure_reason, attempt_count, max_attempts,
                   next_retry_time, scheduled_time, sent_time, server_config_id, server_config_name, sender_username,
                   business_type, business_id, create_time, update_time
            FROM email_record
            <where>
                <if test='status != null and status != ""'>
                    AND status = #{status}
                </if>
                <if test='recipient != null and recipient != ""'>
                    AND recipient LIKE CONCAT(#{recipient}, '%')
                </if>
                <if test='businessType != null and businessType != ""'>
                    AND business_type = #{businessType}
                </if>
            </where>
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EmailRecord> listRecords(@Param("status") String status,
                                  @Param("recipient") String recipient,
                                  @Param("businessType") String businessType,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM email_record
            <where>
                <if test='status != null and status != ""'>
                    AND status = #{status}
                </if>
                <if test='recipient != null and recipient != ""'>
                    AND recipient LIKE CONCAT(#{recipient}, '%')
                </if>
                <if test='businessType != null and businessType != ""'>
                    AND business_type = #{businessType}
                </if>
            </where>
            </script>
            """)
    long countRecords(@Param("status") String status,
                      @Param("recipient") String recipient,
                      @Param("businessType") String businessType);

    @Update("""
            UPDATE email_record
            SET status = 'SENDING',
                attempt_count = attempt_count + 1,
                update_time = NOW(3)
            WHERE id = #{id}
              AND status = 'PENDING'
              AND (scheduled_time IS NULL OR scheduled_time <= NOW(3))
              AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))
            """)
    int markSending(@Param("id") Long id);

    @Update("""
            UPDATE email_record
            SET status = 'SENT',
                failure_reason = NULL,
                sent_time = NOW(3),
                next_retry_time = NULL,
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int markSent(@Param("id") Long id);

    @Update("""
            UPDATE email_record
            SET server_config_id = #{serverConfigId},
                server_config_name = #{serverConfigName},
                sender_username = #{senderUsername},
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int updateSenderSnapshot(@Param("id") Long id,
                             @Param("serverConfigId") Long serverConfigId,
                             @Param("serverConfigName") String serverConfigName,
                             @Param("senderUsername") String senderUsername);

    @Update("""
            UPDATE email_record
            SET status = 'PENDING',
                failure_reason = #{failureReason},
                next_retry_time = #{nextRetryTime},
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int markRetry(@Param("id") Long id,
                  @Param("failureReason") String failureReason,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("""
            UPDATE email_record
            SET status = 'FAILED',
                failure_reason = #{failureReason},
                next_retry_time = NULL,
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id, @Param("failureReason") String failureReason);

    /**
     * P2-2（ISSUE/54）：回捞「卡死在 SENDING」的僵尸记录。
     *
     * <p>{@link #markSending(Long)} 把记录置为 SENDING 后若进程崩溃/被杀，
     * 该记录永远停留在 SENDING —— 而 {@link #listDuePending(int)} 只扫 PENDING，
     * 这封邮件从此无人问津。本方法把 {@code update_time} 早于阈值的 SENDING
     * 记录重置回 PENDING（不推进 attempt_count：markSending 已计过一次，
     * 回捞只做状态归还），并写入 {@code next_retry_time = NOW(3)} 立即可被
     * {@link #listDuePending(int)} 捞起重发。</p>
     *
     * <p>两道防死循环闸门：
     * <ol>
     *   <li>{@code attempt_count < max_attempts}：与 {@code EmailRecord.canRetry()}
     *       同口径 —— 超过上限的记录改判 FAILED，放弃重试（见
     *       {@link #failStaleSending(int)}）；</li>
     *   <li>{@code update_time < NOW(3) - 阈值}：刚进入 SENDING 的正常发送
     *       不会被误回捞，避免与在途发送竞争造成重复投递。</li>
     * </ol></p>
     *
     * @return 重置的记录数（0 = 没有「超龄且未超上限」的 SENDING 记录）
     */
    @Update("""
            UPDATE email_record
            SET status = 'PENDING',
                next_retry_time = NOW(3),
                update_time = NOW(3)
            WHERE status = 'SENDING'
              AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleMinutes} MINUTE)
              AND attempt_count < max_attempts
            """)
    int recoverStaleSending(@Param("staleMinutes") int staleMinutes);

    /**
     * P2-2 配套：把「卡死在 SENDING 且已超过重试上限」的记录改判 FAILED。
     *
     * <p>不与 {@link #recoverStaleSending(int)} 合并成一条 SQL：两个分支语义不同
     * （回捞重发 vs 放弃），分开写可以让 {@code EmailRetryTask} 分别打日志，
     * 运维能直接从日志看出「多少封重发、多少封放弃」。失败原因写成固定文案
     * （正常失败路径由 {@link #markFailed(Long, String)} 写真实异常消息，
     * 这里的记录没有异常现场，只能如实标注「进程中断」）。</p>
     *
     * @return 改判的记录数（0 = 没有「超龄且超上限」的 SENDING 记录）
     */
    @Update("""
            UPDATE email_record
            SET status = 'FAILED',
                failure_reason = '发送进程中断（SENDING 超时回捞），已达最大尝试次数，放弃重试',
                next_retry_time = NULL,
                update_time = NOW(3)
            WHERE status = 'SENDING'
              AND update_time < DATE_SUB(NOW(3), INTERVAL #{staleMinutes} MINUTE)
              AND attempt_count >= max_attempts
            """)
    int failStaleSending(@Param("staleMinutes") int staleMinutes);
}
