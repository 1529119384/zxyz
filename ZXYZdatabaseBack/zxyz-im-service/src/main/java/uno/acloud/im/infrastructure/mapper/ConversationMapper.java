package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.ImConversationMember;
import uno.acloud.im.vo.ConversationSummaryVO;
import uno.acloud.im.vo.TeamConversationVO;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<ImConversation> {

    @Insert("""
            INSERT INTO im_conversation (type, team_id, project_id, name, biz_key, direct_user_a, direct_user_b, status, read_only, create_time, update_time)
            VALUES (#{type}, #{teamId}, #{projectId}, #{name}, #{bizKey}, #{directUserA}, #{directUserB}, #{status}, COALESCE(#{readOnly}, 0), #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConversation(ImConversation conversation);

    @Insert("""
            INSERT INTO im_conversation (type, team_id, project_id, biz_key, direct_user_a, direct_user_b, status, read_only, create_time, update_time)
            VALUES ('SYSTEM', NULL, NULL, #{bizKey}, NULL, NULL, 0, 1, NOW(), NOW())
            ON DUPLICATE KEY UPDATE status = 0, update_time = update_time
            """)
    int upsertSystemConversation(@Param("bizKey") String bizKey);

    @Insert("""
            INSERT INTO im_conversation (type, team_id, project_id, biz_key, direct_user_a, direct_user_b, status, read_only, create_time, update_time)
            VALUES ('TEAM_NOTIFICATION', #{teamId}, NULL, #{bizKey}, NULL, NULL, 0, 1, NOW(), NOW())
            ON DUPLICATE KEY UPDATE status = 0, update_time = update_time
            """)
    int upsertTeamNotificationConversation(@Param("teamId") Long teamId, @Param("bizKey") String bizKey);

    @Select("SELECT id FROM im_conversation WHERE biz_key = #{bizKey} AND type = 'SYSTEM' LIMIT 1")
    Long getSystemConversationId(@Param("bizKey") String bizKey);

    @Select("SELECT id FROM im_conversation WHERE biz_key = #{bizKey} AND type = 'TEAM_NOTIFICATION' LIMIT 1")
    Long getTeamNotificationConversationId(@Param("bizKey") String bizKey);

    @Select("SELECT id FROM im_conversation WHERE type = 'TEAM' AND team_id = #{teamId} AND status = 0 LIMIT 1")
    Long getTeamConversationId(@Param("teamId") Long teamId);

    @Select("""
            SELECT id, type, team_id AS teamId, project_id AS projectId, name, biz_key AS bizKey, direct_user_a AS directUserA, direct_user_b AS directUserB,
                   status, read_only AS readOnly, create_time AS createTime, update_time AS updateTime
            FROM im_conversation
            WHERE id = #{conversationId}
              AND status = 0
            LIMIT 1
            """)
    ImConversation getConversationById(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT COUNT(*)
            FROM im_conversation_member
            WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND status = 0
            """)
    int countActiveConversationMember(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO im_conversation_member (conversation_id, user_id, last_read_message_id, unread_count, status, create_time, update_time)
            VALUES (#{conversationId}, #{userId}, 0, 0, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE status = 0, update_time = NOW()
            """)
    int upsertConversationMember(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("""
            SELECT user_id
            FROM im_conversation_member
            WHERE conversation_id = #{conversationId} AND status = 0
            ORDER BY id ASC
            """)
    List<Long> listActiveMemberUserIds(@Param("conversationId") Long conversationId);

    @Update("""
            UPDATE im_conversation_member
            SET unread_count = unread_count + 1,
                update_time = NOW()
            WHERE conversation_id = #{conversationId}
              AND status = 0
              AND user_id <> #{senderUserId}
            """)
    int incrementUnreadForOthers(@Param("conversationId") Long conversationId, @Param("senderUserId") Long senderUserId);

    @Update("""
            UPDATE im_conversation
            SET update_time = NOW()
            WHERE id = #{conversationId}
            """)
    int touchConversation(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT c.id,
                   c.type,
                   c.team_id AS teamId,
                   c.project_id AS projectId,
                   CASE
                       WHEN c.type = 'SYSTEM' THEN '系统消息'
                       WHEN c.type = 'TEAM_NOTIFICATION' THEN '团队消息'
                       WHEN c.type = 'DIRECT' THEN COALESCE(peer.name, peer.username, CONCAT('用户 ', peer.user_id))
                       WHEN c.type = 'PROJECT' THEN COALESCE(NULLIF(c.name, ''), CONCAT('项目 ', c.project_id))
                       ELSE t.name
                   END AS name,
                   CASE
                       WHEN c.type IN ('SYSTEM', 'TEAM_NOTIFICATION') THEN ''
                       WHEN c.type = 'DIRECT' THEN peer.avatar
                       ELSE t.avatar
                   END AS avatar,
                   cm.unread_count AS unreadCount,
                   CASE
                       WHEN c.type = 'DIRECT' AND c.direct_user_a = #{userId} THEN c.direct_user_b
                       WHEN c.type = 'DIRECT' THEN c.direct_user_a
                       ELSE NULL
                   END AS peerUserId,
                   peer.username AS peerUsername,
                   peer.name AS peerName,
                   peer.avatar AS peerAvatar,
                   c.update_time AS updateTime
            FROM im_conversation c
            JOIN im_conversation_member cm ON cm.conversation_id = c.id
            LEFT JOIN im_team t ON t.id = c.team_id
            LEFT JOIN im_user_profile peer ON peer.user_id = CASE
                    WHEN c.type = 'DIRECT' AND c.direct_user_a = #{userId} THEN c.direct_user_b
                    WHEN c.type = 'DIRECT' THEN c.direct_user_a
                    ELSE NULL
                END
            WHERE cm.user_id = #{userId}
              AND cm.status = 0
              AND c.status = 0
              AND (c.type = 'SYSTEM' OR ((#{teamId} IS NULL AND c.team_id IS NULL) OR (#{teamId} IS NOT NULL AND c.team_id = #{teamId})))
              AND (c.team_id IS NULL OR EXISTS (
                  SELECT 1 FROM team_member tm
                  WHERE tm.team_id = c.team_id AND tm.user_id = #{userId} AND tm.status = 0
              ))
            ORDER BY c.update_time DESC, c.id DESC
            """)
    List<ConversationSummaryVO> listMyConversations(@Param("userId") Long userId, @Param("teamId") Long teamId);

    default List<ConversationSummaryVO> listMyConversations(Long userId) {
        return listMyConversations(userId, null);
    }

    @Select("""
            SELECT c.id AS conversationId,
                   c.team_id AS teamId,
                   t.name AS teamName,
                   t.avatar AS teamAvatar,
                   c.type
            FROM im_conversation c
            JOIN team_member tm ON tm.team_id = c.team_id
            JOIN im_team t ON t.id = c.team_id
            WHERE c.type = 'TEAM'
              AND c.team_id = #{teamId}
              AND c.status = 0
              AND tm.user_id = #{userId}
              AND tm.status = 0
            LIMIT 1
            """)
    TeamConversationVO getTeamConversation(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT c.id, c.type, c.team_id AS teamId, c.project_id AS projectId, c.name, c.biz_key AS bizKey,
                   c.direct_user_a AS directUserA, c.direct_user_b AS directUserB,
                   c.status, c.read_only AS readOnly, c.create_time AS createTime, c.update_time AS updateTime
            FROM im_conversation c
            LEFT JOIN im_conversation_member cm
                ON cm.conversation_id = c.id AND cm.user_id = #{userId} AND cm.status = 0
            WHERE c.id = #{conversationId}
              AND c.status = 0
              AND cm.id IS NOT NULL
            LIMIT 1
            """)
    ImConversation getConversationWithActiveMember(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("""
            SELECT id, type, team_id AS teamId, project_id AS projectId, name, biz_key AS bizKey, direct_user_a AS directUserA, direct_user_b AS directUserB,
                   status, read_only AS readOnly, create_time AS createTime, update_time AS updateTime
            FROM im_conversation
            WHERE biz_key = #{bizKey}
              AND status = 0
            LIMIT 1
            """)
    ImConversation getConversationByBizKey(@Param("bizKey") String bizKey);

    @Update("""
            UPDATE im_conversation
            SET name = #{name},
                update_time = NOW()
            WHERE id = #{conversationId}
              AND type = 'PROJECT'
            """)
    int updateProjectConversationName(@Param("conversationId") Long conversationId, @Param("name") String name);

    @Update("UPDATE im_conversation SET read_only = 1, update_time = NOW() WHERE biz_key = CONCAT('PROJECT:', #{projectId})")
    int archiveProjectConversation(@Param("projectId") Long projectId);

    @Select("""
            SELECT c.id,
                   c.type,
                   c.team_id AS teamId,
                   c.project_id AS projectId,
                   CASE
                       WHEN c.type = 'SYSTEM' THEN '系统消息'
                       WHEN c.type = 'TEAM_NOTIFICATION' THEN '团队消息'
                       WHEN c.type = 'DIRECT' THEN COALESCE(peer.name, peer.username, CONCAT('用户 ', peer.user_id))
                       WHEN c.type = 'PROJECT' THEN COALESCE(NULLIF(c.name, ''), CONCAT('项目 ', c.project_id))
                       ELSE t.name
                   END AS name,
                   CASE
                       WHEN c.type IN ('SYSTEM', 'TEAM_NOTIFICATION') THEN ''
                       WHEN c.type = 'DIRECT' THEN peer.avatar
                       ELSE t.avatar
                   END AS avatar,
                   cm.unread_count AS unreadCount,
                   CASE
                       WHEN c.type = 'DIRECT' AND c.direct_user_a = #{userId} THEN c.direct_user_b
                       WHEN c.type = 'DIRECT' THEN c.direct_user_a
                       ELSE NULL
                   END AS peerUserId,
                   peer.username AS peerUsername,
                   peer.name AS peerName,
                   peer.avatar AS peerAvatar,
                   c.update_time AS updateTime
            FROM im_conversation c
            JOIN im_conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = #{userId} AND cm.status = 0
            LEFT JOIN im_team t ON t.id = c.team_id
            LEFT JOIN im_user_profile peer ON peer.user_id = CASE
                    WHEN c.type = 'DIRECT' AND c.direct_user_a = #{userId} THEN c.direct_user_b
                    WHEN c.type = 'DIRECT' THEN c.direct_user_a
                    ELSE NULL
                END
            WHERE c.id = #{conversationId}
              AND c.status = 0
            LIMIT 1
            """)
    ConversationSummaryVO getConversationSummary(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("""
            SELECT id,
                   conversation_id AS conversationId,
                   user_id AS userId,
                   last_read_message_id AS lastReadMessageId,
                   unread_count AS unreadCount,
                   status,
                   create_time AS createTime,
                   update_time AS updateTime
            FROM im_conversation_member
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 0
            LIMIT 1
            """)
    ImConversationMember getConversationMember(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Update("""
            UPDATE im_conversation_member
            SET last_read_message_id = #{lastReadMessageId},
                unread_count = (
                    SELECT COUNT(*)
                    FROM im_message
                    WHERE conversation_id = #{conversationId}
                      AND status = 0
                      AND sender_user_id <> #{userId}
                      AND id > #{lastReadMessageId}
                ),
                update_time = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 0
            """)
    int updateReadState(@Param("conversationId") Long conversationId,
                        @Param("userId") Long userId,
                        @Param("lastReadMessageId") Long lastReadMessageId);

    @Update("""
            UPDATE im_conversation_member cm
            JOIN im_conversation c ON c.id = cm.conversation_id
            SET cm.status = 1, cm.update_time = NOW()
            WHERE c.team_id = #{teamId}
              AND cm.user_id = #{userId}
              AND cm.status = 0
            """)
    int deactivateUserConversationsInTeam(@Param("teamId") Long teamId, @Param("userId") Long userId);
}
