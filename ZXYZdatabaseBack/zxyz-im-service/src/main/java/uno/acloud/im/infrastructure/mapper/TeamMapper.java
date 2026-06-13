package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.domain.model.Team;
import uno.acloud.im.domain.model.TeamMember;
import uno.acloud.im.vo.TeamMemberVO;
import uno.acloud.im.vo.TeamVO;

import java.util.List;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    @Insert("""
            INSERT INTO im_team (name, avatar, description, owner_user_id, status, create_time, update_time)
            VALUES (#{name}, #{avatar}, #{description}, #{ownerUserId}, #{status}, #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTeam(Team team);

    @Insert("""
            INSERT INTO im_team (id, name, avatar, description, owner_user_id, status, create_time, update_time)
            VALUES (#{id}, #{name}, #{avatar}, #{description}, #{ownerUserId}, #{status}, #{createTime}, #{updateTime})
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                avatar = VALUES(avatar),
                description = VALUES(description),
                owner_user_id = VALUES(owner_user_id),
                status = VALUES(status),
                update_time = VALUES(update_time)
            """)
    int upsertTeamWithId(Team team);

    @Insert("""
            INSERT INTO team_member (team_id, user_id, role_code, status, join_time)
            VALUES (#{teamId}, #{userId}, #{roleCode}, #{status}, #{joinTime})
            ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), status = VALUES(status)
            """)
    int upsertMember(TeamMember member);

    @Select("""
            SELECT id, team_id, user_id, role_code, status, join_time
            FROM team_member
            WHERE team_id = #{teamId} AND user_id = #{userId} AND status = 0
            """)
    TeamMember getActiveMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT user_id
            FROM team_member
            WHERE team_id = #{teamId} AND status = 0
            ORDER BY id ASC
            """)
    List<Long> listActiveMemberUserIds(Long teamId);

    @Update("""
            UPDATE team_member
            SET status = 2
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
              AND status = 0
            """)
    int deactivateMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Update("""
            UPDATE team_member
            SET role_code = #{roleCode}
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
              AND status = 0
            """)
    int updateMemberRoleLabel(@Param("teamId") Long teamId, @Param("userId") Long userId, @Param("roleCode") String roleCode);

    @Select("""
            SELECT COUNT(*)
            FROM team_member_role tmr
            JOIN team_role tr ON tr.id = tmr.team_role_id
            JOIN team_member tm ON tm.team_id = tmr.team_id AND tm.user_id = tmr.user_id AND tm.status = 0
            WHERE tmr.team_id = #{teamId}
              AND tr.role_code = 'team_owner'
            """)
    int countActiveOwners(Long teamId);

    @Select("""
            SELECT t.id, t.name, t.avatar, t.description, t.owner_user_id AS ownerUserId,
                   COALESCE(tr.role_code, tm.role_code) AS myRoleCode,
                   t.create_time AS createTime
            FROM im_team t
            JOIN team_member tm ON tm.team_id = t.id
            LEFT JOIN team_member_role tmr ON tmr.team_id = tm.team_id AND tmr.user_id = tm.user_id
            LEFT JOIN team_role tr ON tr.id = tmr.team_role_id
            WHERE tm.user_id = #{userId} AND tm.status = 0 AND t.status = 0
            ORDER BY t.create_time DESC, t.id DESC
            """)
    @Results(id = "teamVoResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "name", column = "name"),
            @Result(property = "avatar", column = "avatar"),
            @Result(property = "description", column = "description"),
            @Result(property = "ownerUserId", column = "ownerUserId"),
            @Result(property = "myRoleCode", column = "myRoleCode"),
            @Result(property = "createTime", column = "createTime")
    })
    List<TeamVO> listMyTeams(Long userId);

    @Select("""
            SELECT id, name, avatar, description, owner_user_id AS ownerUserId, status, create_time AS createTime, update_time AS updateTime
            FROM im_team
            WHERE id = #{teamId} AND status = 0
            LIMIT 1
            """)
    Team getTeamById(Long teamId);

    @Update("""
            UPDATE im_team
            SET name = #{name},
                avatar = #{avatar},
                description = #{description},
                update_time = NOW()
            WHERE id = #{id} AND status = 0
            """)
    int updateTeamProfile(Team team);

    @Select("""
            SELECT tm.user_id AS userId, up.username, up.name, up.avatar,
                   COALESCE(tr.role_code, tm.role_code) AS roleCode,
                   tm.join_time AS joinTime
            FROM team_member tm
            LEFT JOIN im_user_profile up ON up.user_id = tm.user_id
            LEFT JOIN team_member_role tmr ON tmr.team_id = tm.team_id AND tmr.user_id = tm.user_id
            LEFT JOIN team_role tr ON tr.id = tmr.team_role_id
            WHERE tm.team_id = #{teamId} AND tm.status = 0
            ORDER BY FIELD(COALESCE(tr.role_code, tm.role_code), 'team_owner', 'team_admin', 'team_member'), tm.join_time ASC
            """)
    List<TeamMemberVO> listMembers(Long teamId);
}
