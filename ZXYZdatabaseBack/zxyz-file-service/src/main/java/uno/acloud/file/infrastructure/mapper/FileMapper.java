package uno.acloud.file.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.vo.FileSearchItemVO;

import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;

import java.util.List;
import java.util.Map;

/**
 * 文件节点表 {@code file_node} 的 Mapper。
 *
 * <p>⚠️ 本接口<b>不再内联 SQL</b>：全部 41 条语句与 4 个 resultMap 都在
 * {@code src/main/resources/mapper/FileMapper.xml}，靠 MyBatis-Plus 默认的
 * {@code mybatis-plus.mapper-locations = classpath*:/mapper/**&#47;*.xml} 被扫到
 * （注意前缀是 {@code mybatis-plus.*}；写成 {@code mybatis.*} 是静默无效的）。
 * 改 SQL 请改 XML —— 本文件只保留方法签名、{@code @Param} 名与 default 重载。
 * ⚠️ {@code @Param} 是 XML 里 {@code #{…}} 的取参名，改名必须同步改 XML。</p>
 *
 * <p>{@link FileNode} 是抽象类（子类 {@link FileItem} / {@link Folder}），只能靠
 * {@code fileNodeResultMap} 的 {@code <discriminator>} 落地 ⇒ 凡返回 {@code FileNode} 的语句
 * 都必须引用它。该不变量由 {@code FileMapperWiringTest} 守护（不需要数据库即可验证）。</p>
 */
@Mapper
public interface FileMapper {

    /**
     * 按 id 取节点（含已删除行）。
     *
     * <p>结果映射 {@code fileNodeResultMap}（含 {@code file_type} 判别器）由
     * {@code mapper/FileMapper.xml} 承载 —— 它是**抽象类** {@link FileNode} 能落到
     * {@link FileItem} / {@link Folder} 的唯一途径，本仓 11 条返回 {@code FileNode} 的语句共用它。</p>
     */
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
    FileNode getActiveFileNodeById(Long fileId);

    // countActiveChildren / getParentId 的 SQL 已迁至 mapper/FileMapper.xml（P2-7 批次 1）

    int countActiveChildren(@Param("parentId") Long parentId);

    Long getParentId(@Param("fileId") Long fileId);

    List<FileNode> getFileNodesByIds(@Param("fileIds") List<Long> fileIds);

    List<FileNode> getActiveFileNodesByIds(@Param("fileIds") List<Long> fileIds);

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

    List<FileNode> getChildrenByParentIdWithDeleted(@Param("parentId") Long parentId,
                                                    @Param("teamId") Long teamId,
                                                    @Param("userId") Long userId);

    default List<FileNode> getChildrenByParentIdWithDeleted(Long parentId) {
        return getChildrenByParentIdWithDeleted(parentId, null, null);
    }

    List<FileNode> getShareChildrenByParentIdWithDeleted(@Param("parentId") Long parentId);

    List<FileNode> getShareChildrenByParentIdsWithDeleted(@Param("parentIds") List<Long> parentIds);

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
    int countFileNodesInRecycleBin(@Param("teamId") Long teamId,
                                   @Param("spaceType") Integer spaceType,
                                   @Param("projectId") Long projectId,
                                   @Param("userId") Long userId);

    /** 递归 CTE，取子树全部 id（已迁至 mapper/FileMapper.xml）。 */
    List<Long> collectDescendantIds(@Param("rootIds") List<Long> rootIds);

    List<FileNode> collectDescendantNodes(@Param("parentIds") List<Long> parentIds);

    /** 取 OSS objectKey（已迁至 mapper/FileMapper.xml）。 */
    List<String> getOssKeysByIds(@Param("fileIds") List<Long> fileIds);

    Integer insertFileItem(FileItem fileItem);

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

    List<PersonalStorageUsage> listPersonalStorageUsageByUsers(@Param("userIds") List<Long> userIds);

    /**
     * 批量查询多个团队的存储用量。
     */
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
