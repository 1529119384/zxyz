package uno.acloud.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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

    @Select("SELECT COUNT(*) FROM project WHERE leader_user_id = #{userId} AND status = 0")
    int countActiveProjectsLedBy(@Param("userId") Long userId);

    @Update("UPDATE project SET leader_user_id = #{leaderUserId}, update_time = NOW() WHERE id = #{projectId}")
    int updateLeader(@Param("projectId") Long projectId, @Param("leaderUserId") Long leaderUserId);

    @Update("UPDATE project SET conversation_id = #{conversationId}, update_time = NOW() WHERE id = #{projectId}")
    int updateConversationId(@Param("projectId") Long projectId, @Param("conversationId") Long conversationId);

    @Update("UPDATE project SET status = 1, update_time = NOW() WHERE id = #{projectId}")
    int archiveProject(@Param("projectId") Long projectId);
}
