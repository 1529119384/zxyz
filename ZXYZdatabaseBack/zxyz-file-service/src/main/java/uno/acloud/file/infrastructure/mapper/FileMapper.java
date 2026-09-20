package uno.acloud.file.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.vo.FileSearchItemVO;

import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;

import java.util.List;
import java.util.Map;

@Mapper
public interface FileMapper {

    /**
     * 按 id 取节点（含已删除行）。
     *
     * <p>结果映射 {@code fileNodeResultMap}（含 {@code file_type} 判别器）由
     * {@code mapper/FileMapper.xml} 承载 —— 它是**抽象类** {@link FileNode} 能落到
     * {@link FileItem} / {@link Folder} 的唯一途径，本仓 11 条返回 {@code FileNode} 的语句共用它。</p>
     */
    @Select("SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE id = #{fileId}")
    @ResultMap("fileNodeResultMap")
    FileNode getFileNodeById(Long fileId);

    /**
     * 按 id 取活跃节点（{@code deleted = 0}）。
     *
     * <p>⚠️ 必须显式引用 {@code fileNodeResultMap}：本方法返回的是**抽象类** {@code FileNode}，
     * 而 {@code file_type} → {@code FileItem} / {@code Folder} 的落地只能由该 ResultMap 携带的
     * 判别器完成。缺了它，MyBatis 会拿抽象类去实例化并抛
     * {@code ReflectionException: Error instantiating class ...FileNode ... Cause: InstantiationException}
     * —— 也就是说「查到行」反而比「查不到行」更糟：三个内部端点
     * （{@code /api/internal/files/{id}/stream-info}、{@code /stream}、{@code /share-download-url}）
     * 的分享下载链路会整体 500。本仓其余 10 个返回 {@code FileNode} 的语句均已带该 ResultMap。</p>
     */
    @Select("SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE id = #{fileId} AND deleted = 0")
    @ResultMap("fileNodeResultMap")
    FileNode getActiveFileNodeById(Long fileId);

    // countActiveChildren / getParentId 的 SQL 已迁至 mapper/FileMapper.xml（P2-7 批次 1）

    int countActiveChildren(@Param("parentId") Long parentId);

    Long getParentId(@Param("fileId") Long fileId);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getFileNodesByIds(@Param("fileIds") List<Long> fileIds);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE deleted = 0 AND id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getActiveFileNodesByIds(@Param("fileIds") List<Long> fileIds);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "FROM file_node",
            "WHERE parent_id = #{parentId} AND deleted = 0",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND space_type = 3 AND project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (space_type IS NULL OR space_type = 1) AND team_id IS NULL AND (#{userId} IS NULL OR upload_user_id = #{userId})</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getFileNodesByParentId(@Param("parentId") Long parentId,
                                          @Param("teamId") Long teamId,
                                          @Param("spaceType") Integer spaceType,
                                          @Param("projectId") Long projectId,
                                          @Param("userId") Long userId);

    default List<FileNode> getFileNodesByParentId(Long parentId, Long teamId, Long userId) {
        return getFileNodesByParentId(parentId, teamId, null, null, userId);
    }

    default List<FileNode> getFileNodesByParentId(Long parentId) {
        return getFileNodesByParentId(parentId, null, null);
    }

    default List<FileNode> getFileNodesByParentId(Long parentId, Long teamId) {
        return getFileNodesByParentId(parentId, teamId, null);
    }

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "FROM file_node",
            "WHERE parent_id = #{parentId} AND deleted = 0",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND space_type = 3 AND project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (space_type IS NULL OR space_type = 1) AND team_id IS NULL AND (#{userId} IS NULL OR upload_user_id = #{userId})</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "ORDER BY file_type DESC, original_name ASC",
            "LIMIT #{limit} OFFSET #{offset}",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getFileNodesByParentIdPaged(@Param("parentId") Long parentId,
                                               @Param("teamId") Long teamId,
                                               @Param("spaceType") Integer spaceType,
                                               @Param("projectId") Long projectId,
                                               @Param("userId") Long userId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);

    /** 列表总数；WHERE 与 {@link #getFileNodesByParentId} 同形（已迁至 mapper/FileMapper.xml）。 */
    int countByParentId(@Param("parentId") Long parentId,
                        @Param("teamId") Long teamId,
                        @Param("spaceType") Integer spaceType,
                        @Param("projectId") Long projectId,
                        @Param("userId") Long userId);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "FROM file_node",
            "WHERE parent_id = #{parentId}",
            "<choose>",
            "  <when test='teamId == null'>AND team_id IS NULL AND (#{userId} IS NULL OR upload_user_id = #{userId})</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getChildrenByParentIdWithDeleted(@Param("parentId") Long parentId,
                                                    @Param("teamId") Long teamId,
                                                    @Param("userId") Long userId);

