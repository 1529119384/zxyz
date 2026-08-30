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

    @Select({
            "SELECT object_key",
            "FROM file_object_ref",
            "WHERE object_key LIKE CONCAT(#{prefix}, '%')"
    })
    List<String> selectObjectKeysByPrefix(@Param("prefix") String prefix);

    /**
     * 孤儿对象登记：仅当 object_key 尚不存在任何行时插入一条 PENDING_DELETE 记录。
     * <p>
     * 已存在的行（无论 ACTIVE/DELETING/DELETED 等状态）一律不修改（INSERT IGNORE 被忽略，
     * 返回 0），只对完全没有 ref 行的对象生效。ref_count 置 0 使其满足
     * FileObjectDeleteRetryTask 的 {@code listPendingDeletes} 条件（ref_count = 0），
     * 从而真正进入物理删除管道。
     * </p>
     *
     * @param objectKey      对象键
     * @param pendingStatus  待删除状态（PENDING_DELETE）
     * @param storageProvider 存储提供者标识（oss）
     * @return 插入行数：1 = 新增孤儿登记，0 = 已存在行未修改
     */
    @Insert({
            "INSERT IGNORE INTO file_object_ref",
            "(object_key, ref_count, delete_status, delete_retry_count, next_retry_time, create_time, modify_time, storage_provider)",
            "VALUES (#{objectKey}, 0, #{pendingStatus}, 0, NOW(3), NOW(3), NOW(3), #{storageProvider})"
    })
    int markOrphanPendingDelete(@Param("objectKey") String objectKey,
                                @Param("pendingStatus") String pendingStatus,
                                @Param("storageProvider") String storageProvider);
}
