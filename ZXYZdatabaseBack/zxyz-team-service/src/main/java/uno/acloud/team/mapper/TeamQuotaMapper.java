package uno.acloud.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.team.entity.TeamQuota;

@Mapper
public interface TeamQuotaMapper extends BaseMapper<TeamQuota> {

    @Insert("""
            INSERT INTO team_quota(team_id, member_limit, storage_limit, create_time, update_time)
            VALUES(#{teamId}, #{memberLimit}, #{storageLimit}, #{createTime}, #{updateTime})
            ON DUPLICATE KEY UPDATE member_limit = VALUES(member_limit), storage_limit = VALUES(storage_limit), update_time = VALUES(update_time)
            """)
    int upsertQuota(TeamQuota quota);

    @Select("""
            SELECT id, team_id AS teamId, member_limit AS memberLimit, storage_limit AS storageLimit,
                   create_time AS createTime, update_time AS updateTime
            FROM team_quota
            WHERE team_id = #{teamId}
            LIMIT 1
            """)
    TeamQuota getByTeamId(@Param("teamId") Long teamId);
}
