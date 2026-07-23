package uno.acloud.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.project.entity.ProjectQuota;

@Mapper
public interface ProjectQuotaMapper extends BaseMapper<ProjectQuota> {

    @Insert("""
            INSERT INTO project_quota(project_id, storage_limit, create_time, update_time)
            VALUES(#{projectId}, #{storageLimit}, #{createTime}, #{updateTime})
            ON DUPLICATE KEY UPDATE storage_limit = VALUES(storage_limit), update_time = VALUES(update_time)
            """)
    int upsertQuota(ProjectQuota quota);

    @Select("""
            SELECT id, project_id AS projectId, storage_limit AS storageLimit, create_time AS createTime, update_time AS updateTime
            FROM project_quota
            WHERE project_id = #{projectId}
            LIMIT 1
            """)
    ProjectQuota getByProjectId(@Param("projectId") Long projectId);

    @Select("""
            SELECT COALESCE(SUM(storage_limit), 0)
            FROM project_quota pq
            JOIN project p ON p.id = pq.project_id
            WHERE p.team_id = #{teamId}
              AND p.status = 0
            """)
    Long selectStorageLimitSumByTeamId(@Param("teamId") Long teamId);
}
