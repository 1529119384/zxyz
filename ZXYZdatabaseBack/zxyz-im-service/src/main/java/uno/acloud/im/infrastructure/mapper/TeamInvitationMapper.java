package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.infrastructure.persistence.entity.TeamInvitation;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface TeamInvitationMapper extends BaseMapper<TeamInvitation> {

    @Insert("""
            INSERT INTO team_invitation (team_id, invitee_user_id, inviter_user_id, status, expire_time, create_time)
            VALUES (#{teamId}, #{inviteeUserId}, #{inviterUserId}, #{status}, #{expireTime}, #{createTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInvitation(TeamInvitation invitation);

    @Select("""
            SELECT id, team_id, invitee_user_id, inviter_user_id, status, expire_time, handle_time, create_time
            FROM team_invitation
            WHERE team_id = #{teamId} AND invitee_user_id = #{inviteeUserId} AND status = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    TeamInvitation getPendingByTeamAndInvitee(@Param("teamId") Long teamId, @Param("inviteeUserId") Long inviteeUserId);

    @Update("UPDATE team_invitation SET status = #{status}, handle_time = NOW() WHERE id = #{id} AND status = 0")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 66 号二.5：分批删除已终态且超保留期的行（im_stale_rows 清理任务用）。
     */
    @Delete("DELETE FROM team_invitation WHERE status != 0 AND create_time < #{cutoff} LIMIT #{batchLimit}")
    int deleteTerminatedOlderThanBatch(@Param("cutoff") LocalDateTime cutoff, @Param("batchLimit") int batchLimit);
}