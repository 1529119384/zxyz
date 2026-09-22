package uno.acloud.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.project.entity.ProjectQuota;

import java.util.List;

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

    /**
     * 一次取回某团队全部「可见项目」的配额行（项目列表页批量装配用）。
     *
     * <p>存在的理由是列表页 N+1：逐项目调 {@link #getByProjectId} 会让 P 个项目产生 P 次查询。</p>
     *
     * <p>⚠️ 过滤条件必须与 {@code ProjectMapper.listVisibleProjects}
     * （{@code p.team_id = #{teamId} AND p.status = 0}）以及本文件的
     * {@link #selectStorageLimitSumByTeamId} 保持<b>逐字一致</b> ——
     * 三者口径一旦不同步，「列表页显示的配额上限」与「团队配额合计」就会对不上，
     * 而差别只在有归档项目时才显形，属静默不一致。</p>
     */
    @Select("""
            SELECT pq.id, pq.project_id AS projectId, pq.storage_limit AS storageLimit,
                   pq.create_time AS createTime, pq.update_time AS updateTime
            FROM project_quota pq
            JOIN project p ON p.id = pq.project_id
            WHERE p.team_id = #{teamId}
              AND p.status = 0
            """)
    List<ProjectQuota> listByVisibleTeamProjects(@Param("teamId") Long teamId);
}
