package uno.acloud.im.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.infrastructure.persistence.entity.TeamInviteLink;
import uno.acloud.im.infrastructure.persistence.entity.TeamJoinRequest;
import uno.acloud.im.infrastructure.persistence.entity.TeamMute;
import uno.acloud.im.vo.TeamJoinRequestVO;
import uno.acloud.im.vo.TeamMuteVO;

import java.util.List;

@Mapper
public interface TeamManagementMapper {

    @Insert("""
            INSERT INTO team_mute (team_id, user_id, muted_by_user_id, reason, expire_time, status, create_time, update_time)
            VALUES (#{teamId}, #{userId}, #{mutedByUserId}, #{reason}, #{expireTime}, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE muted_by_user_id = VALUES(muted_by_user_id),
                                    reason = VALUES(reason),
                                    expire_time = VALUES(expire_time),
                                    status = 0,
                                    update_time = NOW()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertMute(TeamMute mute);

    @Update("""
            UPDATE team_mute
            SET status = 1, update_time = NOW()
            WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 0
            """)
    int disableMute(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT tm.id, tm.team_id AS teamId, tm.user_id AS userId, up.username, up.name,
                   tm.muted_by_user_id AS mutedByUserId, tm.reason, tm.expire_time AS expireTime, tm.create_time AS createTime
            FROM team_mute tm
            LEFT JOIN im_user_profile up ON up.user_id = tm.user_id
            WHERE tm.team_id = #{teamId}
              AND tm.status = 0
              AND (tm.expire_time IS NULL OR tm.expire_time > NOW())
            ORDER BY tm.create_time DESC
            """)
    List<TeamMuteVO> listActiveMutes(Long teamId);

    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, muted_by_user_id AS mutedByUserId,
                   reason, expire_time AS expireTime, status, create_time AS createTime, update_time AS updateTime
            FROM team_mute
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
              AND status = 0
              AND (expire_time IS NULL OR expire_time > NOW())
            LIMIT 1
            """)
    TeamMute getActiveMute(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO team_invite_link (team_id, token, created_by_user_id, expire_time, max_uses, used_count, status, create_time, update_time)
            VALUES (#{teamId}, #{token}, #{createdByUserId}, #{expireTime}, #{maxUses}, 0, 0, #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInviteLink(TeamInviteLink link);

    @Select("""
            SELECT id, team_id AS teamId, token, created_by_user_id AS createdByUserId, expire_time AS expireTime,
                   max_uses AS maxUses, used_count AS usedCount, status, create_time AS createTime, update_time AS updateTime
            FROM team_invite_link
            WHERE token = #{token}
            LIMIT 1
            """)
    TeamInviteLink getInviteLinkByToken(String token);

    @Update("""
            UPDATE team_invite_link
            SET used_count = used_count + 1, update_time = NOW()
            WHERE id = #{linkId}
            """)
    int incrementInviteLinkUsedCount(Long linkId);

    @Insert("""
            INSERT INTO team_join_request (team_id, user_id, link_id, status, create_time)
            VALUES (#{teamId}, #{userId}, #{linkId}, #{status}, #{createTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertJoinRequest(TeamJoinRequest request);

    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, link_id AS linkId, status,
                   audit_by_user_id AS auditByUserId, audit_time AS auditTime, create_time AS createTime
            FROM team_join_request
            WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 0
            LIMIT 1
            """)
    TeamJoinRequest getPendingJoinRequest(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT id, team_id AS teamId, user_id AS userId, link_id AS linkId, status,
                   audit_by_user_id AS auditByUserId, audit_time AS auditTime, create_time AS createTime
            FROM team_join_request
            WHERE id = #{requestId}
            LIMIT 1
            """)
    TeamJoinRequest getJoinRequestById(Long requestId);

    @Select("""
            SELECT jr.id, jr.team_id AS teamId, jr.user_id AS userId, up.username, up.name,
                   jr.status, jr.create_time AS createTime
            FROM team_join_request jr
            LEFT JOIN im_user_profile up ON up.user_id = jr.user_id
            WHERE jr.team_id = #{teamId}
              AND jr.status = 0
            ORDER BY jr.create_time ASC
            """)
    List<TeamJoinRequestVO> listPendingJoinRequests(Long teamId);

    @Update("""
            UPDATE team_join_request
            SET status = #{status}, audit_by_user_id = #{auditByUserId}, audit_time = NOW()
            WHERE id = #{requestId} AND status = 0
            """)
    int auditJoinRequest(@Param("requestId") Long requestId,
                         @Param("status") Integer status,
                         @Param("auditByUserId") Long auditByUserId);
}
