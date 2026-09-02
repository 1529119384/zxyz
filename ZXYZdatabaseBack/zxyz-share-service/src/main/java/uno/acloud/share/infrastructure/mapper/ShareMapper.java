package uno.acloud.share.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.entity.ShareItem;

import java.util.List;

@Mapper
public interface ShareMapper extends BaseMapper<Share> {

    @Insert({
            "<script>",
            "INSERT INTO share_item (share_id, file_id, file_type, create_time) VALUES",
            "<foreach collection='items' item='item' separator=','>",
            "(#{item.shareId}, #{item.fileId}, #{item.fileType}, #{item.createTime})",
            "</foreach>",
            "</script>"
    })
    int batchInsertShareItems(@Param("items") List<ShareItem> items);

    @Select("SELECT id, share_key, user_id, username, password, expire_time, max_access_count, current_access_count, status, create_time FROM share WHERE share_key = #{shareKey}")
    Share getByShareKey(String shareKey);

    @Select("SELECT id, share_key, user_id, username, expire_time, max_access_count, current_access_count, status, create_time FROM share WHERE id = #{shareId} AND user_id = #{userId}")
    Share getByIdAndUserId(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM share WHERE user_id = #{userId}")
    int countByUserId(Long userId);

    @Select("SELECT id, share_key, user_id, username, expire_time, max_access_count, current_access_count, status, create_time FROM share WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC")
    List<Share> listByUserId(Long userId);

    @Select("SELECT id, share_key, user_id, username, expire_time, max_access_count, current_access_count, status, create_time FROM share WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{pageSize}")
    List<Share> listPageByUserId(@Param("userId") Long userId,
                                 @Param("offset") Integer offset,
                                 @Param("pageSize") Integer pageSize);

    @Select("SELECT id, share_id, file_id, file_type, create_time FROM share_item WHERE share_id = #{shareId} ORDER BY create_time ASC, id ASC")
    List<ShareItem> listItemsByShareId(Long shareId);

    @Delete("DELETE FROM share_item WHERE share_id = #{shareId}")
    int deleteShareItemsByShareId(@Param("shareId") Long shareId);

    @Delete({
            "<script>",
            "DELETE FROM share_item WHERE file_id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    int deleteShareItemsByFileIds(@Param("fileIds") List<Long> fileIds);

    @Update("UPDATE share SET status = #{status} WHERE id = #{shareId}")
    int updateStatusById(@Param("shareId") Long shareId, @Param("status") Integer status);

    @Update("UPDATE share SET status = #{nextStatus} WHERE id = #{shareId} AND status = #{currentStatus}")
    int updateStatusByIdAndCurrentStatus(@Param("shareId") Long shareId,
                                         @Param("currentStatus") Integer currentStatus,
                                         @Param("nextStatus") Integer nextStatus);

    @Update("UPDATE share SET status = #{status} WHERE id = #{shareId} AND user_id = #{userId}")
    int updateStatusByIdAndUserId(@Param("shareId") Long shareId, @Param("userId") Long userId, @Param("status") Integer status);

    @Update("UPDATE share SET current_access_count = current_access_count + 1 WHERE id = #{shareId}")
    int incrementAccessCount(Long shareId);

    @Update("UPDATE share SET current_access_count = current_access_count + 1 " +
            "WHERE id = #{shareId} AND max_access_count IS NOT NULL AND current_access_count < max_access_count")
    int tryIncrementAccessCountWhenUnderLimit(Long shareId);

    @Update("UPDATE share SET username = #{username} WHERE user_id = #{userId}")
    int updateUsernameByUserId(@Param("userId") Long userId, @Param("username") String username);
}
