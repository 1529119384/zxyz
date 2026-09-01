package uno.acloud.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TeamMapper extends BaseMapper<Team> {

    // getTeamById removed — use selectById(teamId) from BaseMapper

    @Update("""
            UPDATE team
            SET name = #{name},
                avatar = #{avatar},
                description = #{description},
                update_time = #{updateTime}
            WHERE id = #{id}
              AND status = 0
            """)
    int updateTeamProfile(Team team);

    @Select("""
            SELECT t.id, t.name, t.avatar, t.description, t.owner_user_id AS ownerUserId, t.status,
                   t.create_time AS createTime, t.update_time AS updateTime
            FROM team t
            JOIN team_member tm ON tm.team_id = t.id
            WHERE tm.user_id = #{userId}
              AND tm.status IN (0, 1)
              AND t.status = 0
            ORDER BY t.id ASC
            """)
    List<Team> listMyTeams(@Param("userId") Long userId);

    /**
     * 管理端概览查询 — 不含 user/file_node 跨库 JOIN。
     * ownerUsername 和 usedStorage 由 Service 层通过 HTTP 调用填充。
     */
    @Select("""
            SELECT t.id,
                   t.name,
                   t.description,
                   t.owner_user_id AS ownerUserId,
                   COALESCE(member_stats.member_count, 0) AS memberCount,
                   tq.member_limit AS memberLimit,
                   tq.storage_limit AS storageLimit,
                   t.create_time AS createTime
            FROM team t
            LEFT JOIN (
                SELECT team_id, COUNT(*) AS member_count
                FROM team_member
                WHERE status IN (0, 1)
                GROUP BY team_id
            ) member_stats ON member_stats.team_id = t.id
            LEFT JOIN team_quota tq ON tq.team_id = t.id
            WHERE t.status = 0
            ORDER BY t.id ASC
            """)
    List<AdminTeamOverviewVO> listAdminTeamOverviews();

    @Insert("""
            INSERT INTO team_member(team_id, user_id, role_code, status, join_time, update_time)
            VALUES(#{teamId}, #{userId}, #{roleCode}, #{status}, #{joinTime}, #{updateTime})
            ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), status = VALUES(status), update_time = VALUES(update_time)
            """)
    int upsertMember(TeamMember member);

    /**
     * 列出团队成员 — 不含 user 表 JOIN。
     * 用户信息（username、name、avatar、email）由 Service 层通过 HTTP 调用填充。
     */
    @Select("""
            SELECT tm.id, tm.team_id AS teamId, tm.user_id AS userId, tm.role_code AS roleCode,
                   tm.status, tm.join_time AS joinTime, tm.update_time AS updateTime,
                   tm.personal_storage_limit AS personalStorageLimit
            FROM team_member tm
            WHERE tm.team_id = #{teamId}
              AND tm.status IN (0, 1)
            ORDER BY tm.id ASC
            """)
    List<TeamMember> listMembers(@Param("teamId") Long teamId);

    @Select("""
            SELECT tm.id, tm.team_id AS teamId, tm.user_id AS userId, tm.role_code AS roleCode,
                   tm.status, tm.join_time AS joinTime, tm.update_time AS updateTime
            FROM team_member tm
            WHERE tm.team_id = #{teamId}
              AND tm.user_id = #{userId}
              AND tm.status = 0
            LIMIT 1
            """)
    TeamMember getActiveMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM team_member WHERE user_id = #{userId} AND status IN (0, 1)")
    int countCurrentMemberships(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM team_member WHERE team_id = #{teamId} AND status IN (0, 1)")
    int countOccupiedMembers(@Param("teamId") Long teamId);

    @Select("""
            SELECT COUNT(*)
            FROM team_member
            WHERE team_id = #{teamId}
              AND status = 0
              AND role_code = 'team_owner'
            """)
    int countActiveOwners(@Param("teamId") Long teamId);

    @Select("""
            SELECT user_id
            FROM team_member
            WHERE team_id = #{teamId}
              AND status = 0
              AND role_code IN ('team_owner', 'team_admin')
            ORDER BY user_id ASC
            """)
    List<Long> listAdminUserIds(@Param("teamId") Long teamId);

    @Update("""
            UPDATE team_member
            SET status = #{status}, update_time = NOW()
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
            """)
    int updateMemberStatus(@Param("teamId") Long teamId, @Param("userId") Long userId, @Param("status") Integer status);

    @Update("""
            UPDATE team_member
            SET status = 2, personal_storage_limit = NULL, update_time = NOW()
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
              AND status = 0
            """)
    int removeMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Update("""
            UPDATE team_member
            SET personal_storage_limit = #{personalStorageLimit}, update_time = NOW()
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
            """)
    int updateMemberStorageLimit(@Param("teamId") Long teamId,
                                 @Param("userId") Long userId,
                                 @Param("personalStorageLimit") Long personalStorageLimit);

    /** 更新成员角色标签（反范式冗余字段，与 team_member_role 保持同步） */
    @Update("""
            UPDATE team_member
            SET role_code = #{roleCode}, update_time = NOW()
            WHERE team_id = #{teamId}
              AND user_id = #{userId}
            """)
    int updateMemberRoleLabel(@Param("teamId") Long teamId,
                              @Param("userId") Long userId,
                              @Param("roleCode") String roleCode);

    // ==================== 所有者注销后的终态处理 ====================

    /**
     * 继任者角色优先级表达式：team_owner &gt; team_admin &gt; 其它成员。
     * MyBatis 注解值必须是编译期常量，所以用常量拼接而非运行时取值。
     */
    String SUCCESSOR_ROLE_PRIORITY =
            "FIELD(role_code, '" + TeamRoleCodes.OWNER + "', '" + TeamRoleCodes.ADMIN + "')";

    /**
     * 转让团队所有权。
     * WHERE 带上 owner_user_id = #{fromUserId} 与 status = 0：既避免误改他人团队，
     * 也让「重复消费同一条用户注销事件」天然幂等（第二次执行影响行数为 0）。
     */
    @Update("""
            UPDATE team
            SET owner_user_id = #{toUserId},
                update_time = #{updateTime}
            WHERE id = #{teamId}
              AND owner_user_id = #{fromUserId}
              AND status = 0
            """)
    int transferOwner(@Param("teamId") Long teamId,
                      @Param("fromUserId") Long fromUserId,
                      @Param("toUserId") Long toUserId,
                      @Param("updateTime") LocalDateTime updateTime);

    /**
     * 解散团队（所有者注销且无继任者时的终态：status = 2）。
     * 同样带 owner_user_id + status = 0 条件，保证只有「当前所有者且仍正常」的团队能被解散。
     */
    @Update("""
            UPDATE team
            SET status = 2,
                update_time = #{updateTime}
            WHERE id = #{teamId}
              AND owner_user_id = #{ownerUserId}
              AND status = 0
            """)
    int dissolveTeam(@Param("teamId") Long teamId,
                     @Param("ownerUserId") Long ownerUserId,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 挑选继任所有者：优先 team_admin，其次最早加入（id 最小）的普通成员，排除即将注销的用户。
     * FIELD() 对未命中的角色返回 0，所以先用 "= 0" 把普通成员整体排到最后，
     * 再按 FIELD() 升序区分 team_owner(1) 与 team_admin(2)，最后用 id 保证结果稳定。
     */
    @Select("SELECT user_id FROM team_member "
            + "WHERE team_id = #{teamId} AND status = 0 AND user_id != #{excludeUserId} "
            + "ORDER BY " + SUCCESSOR_ROLE_PRIORITY + " = 0, " + SUCCESSOR_ROLE_PRIORITY + ", id ASC "
            + "LIMIT 1")
    Long selectSuccessorOwner(@Param("teamId") Long teamId, @Param("excludeUserId") Long excludeUserId);
}
