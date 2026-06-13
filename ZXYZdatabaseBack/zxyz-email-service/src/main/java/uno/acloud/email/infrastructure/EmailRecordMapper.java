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
}
