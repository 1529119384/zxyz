package uno.acloud.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectMember;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Select("""
            SELECT p.id, p.team_id AS teamId, p.name, p.description, p.leader_user_id AS leaderUserId,
                   p.conversation_id AS conversationId, p.status, p.create_time AS createTime, p.update_time AS updateTime
            FROM project p
            WHERE p.team_id = #{teamId}
              AND p.status = 0
            ORDER BY p.update_time DESC, p.id DESC
            """)
    List<Project> listVisibleProjects(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM project
            WHERE team_id = #{teamId}
              AND status = 0
              AND name = #{name}
            """)
    int countActiveByTeamIdAndName(@Param("teamId") Long teamId, @Param("name") String name);

    @Insert("""
            INSERT INTO project_member(project_id, user_id, role_code, join_time)
            VALUES(#{projectId}, #{userId}, #{roleCode}, #{joinTime})
            ON DUPLICATE KEY UPDATE role_code = VALUES(role_code)
            """)
    int upsertMember(ProjectMember member);

    @Select("""
            SELECT id, project_id AS projectId, user_id AS userId, role_code AS roleCode, join_time AS joinTime
            FROM project_member
            WHERE project_id = #{projectId}
            ORDER BY id ASC
            """)
    List<ProjectMember> listMembers(@Param("projectId") Long projectId);

    @Select("SELECT COUNT(*) FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
    int countMember(@Param("projectId") Long projectId, @Param("userId") Long userId);

    /**
     * 一次取回「该用户在某团队内已加入的项目 id」（项目列表页批量装配用）。
     *
     * <p>存在的理由是列表页 N+1：逐项目调 {@link #countMember} 会让 P 个项目产生 P 次查询。</p>
     *
     * <p>语义等价性：调用方拿到的是 {@code listVisibleProjects(teamId, userId)} 的结果集，
     * 其过滤条件是 {@code p.team_id = #{teamId} AND p.status = 0}。本方法用同一个 JOIN 谓词，
     * 因此「返回集合是否含某项目」恰好等价于对该项目调 {@code countMember(...) > 0}。</p>
     *
     * <p>⚠️ 刻意不做成 {@code project_id IN (...)} 的动态 SQL：入参项目数不可控，
     * 且用 JOIN 表达能顺带把「跨团队项目 id 串入」这一整类错误挡在 SQL 层。</p>
     */
    @Select("""
            SELECT pm.project_id
            FROM project_member pm
            JOIN project p ON p.id = pm.project_id
            WHERE pm.user_id = #{userId}
              AND p.team_id = #{teamId}
              AND p.status = 0
            """)
    List<Long> listMemberProjectIds(@Param("teamId") Long teamId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM project WHERE leader_user_id = #{userId} AND status = 0")
    int countActiveProjectsLedBy(@Param("userId") Long userId);

    @Update("UPDATE project SET leader_user_id = #{leaderUserId}, update_time = NOW() WHERE id = #{projectId}")
    int updateLeader(@Param("projectId") Long projectId, @Param("leaderUserId") Long leaderUserId);

    @Update("UPDATE project SET conversation_id = #{conversationId}, update_time = NOW() WHERE id = #{projectId}")
    int updateConversationId(@Param("projectId") Long projectId, @Param("conversationId") Long conversationId);

    @Update("UPDATE project SET status = 1, update_time = NOW() WHERE id = #{projectId}")
    int archiveProject(@Param("projectId") Long projectId);

    @Delete("DELETE FROM project_member WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
