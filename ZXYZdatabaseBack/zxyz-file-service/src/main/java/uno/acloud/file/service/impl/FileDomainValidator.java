package uno.acloud.file.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class FileDomainValidator {

    private final FileMapper fileMapper;

    public List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能为空");
        }
        Set<Long> uniqueFileIds = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 中存在空值");
            }
            if (!uniqueFileIds.add(fileId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能包含重复值");
            }
        }
        return List.copyOf(uniqueFileIds);
    }

    public List<FileNode> requireNodes(List<Long> fileIds) {
        return fileIds.stream()
                .map(this::requireNode)
                .collect(Collectors.toList());
    }

    public FileNode requireNode(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }
        FileNode fileNode = fileMapper.getFileNodeById(fileId);
        if (fileNode == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return fileNode;
    }

    public FileNode requireNode(Long fileId, Long userId, FileAccessGuard fileAccessGuardService) {
        FileNode fileNode = requireNode(fileId);
        if (fileAccessGuardService != null) {
            fileAccessGuardService.requireReadAccess(fileNode, userId);
        }
        return fileNode;
    }

    public FileNode requireNodeForRename(Long fileId) {
        FileNode fileNode = requireNode(fileId);
        if (!Integer.valueOf(FileDeleteStatus.NORMAL).equals(fileNode.getDeleted())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "当前文件状态不允许重命名");
        }
        return fileNode;
    }

    public FileNode requireActiveNode(Long fileId) {
        FileNode fileNode = requireNode(fileId);
        if (!fileNode.isActive()) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "当前文件状态不可操作");
        }
        return fileNode;
    }

    public List<FileNode> requireMovableNodes(List<Long> fileIds) {
        return normalizeFileIds(fileIds)
                .stream()
                .map(this::requireActiveNode)
                .collect(Collectors.toList());
    }

    public FileItem requireFileItem(Long fileId) {
        FileNode fileNode = requireNode(fileId);
        if (!(fileNode instanceof FileItem fileItem)) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标不是文件");
        }
        return fileItem;
    }

    public Folder requireFolder(Long fileId) {
        FileNode fileNode = requireNode(fileId);
        if (!(fileNode instanceof Folder folder)) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标不是文件夹");
        }
        return folder;
    }

    public void validateUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.NO_LOGIN, "用户未登录");
        }
    }

    public void validateTargetParentId(Long targetParentId) {
        if (targetParentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "targetParentId 不能为空");
        }
    }

    @Nullable
    public Folder requireTargetFolder(Long targetParentId) {
        if (Long.valueOf(-1L).equals(targetParentId)) {
            return null;
        }
        FileNode targetFolder = requireActiveNode(targetParentId);
        if (!(targetFolder instanceof Folder folder)) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "目标位置不是文件夹");
        }
        return folder;
    }

    public boolean isSameParent(FileNode fileNode, Long targetParentId) {
        return targetParentId.equals(fileNode.getParentId());
    }

    public void validateFolderTarget(FileNode fileNode, Folder targetFolder) {
        if (!fileNode.isFolder() || targetFolder == null) {
            return;
        }

        String sourcePath = FilePathUtil.safeStorePath(fileNode.getStorePath());
        String targetPath = FilePathUtil.safeStorePath(targetFolder.getStorePath());
        if (sourcePath.equals(targetPath) || FilePathUtil.isDescendantPath(targetPath, sourcePath)) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "不能把文件夹移动或复制到自身或其子目录");
        }
    }

    public String validateInputName(String rawName) {
        if (rawName == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        String normalizedName = rawName.trim();
        if (normalizedName.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        if (normalizedName.length() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名长度不能超过 100");
        }
        if (normalizedName.contains("/") || normalizedName.contains("\\")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能包含路径分隔符");
        }
        if (".".equals(normalizedName) || "..".equals(normalizedName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为 . 或 ..");
        }
        return normalizedName;
    }

    public String resolveAvailableName(Long parentId,
                                       SpaceTarget target,
                                       Integer fileType,
                                       String rawName,
                                       Set<String> reservedNames,
                                       Long ownerUserId) {
        if (target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件空间不能为空");
        }
        if (fileType == null
                || (!Integer.valueOf(FileNodeType.FOLDER).equals(fileType)
                && !Integer.valueOf(FileNodeType.FILE).equals(fileType))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的文件类型");
        }
        String normalizedName = validateInputName(rawName);
        List<String> activeNames = fileMapper.getActiveNamesByParentIdAndFileType(
                parentId,
                target.teamId(),
                target.spaceType(),
                target.projectId(),
                fileType,
                ownerUserId
        );
        Set<String> occupiedNames = new HashSet<>(activeNames);
        if (reservedNames != null) {
            occupiedNames.addAll(reservedNames);
        }
        return nextAvailableName(normalizedName, fileType, occupiedNames);
    }

    private String nextAvailableName(String normalizedName, Integer fileType, Set<String> occupiedNames) {
        if (!occupiedNames.contains(normalizedName)) {
            return normalizedName;
        }

        if (Integer.valueOf(FileNodeType.FOLDER).equals(fileType)) {
            return appendSequenceForFolder(normalizedName, occupiedNames);
        }
        return appendSequenceForFile(normalizedName, occupiedNames);
    }

    private String appendSequenceForFolder(String folderName, Set<String> occupiedNames) {
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            String candidate = folderName + "(" + index + ")";
            if (!occupiedNames.contains(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "无法生成可用的文件夹名称");
    }

    private String appendSequenceForFile(String fileName, Set<String> occupiedNames) {
        NameParts nameParts = splitFileName(fileName);
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            String candidate = nameParts.baseName + "(" + index + ")" + nameParts.extension;
            if (!occupiedNames.contains(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "无法生成可用的文件名称");
    }

    private NameParts splitFileName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            return new NameParts(fileName, "");
        }
        return new NameParts(fileName.substring(0, dotIndex), fileName.substring(dotIndex));
    }

    private record NameParts(String baseName, String extension) {
    }
}
