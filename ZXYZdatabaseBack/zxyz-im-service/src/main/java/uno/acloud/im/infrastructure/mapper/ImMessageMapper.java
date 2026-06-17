package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.domain.model.ImMessage;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    /**
     * Prevent accidental use of BaseMapper.selectById — the content column is JSON type
     * and requires CAST(content AS CHAR) which only getById() provides.
     */
    @Override
    default ImMessage selectById(Serializable id) {
        throw new UnsupportedOperationException("Use getById() for JSON content casting");
    }

    @Select("""
            SELECT id,
                   conversation_id AS conversationId,
                   sender_user_id AS senderUserId,
                   message_type AS messageType,
                   CAST(content AS CHAR) AS content,
                   status,
                   recall_by_user_id AS recallByUserId,
                   recall_time AS recallTime,
                   recall_reason AS recallReason,
                   client_message_id AS clientMessageId,
                   create_time AS createTime
            FROM im_message
            WHERE conversation_id = #{conversationId}
              AND sender_user_id = #{senderUserId}
              AND client_message_id = #{clientMessageId}
            LIMIT 1
            """)
    ImMessage getByClientMessageId(@Param("conversationId") Long conversationId,
                                   @Param("senderUserId") Long senderUserId,
                                   @Param("clientMessageId") String clientMessageId);

    @Select("""
            SELECT id,
                   conversation_id AS conversationId,
                   sender_user_id AS senderUserId,
                   message_type AS messageType,
                   CAST(content AS CHAR) AS content,
                   status,
                   recall_by_user_id AS recallByUserId,
                   recall_time AS recallTime,
                   recall_reason AS recallReason,
                   client_message_id AS clientMessageId,
                   create_time AS createTime
            FROM im_message
            WHERE id = #{messageId}
            LIMIT 1
            """)
    ImMessage getById(@Param("messageId") Long messageId);

    @Select("""
            SELECT COUNT(*)
            FROM im_message
            WHERE conversation_id = #{conversationId}
              AND id = #{messageId}
              AND status IN (0, 1)
            """)
    int countConversationMessage(@Param("conversationId") Long conversationId, @Param("messageId") Long messageId);

    @Update("""
            UPDATE im_message
            SET status = 1,
                recall_by_user_id = #{recallByUserId},
                recall_time = NOW(),
                recall_reason = #{reason}
            WHERE id = #{messageId}
              AND status = 0
            """)
    int recallMessage(@Param("messageId") Long messageId,
                      @Param("recallByUserId") Long recallByUserId,
                      @Param("reason") String reason);

    // SQL moved to ImMessageMapper.xml with <sql> fragments for deduplication (M40)
    // and correlated EXISTS replaced with LEFT JOIN aggregation (M31)
    List<ImMessageViewRow> listMessageRows(@Param("conversationId") Long conversationId,
                                           @Param("currentUserId") Long currentUserId,
                                           @Param("afterMessageId") Long afterMessageId,
                                           @Param("afterTime") LocalDateTime afterTime,
                                           @Param("beforeMessageId") Long beforeMessageId,
                                           @Param("limit") int limit);

    @Select("""
            SELECT m.id AS messageId,
                   m.conversation_id AS conversationId,
                   m.sender_user_id AS senderUserId,
                   up.username AS senderUsername,
                   up.name AS senderName,
                   up.avatar AS senderAvatar,
                   m.message_type AS messageType,
                   CASE
                       WHEN m.status = 0 AND m.message_type IN ('PROJECT_CREATION_APPLICATION', 'PROJECT_CREATE_RESULT') THEN CAST(m.content AS CHAR)
                       WHEN m.status = 0 AND m.message_type = 'TEXT' THEN m.content_extracted
                       WHEN m.status = 0 AND m.message_type IN ('ANNOUNCEMENT', 'SYSTEM_NOTIFICATION') THEN CAST(m.content AS CHAR)
                       ELSE NULL
                   END AS content,
                   CASE WHEN m.status = 0 THEN CAST(m.content AS CHAR) ELSE NULL END AS rawContent,
                   m.client_message_id AS clientMessageId,
                   m.status AS status,
                   m.recall_by_user_id AS recallByUserId,
                   m.recall_time AS recallTime,
                   m.recall_reason AS recallReason,
                   FALSE AS readByPeer,
                   0 AS readCount,
                   m.create_time AS createTime
            FROM im_message m
            LEFT JOIN im_user_profile up ON up.user_id = m.sender_user_id
            WHERE m.id = #{messageId}
            LIMIT 1
            """)
    ImMessageViewRow getMessageRowById(@Param("messageId") Long messageId);

    @Select("""
            SELECT m.id AS messageId,
                   m.conversation_id AS conversationId,
                   m.sender_user_id AS senderUserId,
                   up.username AS senderUsername,
                   up.name AS senderName,
                   up.avatar AS senderAvatar,
                   m.message_type AS messageType,
                   CASE
                       WHEN m.status = 0 AND m.message_type IN ('PROJECT_CREATION_APPLICATION', 'PROJECT_CREATE_RESULT') THEN CAST(m.content AS CHAR)
                       WHEN m.status = 0 AND m.message_type = 'TEXT' THEN m.content_extracted
                       WHEN m.status = 0 AND m.message_type IN ('ANNOUNCEMENT', 'SYSTEM_NOTIFICATION') THEN CAST(m.content AS CHAR)
                       ELSE NULL
                   END AS content,
                   CASE WHEN m.status = 0 THEN CAST(m.content AS CHAR) ELSE NULL END AS rawContent,
                   m.client_message_id AS clientMessageId,
                   m.status AS status,
                   m.recall_by_user_id AS recallByUserId,
                   m.recall_time AS recallTime,
                   m.recall_reason AS recallReason,
                   FALSE AS readByPeer,
                   0 AS readCount,
                   m.create_time AS createTime
            FROM im_message m
            LEFT JOIN im_user_profile up ON up.user_id = m.sender_user_id
            WHERE m.conversation_id = #{conversationId}
              AND m.status = 0
              AND (
                  m.content_extracted LIKE CONCAT(#{keyword}, '%')
                  OR CAST(m.content AS CHAR) LIKE CONCAT(#{keyword}, '%')
              )
            ORDER BY m.id DESC
            LIMIT #{limit}
            """)
    List<ImMessageViewRow> searchMessageRows(@Param("conversationId") Long conversationId,
                                             @Param("keyword") String keyword,
                                             @Param("limit") int limit);

    @Select("""
            SELECT m.id
            FROM im_message m
            WHERE m.conversation_id = #{conversationId}
              AND m.message_type = 'PROJECT_CREATION_APPLICATION'
              AND m.status = 0
              AND JSON_EXTRACT(m.content, '$.applicationId') = CAST(#{applicationId} AS JSON)
            ORDER BY m.id DESC
            LIMIT 1
            """)
    Long getLatestProjectCreateRequestMessageId(@Param("conversationId") Long conversationId, @Param("applicationId") Long applicationId);

    @Update("""
            UPDATE im_message
            SET content = #{content}
            WHERE id = #{messageId}
              AND status = 0
            """)
    int updateMessageContent(@Param("messageId") Long messageId, @Param("content") String content);
}
