package uno.acloud.im.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.im.infrastructure.persistence.entity.UserProfile;
import uno.acloud.im.vo.UserProfileVO;

import java.util.List;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    @Select("SELECT user_id, username, name, avatar, create_time, update_time FROM im_user_profile WHERE user_id = #{userId}")
    UserProfile getByUserId(Long userId);

    @Select("""
            SELECT user_id, username, name, avatar
            FROM im_user_profile
            WHERE username LIKE CONCAT(#{keyword}, '%')
               OR CAST(user_id AS CHAR) = #{keyword}
            ORDER BY user_id ASC
            LIMIT #{limit}
            """)
    List<UserProfileVO> searchLocal(@Param("keyword") String keyword, @Param("limit") int limit);

    @Insert("""
            INSERT INTO im_user_profile (user_id, username, name, avatar, create_time, update_time)
            VALUES (#{userId}, #{username}, #{name}, #{avatar}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                username = VALUES(username),
                name = VALUES(name),
                avatar = VALUES(avatar),
                update_time = NOW()
            """)
    int upsert(UserProfile profile);

    @Insert("""
            INSERT INTO im_user_profile (user_id, username, create_time, update_time)
            VALUES (#{userId}, #{username}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE update_time = update_time
            """)
    int insertPlaceholder(@Param("userId") Long userId, @Param("username") String username);

    @Delete("DELETE FROM im_user_profile WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
