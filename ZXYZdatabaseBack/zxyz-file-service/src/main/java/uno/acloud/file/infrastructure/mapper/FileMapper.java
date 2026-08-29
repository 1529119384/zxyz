package uno.acloud.file.infrastructure.mapper;

import org.apache.ibatis.annotations.Case;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.TypeDiscriminator;
import org.apache.ibatis.annotations.Update;
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

    @Results(
            id = "fileNodeResultMap",
            value = {
                    @Result(column = "id", property = "id", id = true),
                    @Result(column = "file_type", property = "fileType"),
                    @Result(column = "original_name", property = "originalName"),
                    @Result(column = "store_path", property = "storePath"),
                    @Result(column = "upload_user_id", property = "uploadUserId"),
                    @Result(column = "shared_user_id", property = "sharedUserId"),
                    @Result(column = "team_id", property = "teamId"),
                    @Result(column = "space_type", property = "spaceType"),
                    @Result(column = "project_id", property = "projectId"),
                    @Result(column = "deleted_user_id", property = "deletedUserId"),
                    @Result(column = "parent_id", property = "parentId"),
                    @Result(column = "create_time", property = "createTime"),
                    @Result(column = "modify_time", property = "modifyTime"),
                    @Result(column = "deleted", property = "deleted"),
                    @Result(column = "storage_provider", property = "storageProvider")
            }
    )
    @TypeDiscriminator(
            column = "file_type",
            javaType = Integer.class,
            cases = {
                    @Case(
                            value = "1",
                            type = FileItem.class,
                            results = {
                                    @Result(column = "uuid_name", property = "uuidName"),
                                    @Result(column = "category", property = "category"),
                                    @Result(column = "file_size", property = "fileSize"),
                                    @Result(column = "file_url", property = "fileUrl")
                            }
                    ),
                    @Case(value = "0", type = Folder.class)
            }
    )
    @Select("SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE id = #{fileId}")
    FileNode getFileNodeById(Long fileId);

    @Select("SELECT id, file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider FROM file_node WHERE id = #{fileId} AND deleted = 0")
    FileNode getActiveFileNodeById(Long fileId);

    @Select("SELECT COUNT(*) FROM file_node WHERE parent_id = #{parentId} AND deleted = 0")
    int countActiveChildren(@Param("parentId") Long parentId);

    @Select("SELECT parent_id FROM file_node WHERE id = #{fileId}")
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

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM file_node",
            "WHERE parent_id = #{parentId} AND deleted = 0",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND space_type = 3 AND project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (space_type IS NULL OR space_type = 1) AND team_id IS NULL AND (#{userId} IS NULL OR upload_user_id = #{userId})</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "</script>"
    })
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
            "</script>"
    })
    @ResultMap("fileNodeResultMap")
    List<FileNode> getFileNodesInRecycleBin(@Param("teamId") Long teamId,
                                            @Param("spaceType") Integer spaceType,
                                            @Param("projectId") Long projectId,
                                            @Param("userId") Long userId);

    default List<FileNode> getFileNodesInRecycleBin(Long teamId, Long userId) {
        return getFileNodesInRecycleBin(teamId, null, null, userId);
    }

    default List<FileNode> getFileNodesInRecycleBin() {
        return getFileNodesInRecycleBin(null, null);
    }

    @Select({
            "<script>",
            "WITH RECURSIVE descendants AS (",
            "    SELECT id, parent_id FROM file_node WHERE deleted IN (0, 1) AND id IN",
            "    <foreach collection='rootIds' item='rootId' open='(' separator=',' close=')'>",
            "        #{rootId}",
            "    </foreach>",
            "    UNION ALL",
            "    SELECT child.id, child.parent_id FROM file_node child",
            "    INNER JOIN descendants parent ON child.parent_id = parent.id",
            "    WHERE child.deleted IN (0, 1)",
            ")",
            "SELECT id FROM descendants",
            "</script>"
    })
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

    @Select({
            "<script>",
            "SELECT uuid_name FROM file_node",
            "WHERE deleted IN (0, 1) AND file_type = 1",
            "AND id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "AND uuid_name IS NOT NULL AND uuid_name != ''",
            "</script>"
    })
    List<String> getOssKeysByIds(@Param("fileIds") List<Long> fileIds);

    @Insert("INSERT INTO file_node (file_type, uuid_name, original_name, category, file_size, file_url, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider) VALUES (#{fileType}, #{uuidName}, #{originalName}, #{category}, #{fileSize}, #{fileUrl}, #{storePath}, #{uploadUserId}, #{sharedUserId}, #{teamId}, #{spaceType}, #{projectId}, #{deletedUserId}, #{parentId}, #{createTime}, #{modifyTime}, #{deleted}, #{storageProvider})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Integer insertFileItem(FileItem fileItem);

    @Insert("INSERT INTO file_node(file_type, original_name, store_path, upload_user_id, shared_user_id, team_id, space_type, project_id, deleted_user_id, parent_id, create_time, modify_time, deleted, storage_provider) VALUES(#{fileType}, #{originalName}, #{storePath}, #{uploadUserId}, #{sharedUserId}, #{teamId}, #{spaceType}, #{projectId}, #{deletedUserId}, #{parentId}, #{createTime}, #{modifyTime}, #{deleted}, #{storageProvider})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Integer insertFolder(Folder folder);

    @Select({
            "<script>",
            "SELECT original_name FROM file_node",
            "WHERE parent_id = #{parentId} AND file_type = #{fileType} AND deleted = 0",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND space_type = 3 AND project_id = #{projectId}</when>",
            "  <when test='teamId == null'>AND (space_type IS NULL OR space_type = 1) AND team_id IS NULL AND (#{userId} IS NULL OR upload_user_id = #{userId})</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "</script>"
    })
    List<String> getActiveNamesByParentIdAndFileType(@Param("parentId") Long parentId,
                                                     @Param("teamId") Long teamId,
                                                     @Param("spaceType") Integer spaceType,
                                                     @Param("projectId") Long projectId,
                                                     @Param("fileType") Integer fileType,
                                                     @Param("userId") Long userId);

    @Select({
            "<script>",
            "SELECT COUNT(*) FROM file_node",
            "WHERE deleted = 0",
            "<choose>",
            "  <when test='teamId == null'>AND team_id IS NULL AND upload_user_id = #{userId}</when>",
            "  <otherwise>AND team_id = #{teamId}</otherwise>",
            "</choose>",
            "AND original_name LIKE CONCAT(#{keyword}, '%')",
            "</script>"
    })
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

    @Update("UPDATE file_node SET original_name = #{originalName}, store_path = #{storePath}, modify_time = NOW() WHERE id = #{fileId}")
    int renameNodeById(@Param("fileId") Long fileId,
                       @Param("originalName") String originalName,
                       @Param("storePath") String storePath);

    @Update("UPDATE file_node SET original_name = #{originalName}, parent_id = #{parentId}, store_path = #{storePath}, team_id = #{teamId}, space_type = #{spaceType}, project_id = #{projectId}, modify_time = NOW() WHERE id = #{fileId}")
    int moveNodeById(@Param("fileId") Long fileId,
                     @Param("originalName") String originalName,
                     @Param("parentId") Long parentId,
                     @Param("storePath") String storePath,
                     @Param("teamId") Long teamId,
                     @Param("spaceType") Integer spaceType,
                     @Param("projectId") Long projectId);

    @Update("UPDATE file_node SET store_path = #{storePath}, modify_time = NOW() WHERE id = #{fileId}")
    int updateStorePathById(@Param("fileId") Long fileId, @Param("storePath") String storePath);

    @Update("UPDATE file_node SET store_path = #{storePath}, team_id = #{teamId}, space_type = #{spaceType}, project_id = #{projectId}, modify_time = NOW() WHERE id = #{fileId}")
    int updateStorePathAndSpaceById(@Param("fileId") Long fileId,
                                    @Param("storePath") String storePath,
                                    @Param("teamId") Long teamId,
                                    @Param("spaceType") Integer spaceType,
                                    @Param("projectId") Long projectId);

    @Update("UPDATE file_node SET store_path = CONCAT(#{newPrefix}, SUBSTRING(store_path, LENGTH(#{oldPrefix}) + 1)), modify_time = NOW() WHERE store_path LIKE CONCAT(#{oldPrefix}, '/%') AND deleted IN (0, 1)")
    int renameDescendantStorePaths(@Param("oldPrefix") String oldPrefix, @Param("newPrefix") String newPrefix);

    @Update({
            "<script>",
            "UPDATE file_node",
            "SET original_name = CASE id",
            "<foreach collection='renameMap' index='fileId' item='originalName'>",
            "WHEN #{fileId} THEN #{originalName}",
            "</foreach>",
            "END, modify_time = NOW()",
            "WHERE id IN",
            "<foreach collection='renameMap' index='fileId' item='originalName' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    int batchRenameByIds(@Param("renameMap") Map<Long, String> renameMap);

    @Update({
            "<script>",
            "UPDATE file_node",
            "SET deleted = 1, modify_time = NOW(), deleted_user_id = #{userId}",
            "WHERE deleted IN (0, 1) AND id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    int logicalDeleteByIds(@Param("fileIds") List<Long> fileIds, @Param("userId") Long userId);

    @Update({
            "<script>",
            "UPDATE file_node",
            "SET deleted = 0, modify_time = NOW()",
            "WHERE deleted = 1 AND id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    int restoreByIds(@Param("fileIds") List<Long> fileIds);

    @Update({
            "<script>",
            "UPDATE file_node",
            "SET deleted = 2, modify_time = NOW(), deleted_user_id = #{userId}",
            "WHERE deleted IN (0, 1) AND id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "</script>"
    })
    int reallyDeleteByIds(@Param("fileIds") List<Long> fileIds, @Param("userId") Long userId);

    @Select({
            "<script>",
            "SELECT COALESCE(SUM(file_size), 0)",
            "FROM file_node",
            "WHERE deleted = 0 AND file_type = 1",
            "<choose>",
            "  <when test='spaceType != null and spaceType == 3'>AND space_type = 3 AND project_id = #{projectId}</when>",
            "  <when test='spaceType != null and spaceType == 2'>AND space_type = 2 AND team_id = #{teamId}</when>",
            "  <otherwise>AND (space_type IS NULL OR space_type = 1) AND team_id IS NULL AND upload_user_id = #{userId}</otherwise>",
            "</choose>",
            "</script>"
    })
    long sumActiveFileSize(@Param("userId") Long userId,
                           @Param("teamId") Long teamId,
                           @Param("spaceType") Integer spaceType,
                           @Param("projectId") Long projectId);

    @Select({
            "<script>",
            "SELECT COALESCE(SUM(file_size), 0)",
            "FROM file_node",
            "WHERE deleted = 0 AND file_type = 1",
            "AND (space_type IS NULL OR space_type = 1)",
            "AND team_id IS NULL",
            "AND upload_user_id IN",
            "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>",
            "#{userId}",
            "</foreach>",
            "</script>"
    })
    long sumPersonalStorageByUsers(@Param("userIds") List<Long> userIds);

    @Select({
            "<script>",
            "SELECT upload_user_id AS userId, COALESCE(SUM(file_size), 0) AS usedStorage",
            "FROM file_node",
            "WHERE deleted = 0 AND file_type = 1",
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
            "WHERE deleted = 0 AND file_type = 1 AND space_type = 2",
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

    @Select("""
            SELECT id FROM file_node
            WHERE parent_id = -1
              AND (space_type IS NULL OR space_type = 1)
              AND team_id IS NULL
              AND upload_user_id = #{userId}
              AND deleted IN (0, 1)
            """)
    List<Long> getPersonalRootFileIds(@Param("userId") Long userId);
}
