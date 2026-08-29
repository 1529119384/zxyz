package uno.acloud.file.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.acloud.file.infrastructure.entity.FileObjectRef;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileObjectRefMapper extends BaseMapper<FileObjectRef> {

    @Insert({
            "INSERT INTO file_object_ref (object_key, ref_count, delete_status, delete_retry_count, next_retry_time, last_delete_error, create_time, modify_time, storage_provider)",
            "VALUES (#{objectKey}, #{increment}, #{activeStatus}, 0, NULL, NULL, NOW(3), NOW(3), #{storageProvider})",
            "ON DUPLICATE KEY UPDATE",
            "ref_count = CASE WHEN delete_status IN ('ACTIVE', 'PENDING_DELETE') THEN ref_count + #{increment} ELSE ref_count END,",
            "delete_status = CASE WHEN delete_status IN ('ACTIVE', 'PENDING_DELETE') THEN #{activeStatus} ELSE delete_status END,",
            "delete_retry_count = CASE WHEN delete_status IN ('ACTIVE', 'PENDING_DELETE') THEN 0 ELSE delete_retry_count END,",
            "next_retry_time = CASE WHEN delete_status IN ('ACTIVE', 'PENDING_DELETE') THEN NULL ELSE next_retry_time END,",
            "last_delete_error = CASE WHEN delete_status IN ('ACTIVE', 'PENDING_DELETE') THEN NULL ELSE last_delete_error END,",
            "modify_time = NOW(3)"
    })
    int incrementReference(@Param("objectKey") String objectKey,
                           @Param("increment") int increment,
                           @Param("activeStatus") String activeStatus,
                           @Param("storageProvider") String storageProvider);

    @Update({
            "UPDATE file_object_ref",
            "SET ref_count = ref_count - #{decrement}, modify_time = NOW(3)",
            "WHERE object_key = #{objectKey}",
            "  AND delete_status = #{activeStatus}",
            "  AND ref_count >= #{decrement}"
    })
    int decrementReference(@Param("objectKey") String objectKey,
                           @Param("decrement") int decrement,
                           @Param("activeStatus") String activeStatus);

    @Select({
            "SELECT object_key, ref_count, delete_status, delete_retry_count, storage_provider,",
            "       next_retry_time, last_delete_error, create_time, modify_time, delete_time",
            "FROM file_object_ref",
            "WHERE object_key = #{objectKey}"
    })
    FileObjectRef selectByKey(@Param("objectKey") String objectKey);

    @Update({
            "UPDATE file_object_ref",
            "SET delete_status = #{pendingStatus},",
            "    delete_retry_count = 0,",
            "    next_retry_time = NOW(3),",
            "    last_delete_error = NULL,",
            "    modify_time = NOW(3)",
            "WHERE object_key = #{objectKey}",
            "  AND ref_count = 0",
            "  AND delete_status = #{activeStatus}"
    })
    int markPendingIfUnused(@Param("objectKey") String objectKey,
                            @Param("activeStatus") String activeStatus,
                            @Param("pendingStatus") String pendingStatus);

    @Select({
            "SELECT object_key, ref_count, delete_status, delete_retry_count, storage_provider, next_retry_time, last_delete_error, create_time, modify_time, delete_time",
            "FROM file_object_ref",
            "WHERE ref_count = 0",
            "  AND delete_status = #{pendingStatus}",
            "  AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))",
            "ORDER BY modify_time ASC",
            "LIMIT #{limit}"
    })
    List<FileObjectRef> listPendingDeletes(@Param("pendingStatus") String pendingStatus,
                                           @Param("limit") int limit);

    @Update({
            "UPDATE file_object_ref",
            "SET delete_status = #{deletingStatus}, modify_time = NOW(3)",
            "WHERE object_key = #{objectKey}",
            "  AND ref_count = 0",
            "  AND delete_status = #{pendingStatus}"
    })
    int markDeleting(@Param("objectKey") String objectKey,
                     @Param("pendingStatus") String pendingStatus,
                     @Param("deletingStatus") String deletingStatus);

    @Update({
            "UPDATE file_object_ref",
            "SET delete_status = #{deletedStatus},",
            "    delete_time = NOW(3),",
            "    next_retry_time = NULL,",
            "    last_delete_error = NULL,",
            "    modify_time = NOW(3)",
            "WHERE object_key = #{objectKey}",
            "  AND ref_count = 0",
            "  AND delete_status = #{deletingStatus}"
    })
    int markDeleted(@Param("objectKey") String objectKey,
                    @Param("deletingStatus") String deletingStatus,
                    @Param("deletedStatus") String deletedStatus);

    @Update({
            "UPDATE file_object_ref",
            "SET delete_status = #{pendingStatus},",
            "    delete_retry_count = delete_retry_count + 1,",
            "    next_retry_time = #{nextRetryTime},",
            "    last_delete_error = #{lastDeleteError},",
            "    modify_time = NOW(3)",
            "WHERE object_key = #{objectKey}",
            "  AND ref_count = 0",
            "  AND delete_status = #{deletingStatus}"
    })
    int markDeleteFailed(@Param("objectKey") String objectKey,
                         @Param("deletingStatus") String deletingStatus,
                         @Param("pendingStatus") String pendingStatus,
                         @Param("lastDeleteError") String lastDeleteError,
                         @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Delete("DELETE FROM file_object_ref WHERE delete_status = 'DELETED' AND delete_time < DATE_SUB(NOW(), INTERVAL 30 DAY)")
    int deleteExpiredDeleted();
}
