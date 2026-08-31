package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.infrastructure.persistence.entity.SystemNotification;
import uno.acloud.im.vo.SystemNotificationVO;

import java.util.List;

@Mapper
public interface SystemNotificationMapper extends BaseMapper<SystemNotification> {

    @Insert("""
            INSERT INTO system_notification
                (user_id, type, title, content, business_type, business_id, team_id, status, create_time)
            VALUES
                (#{userId}, #{type}, #{title}, #{content}, #{businessType}, #{businessId}, #{teamId}, #{status}, #{createTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertNotification(SystemNotification notification);

    @Insert("<script>" +
            "INSERT INTO system_notification (user_id, type, title, content, business_type, business_id, team_id, status, create_time) VALUES " +
            "<foreach collection='notifications' item='n' separator=','>" +
            "(#{n.userId}, #{n.type}, #{n.title}, #{n.content}, #{n.businessType}, #{n.businessId}, #{n.teamId}, #{n.status}, #{n.createTime})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("notifications") List<SystemNotification> notifications);

    @Select("""
            SELECT id, type, title, content, business_type AS businessType, business_id AS businessId, team_id AS teamId,
                   status, read_time AS readTime, create_time AS createTime
            FROM system_notification
            WHERE user_id = #{userId}
              AND (team_id IS NULL OR team_id = #{teamId})
            ORDER BY create_time DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<SystemNotificationVO> listByUser(@Param("userId") Long userId,
                                          @Param("teamId") Long teamId,
                                          @Param("offset") int offset,
                                          @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*)
            FROM system_notification
            WHERE user_id = #{userId}
              AND status = 0
              AND (team_id IS NULL OR team_id = #{teamId})
            """)
    int countUnread(@Param("userId") Long userId, @Param("teamId") Long teamId);

    @Update("UPDATE system_notification SET status = 1, read_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE system_notification
            SET status = 1, read_time = NOW()
            WHERE user_id = #{userId} AND business_type = #{businessType} AND business_id = #{businessId}
            """)
    int markBusinessRead(@Param("userId") Long userId,
                         @Param("businessType") String businessType,
                         @Param("businessId") Long businessId);
}
