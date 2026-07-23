package uno.acloud.file.service;

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
     * 获取文件节点实体（流式下载等内部操作使用）
     */
    uno.acloud.file.infrastructure.entity.FileNode getFileNodeForStream(Long fileId, Long userId);

    List<FileListItemVO> getRecycleList(Long teamId, Long userId);

    List<FileListItemVO> getRecycleList(Long teamId, Integer spaceType, Long projectId, Long userId);

    FileResourceVO getFileResourceById(Long fileId);

    FileResourceVO getFileResourceById(Long fileId, Long userId);

    FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId);

    FileSearchResultVO searchFiles(String keyword, Integer page, Integer pageSize, long userId, Long teamId, Integer spaceType, Long projectId);
}
