package uno.acloud.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.project.entity.ProjectCreateRequest;

import java.util.List;

@Mapper
public interface ProjectCreateRequestMapper extends BaseMapper<ProjectCreateRequest> {

    @Select("""
            SELECT id, team_id AS teamId, requester_user_id AS requesterUserId,
                   project_name AS projectName, description, leader_user_id AS leaderUserId,
                   storage_limit AS storageLimit, status, reviewer_user_id AS reviewerUserId,
                   review_time AS reviewTime, review_reason AS reviewReason,
                   create_time AS createTime, update_time AS updateTime
            FROM project_create_request
            WHERE team_id = #{teamId}
              AND status = 0
            ORDER BY create_time ASC, id ASC
            """)
    List<ProjectCreateRequest> listPendingByTeamId(@Param("teamId") Long teamId);

    @Update("""
            UPDATE project_create_request
            SET status = #{status},
                reviewer_user_id = #{reviewerUserId},
                review_time = NOW(),
                review_reason = #{reviewReason},
                update_time = NOW()
            WHERE id = #{applicationId}
              AND status = 0
            """)
    int reviewPending(@Param("applicationId") Long applicationId,
                      @Param("status") Integer status,
                      @Param("reviewerUserId") Long reviewerUserId,
                      @Param("reviewReason") String reviewReason);

    @Select("""
            SELECT COUNT(*)
            FROM project_create_request
            WHERE team_id = #{teamId}
              AND status = 0
              AND project_name = #{projectName}
              AND (#{excludeApplicationId} IS NULL OR id <> #{excludeApplicationId})
            """)
    int countPendingSameName(@Param("teamId") Long teamId,
                             @Param("projectName") String projectName,
                             @Param("excludeApplicationId") Long excludeApplicationId);
}