    default List<FileNode> getChildrenByParentIdWithDeleted(Long parentId) {
        return getChildrenByParentIdWithDeleted(parentId, null, null);
    }

    @Select("SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE parent_id = #{parentId}")
    @ResultMap("fileNodeResultMap")
    List<FileNode> getShareChildrenByParentIdWithDeleted(@Param("parentId") Long parentId);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path,",
            "       upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "FROM file_node",
            "WHERE parent_id IN",
            "<foreach collection='parentIds' item='parentId' open='(' separator=',' close=')'>",
            "    #{parentId}",
            "</foreach>",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getShareChildrenByParentIdsWithDeleted(@Param("parentIds") List<Long> parentIds);

    @Select({
            "<script>",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path,",
            "       upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "FROM file_node f",
            "WHERE f.deleted = 1",
            "  AND NOT EXISTS (",
            "      SELECT 1 FROM file_node p",
            "      WHERE p.id = f.parent_id AND p.deleted = 1",
            "  )",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND f.space_type = 3 AND f.project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (f.space_type IS NULL OR f.space_type = 1) AND f.team_id IS NULL AND f.upload_user_id = #{userId}</when>",
            "  <otherwise>AND f.team_id = #{teamId}</otherwise>",
            "</choose>",
            "ORDER BY modify_time DESC",
            "LIMIT #{limit} OFFSET #{offset}",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getFileNodesInRecycleBinPaged(@Param("teamId") Long teamId,
                                                 @Param("spaceType") Integer spaceType,
                                                 @Param("projectId") Long projectId,
                                                 @Param("userId") Long userId,
                                                 @Param("limit") int limit,
                                                 @Param("offset") int offset);

    /**
     * 回收站可见节点总数。
     *
     * <p>WHERE 条件必须与 {@link #getFileNodesInRecycleBinPaged} 逐字保持一致：
     * 两处一旦不同步，分页器就会出现"总条数与实际翻页结果对不上"的静默错位。
     */
    @Select({
            "<script>",
            "SELECT COUNT(*) FROM file_node f",
            "WHERE f.deleted = 1",
            "  AND NOT EXISTS (",
            "      SELECT 1 FROM file_node p",
            "      WHERE p.id = f.parent_id AND p.deleted = 1",
            "  )",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND f.space_type = 3 AND f.project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (f.space_type IS NULL OR f.space_type = 1) AND f.team_id IS NULL AND f.upload_user_id = #{userId}</when>",
            "  <otherwise>AND f.team_id = #{teamId}</otherwise>",
            "</choose>",
            "</script>"
    })
    int countFileNodesInRecycleBin(@Param("teamId") Long teamId,
                                   @Param("spaceType") Integer spaceType,
                                   @Param("projectId") Long projectId,
                                   @Param("userId") Long userId);

    /** 递归 CTE，取子树全部 id（已迁至 mapper/FileMapper.xml）。 */
    List<Long> collectDescendantIds(@Param("rootIds") List<Long> rootIds);

    @Select({
            "<script>",
            "WITH RECURSIVE descendants AS (",
            "    SELECT id, file_type, uuid_name, original_name, category, file_size, file_url,",
            "           store_path, upload_user_id, shared_user_id, team_id, space_type, project_id,",
            "           deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider",
            "    FROM file_node",
            "    WHERE parent_id IN",
            "    <foreach collection='parentIds' item='parentId' open='(' separator=',' close=')'>",
            "        #{parentId}",
            "    </foreach>",
            "    AND deleted = 0",
            "    UNION ALL",
            "    SELECT c.id, c.file_type, c.uuid_name, c.original_name, c.category, c.file_size, c.file_url,",
            "           c.store_path, c.upload_user_id, c.shared_user_id, c.team_id, c.space_type, c.project_id,",
            "           c.deleted_user_id, c.parent_id, c.create_time, c.modify_time, c.deleted, c.storage_provider",
            "    FROM file_node c",
            "    INNER JOIN descendants d ON c.parent_id = d.id",
            "    WHERE c.deleted = 0",
            ")",
            "SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM descendants",
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> collectDescendantNodes(@Param("parentIds") List<Long> parentIds);

    /** 取 OSS objectKey（已迁至 mapper/FileMapper.xml）。 */
    List<String> getOssKeysByIds(@Param("fileIds") List<Long> fileIds);

    @Insert("INSERT INTO file_node (file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider) VALUES (#{fileType}, #{uuidName}, #{originalName}, #{category}, #{fileSize}, #{fileUrl}, #{storePath}, #{uploadUserId}, #{sharedUserId}, #{teamId}, #{spaceType}, #{projectId}, #{deletedUserId}, #{parentId}, #{createTime}, #{modifyTime}, #{deleted}, #{storageProvider})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Integer insertFileItem(FileItem fileItem);

    @Insert("INSERT INTO file_node(file_type, original_name, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider) VALUES(#{fileType}, #{originalName}, #{storePath}, #{uploadUserId}, #{sharedUserId}, #{teamId}, #{spaceType}, #{projectId}, #{deletedUserId}, #{parentId}, #{createTime}, #{modifyTime}, #{deleted}, #{storageProvider})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Integer insertFolder(Folder folder);

    /** 同级同名去重用；WHERE 与 {@link #countByParentId} 同形（已迁至 mapper/FileMapper.xml）。 */
    List<String> getActiveNamesByParentIdAndFileType(@Param("parentId") Long parentId,
                                                     @Param("teamId") Long teamId,
                                                     @Param("spaceType") Integer spaceType,
                                                     @Param("projectId") Long projectId,
                                                     @Param("fileType") Integer fileType,
                                                     @Param("userId") Long userId);

    /** 关键词命中总数；与 {@link #searchByKeyword} 同形（已迁至 mapper/FileMapper.xml）。 */
    int countByKeyword(@Param("userId") long userId, @Param("teamId") Long teamId, @Param("keyword") String keyword);

    default int countByKeyword(long userId, String keyword) {
        return countByKeyword(userId, null, keyword);
    }

    @Select({
            "<script>",
            "SELECT id, file_type, original_name, category, file_size,",
            "parent_id, team_id AS teamId, create_time, modify_time",
            "FROM file_node",
            "WHERE deleted = 0",
            "<choose>",
            "  <when test='teamId == null'>AND team_id IS NULL AND upload_user_id = #{userId}</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "AND original_name LIKE CONCAT(#{keyword}, '%')",
            "ORDER BY modify_time DESC",
            "LIMIT #{pageSize} OFFSET #{offset}",
            "</script>"
    })
    @Results({
            @Result(column = "id", property = "id", id = true),
            @Result(column = "file_type", property = "fileType"),
            @Result(column = "original_name", property = "originalName"),
            @Result(column = "category", property = "category"),
            @Result(column = "file_size", property = "fileSize"),
            @Result(column = "parent_id", property = "parentId"),
            @Result(column = "teamId", property = "teamId"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "modify_time", property = "modifyTime")
    })
    List<FileSearchItemVO> searchByKeyword(@Param("userId") long userId,
                                           @Param("teamId") Long teamId,
                                           @Param("keyword") String keyword,
                                           @Param("pageSize") int pageSize,
                                           @Param("offset") int offset);

    default List<FileSearchItemVO> searchByKeyword(long userId, String keyword, int pageSize, int offset) {
        return searchByKeyword(userId, null, keyword, pageSize, offset);
    }

    // 以下 5 条单行 UPDATE 已迁至 mapper/FileMapper.xml（P2-7 批次 1）

    int renameNodeById(@Param("fileId") Long fileId,
                       @Param("originalName") String originalName,
                       @Param("storePath") String storePath);

    int moveNodeById(@Param("fileId") Long fileId,
                     @Param("originalName") String originalName,
                     @Param("parentId") Long parentId,
                     @Param("storePath") String storePath,
                     @Param("teamId") Long teamId,
                     @Param("spaceType") Integer spaceType,
                     @Param("projectId") Long projectId);

    int updateStorePathById(@Param("fileId") Long fileId, @Param("storePath") String storePath);

    int updateStorePathAndSpaceById(@Param("fileId") Long fileId,
                                    @Param("storePath") String storePath,
                                    @Param("teamId") Long teamId,
                                    @Param("spaceType") Integer spaceType,
                                    @Param("projectId") Long projectId);

    int renameDescendantStorePaths(@Param("oldPrefix") String oldPrefix, @Param("newPrefix") String newPrefix);

    // 以下 4 条批量 UPDATE 已迁至 mapper/FileMapper.xml（P2-7 批次 2）

    int batchRenameByIds(@Param("renameMap") Map<Long, String> renameMap);

    int logicalDeleteByIds(@Param("fileIds") List<Long> fileIds, @Param("userId") Long userId);

    int restoreByIds(@Param("fileIds") List<Long> fileIds);

    int reallyDeleteByIds(@Param("fileIds") List<Long> fileIds, @Param("userId") Long userId);

    // sumActiveFileSize / sumPersonalStorageByUsers 已迁至 mapper/FileMapper.xml（P2-7 批次 2）

    /** 个人/团队/项目三种作用域的存活字节数（quota 口径 {@code deleted IN (0,1)}、仅文件行）。 */
    long sumActiveFileSize(@Param("userId") Long userId,
                           @Param("teamId") Long teamId,
                           @Param("spaceType") Integer spaceType,
                           @Param("projectId") Long projectId);

    long sumPersonalStorageByUsers(@Param("userIds") List<Long> userIds);

    @Select({
            "<script>",
            "SELECT upload_user_id AS userId, COALESCE(SUM(file_size), 0) AS usedStorage",
            "FROM file_node",
            "WHERE deleted IN (0, 1) AND file_type = 1",
            "AND (space_type IS NULL OR space_type = 1)",
            "AND team_id IS NULL",
            "AND upload_user_id IN",
            "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>",
            "#{userId}",
            "</foreach>",
            "GROUP BY upload_user_id",
            "</script>"
    })
    @Results({
            @Result(column = "userId", property = "userId"),
            @Result(column = "usedStorage", property = "usedStorage")
    })
    List<PersonalStorageUsage> listPersonalStorageUsageByUsers(@Param("userIds") List<Long> userIds);

    /**
     * 批量查询多个团队的存储用量。
     */
    @Select({
            "<script>",
            "SELECT team_id AS teamId, COALESCE(SUM(file_size), 0) AS usedStorage",
            "FROM file_node",
            "WHERE deleted IN (0, 1) AND file_type = 1 AND space_type = 2",
            "AND team_id IN",
            "<foreach collection='teamIds' item='teamId' open='(' separator=',' close=')'>",
            "#{teamId}",
            "</foreach>",
            "GROUP BY team_id",
            "</script>"
    })
    @Results({
            @Result(column = "teamId", property = "teamId"),
            @Result(column = "usedStorage", property = "usedStorage")
    })
    List<TeamStorageUsage> sumActiveFileSizeByTeamIds(@Param("teamIds") List<Long> teamIds);

    /** 个人空间哨兵根节点（parent_id = -1）的 id 列表（已迁至 mapper/FileMapper.xml）。 */
    List<Long> getPersonalRootFileIds(@Param("userId") Long userId);

    // 以下 5 条（2 条过期扫描 + 1 条墓碑删除 + 2 条作用域聚合）已迁至 mapper/FileMapper.xml（P2-7 批次 2）

    /** 回收站中「已过期、且父节点未一并进回收站」的根节点 id（父节点也删的由父那层负责）。 */
    List<Long> selectRecycleExpiredRootIds(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);

    List<Long> selectTombstoneExpiredIds(@Param("cutoff") java.sql.Timestamp cutoff, @Param("limit") int limit);

    int deleteTombstoneRows(@Param("fileIds") List<Long> fileIds);

    List<Map<String, Object>> sumDeletedFileBytesByScopeKey(@Param("fileIds") List<Long> fileIds);

    /** 对账用：按作用域聚合存活文件字节（quota 口径 deleted IN (0,1)）。 */
    List<Map<String, Object>> selectScopeUsageAll();
}
