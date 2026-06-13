package uno.acloud.file.service;

import uno.acloud.file.vo.FolderCreateResultVO;

public interface FileFolderPort {

    FolderCreateResultVO createFolder(String folderName, Long parentId, Long teamId, Long userId);

    FolderCreateResultVO createFolder(String folderName, Long parentId, Long teamId, Integer spaceType, Long projectId, Long userId);
}
