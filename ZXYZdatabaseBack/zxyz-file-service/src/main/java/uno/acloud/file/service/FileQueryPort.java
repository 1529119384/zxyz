package uno.acloud.file.service;

import uno.acloud.common.PageResult;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileListPagedResultVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.FileSearchResultVO;
import uno.acloud.vo.FileDownloadUrlVO;

import java.util.List;
import java.util.Map;

public interface FileQueryPort {

    List<FileListItemVO> getFileListByParentId(Long parentId, Long teamId, String sortField, String sortOrder, Long userId);

    List<FileListItemVO> getFileListByParentId(Long parentId, Long teamId, Integer spaceType, Long projectId, String sortField, String sortOrder, Long userId);

    FileListPagedResultVO getFileListByParentId(Long parentId, Long teamId, Integer spaceType, Long projectId, String sortField, String sortOrder, Integer page, Integer pageSize, Long userId);

    FileDownloadUrlVO getFileDownloadUrl(Long fileId);

    FileDownloadUrlVO getFileDownloadUrl(Long fileId, Long userId);

    FileDownloadUrlVO getSharedFileDownloadUrl(Long fileId);

    List<FileInfoDTO> getFileInfoByIds(List<Long> fileIds);

    List<FileInfoDTO> getFileInfoByIds(List<Long> fileIds, Long userId);

    FileInfoDTO getFileInfoById(Long fileId);

    FileInfoDTO getFileInfoById(Long fileId, Long userId);

    List<FileInfoDTO> getChildrenByParentIdWithDeleted(Long parentId);

    List<FileInfoDTO> getChildrenByParentIdWithDeleted(Long parentId, Long userId);

    List<FileInfoDTO> getShareChildrenByParentIdWithDeleted(Long parentId);

    Map<Long, List<FileInfoDTO>> getShareChildrenByParentIdsWithDeleted(List<Long> parentIds);

    /**
     * 获取文件节点实体（内部操作使用，不进行权限校验）
     */
    uno.acloud.file.infrastructure.entity.FileNode getFileNodeById(Long fileId);

    /**
     * 批量获取活动文件节点实体（内部操作使用，不进行权限校验）。
     * <p>按传入顺序不保证；缺失/已删除节点不会出现在结果中。</p>
     */
    List<uno.acloud.file.infrastructure.entity.FileNode> getActiveFileNodesByIds(List<Long> fileIds);

    /**
     * 获取文件节点实体（流式下载等内部操作使用）
     */
    uno.acloud.file.infrastructure.entity.FileNode getFileNodeForStream(Long fileId, Long userId);

    /**
     * 回收站文件列表（分页）。
     *
     * <p>此前该接口是无上限的全量返回（07-CODE-QUALITY-REVIEW.md 的 P0-2），
     * 回收站随删除操作持续增长，返回体不可控。改为返回 {@link PageResult} 后
     * {@code pageSize} 由 {@code PageResult.normalizePageSize} 统一钳制到上限。
     */
    PageResult<FileListItemVO> getRecycleList(Long teamId, Long userId, Integer page, Integer pageSize);

    PageResult<FileListItemVO> getRecycleList(Long teamId, Integer spaceType, Long projectId, Long userId, Integer page, Integer pageSize);

    FileResourceVO getFileResourceById(Long fileId);

    FileResourceVO getFileResourceById(Long fileId, Long userId);

    FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId);

    FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId, Integer spaceType, Long projectId);
}
