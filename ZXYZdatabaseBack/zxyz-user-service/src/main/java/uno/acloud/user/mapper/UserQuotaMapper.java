package uno.acloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.acloud.user.entity.UserQuota;

@Mapper
public interface UserQuotaMapper extends BaseMapper<UserQuota> {

    @Select("""
            SELECT id, user_id AS userId, storage_limit AS storageLimit, create_time AS createTime, update_time AS updateTime
            FROM user_quota
            WHERE user_id = #{userId}
            LIMIT 1
            """)
    UserQuota getByUserId(@Param("userId") Long userId);
}
