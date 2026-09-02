package uno.acloud.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.team.entity.TeamUserDefaultSync;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TeamUserDefaultSyncMapper extends BaseMapper<TeamUserDefaultSync> {

    /**
     * 幂等插入待同步记录：以 idempotency_key 唯一约束去重。
     * 若同用户已存在 DONE（已成功同步）终态行，则保持不变（避免覆盖已成功的同步）；
     * 若为 PENDING/FAILED（尚未成功），则重置为 PENDING 并刷新重试时间，重新触发同步。
     */
    @Insert("""
            INSERT INTO team_user_default_sync
                (user_id, team_id, status, next_retry_time, retry_count, idempotency_key, create_time, update_time)
            VALUES
                (#{userId}, #{teamId}, 'PENDING', NOW(3), 0, #{idempotencyKey}, NOW(3), NOW(3))
            ON DUPLICATE KEY UPDATE
                status          = IF(status = 'DONE', 'DONE', 'PENDING'),
                team_id         = IF(status = 'DONE', team_id, VALUES(team_id)),
                retry_count      = IF(status = 'DONE', retry_count, 0),
                next_retry_time  = IF(status = 'DONE', next_retry_time, NOW(3)),
                update_time      = NOW(3)
            """)
    int upsertPending(@Param("userId") Long userId,
                      @Param("teamId") Long teamId,
                      @Param("idempotencyKey") String idempotencyKey);

    /**
     * 扫描到点的待同步记录（status=PENDING 且 next_retry_time <= NOW(3)）。
     */
    @Select("""
            SELECT id, user_id AS userId, team_id AS teamId, status,
                   next_retry_time AS nextRetryTime, retry_count AS retryCount,
                   idempotency_key AS idempotencyKey, create_time AS createTime, update_time AS updateTime
            FROM team_user_default_sync
            WHERE status = 'PENDING'
              AND next_retry_time <= NOW(3)
            ORDER BY next_retry_time ASC, id ASC
            LIMIT #{limit}
            """)
    List<TeamUserDefaultSync> listDuePending(@Param("limit") int limit);

    /** 标记同步成功（终态）。 */
    @Update("""
            UPDATE team_user_default_sync
            SET status = 'DONE',
                next_retry_time = NOW(3),
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int markDone(@Param("id") Long id);

    /**
     * 标记同步失败、进入下一次重试：递增 retry_count 并推进 next_retry_time。
     * 仅当仍为 PENDING 时更新，避免与并发处理（如已 DONE）冲突。
     */
    @Update("""
            UPDATE team_user_default_sync
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                next_retry_time = #{nextRetryTime},
                update_time = NOW(3)
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** 标记最终失败（放弃重试，终态）。 */
    @Update("""
            UPDATE team_user_default_sync
            SET status = 'FAILED',
                next_retry_time = NOW(3),
                update_time = NOW(3)
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id);
}
