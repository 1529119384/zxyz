package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.im.infrastructure.persistence.entity.UserPresence;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserPresenceMapper extends BaseMapper<UserPresence> {

    @Insert("""
            INSERT INTO im_user_presence (user_id, online, connection_count, last_active_time, update_time)
            VALUES (#{userId}, 1, #{connectionCount}, NULL, NOW())
            ON DUPLICATE KEY UPDATE
                online = 1,
                connection_count = #{connectionCount},
                update_time = NOW()
            """)
    int markOnline(@Param("userId") Long userId, @Param("connectionCount") int connectionCount);

    @Update("""
            UPDATE im_user_presence
            SET online = #{online}, connection_count = #{connectionCount}, update_time = NOW()
            WHERE user_id = #{userId}
            """)
    int updateOnlineCount(@Param("userId") Long userId,
                          @Param("online") boolean online,
                          @Param("connectionCount") int connectionCount);

    @Insert("""
            INSERT INTO im_user_presence (user_id, online, connection_count, last_active_time, update_time)
            VALUES (#{userId}, 0, 0, #{lastActiveTime}, NOW())
            ON DUPLICATE KEY UPDATE
                online = 0,
                connection_count = 0,
                last_active_time = #{lastActiveTime},
                update_time = NOW()
            """)
    int markOffline(@Param("userId") Long userId, @Param("lastActiveTime") LocalDateTime lastActiveTime);

    @Select("""
            SELECT user_id, online, connection_count, last_active_time, update_time
            FROM im_user_presence
            WHERE user_id = #{userId}
            """)
    UserPresence getByUserId(Long userId);

    @Select({
            "<script>",
            "SELECT user_id, online, connection_count, last_active_time, update_time",
            "FROM im_user_presence",
            "WHERE user_id IN",
            "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>",
            "#{userId}",
            "</foreach>",
            "</script>"
    })
    List<UserPresence> listByUserIds(@Param("userIds") List<Long> userIds);
}
