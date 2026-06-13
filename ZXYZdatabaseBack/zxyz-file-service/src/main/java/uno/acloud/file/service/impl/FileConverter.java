package uno.acloud.file.service.impl;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileResourceVO;

@Component
public class FileConverter {

    public FileListItemVO toFileListItemVO(FileNode fileNode) {
        FileItem fileItem = asFileItem(fileNode);
        return new FileListItemVO(
                fileNode.getId(),
                fileNode.getFileType(),
                fileNode.getOriginalName(),
                safeCategory(fileItem),
                safeFileSize(fileItem),
                fileNode.getParentId(),
                fileNode.getStorePath(),
                fileNode.getTeamId(),
                fileNode.getCreateTime(),
                fileNode.getModifyTime()
        );
    }

    @Nullable
    public FileInfoDTO toFileInfoDTO(@Nullable FileNode fileNode) {
        if (fileNode == null) {
            return null;
        }
        FileItem fileItem = asFileItem(fileNode);
        return new FileInfoDTO(
                fileNode.getId(),
                fileNode.getFileType(),
                fileItem == null ? null : fileItem.getUuidName(),
                fileNode.getOriginalName(),
                safeCategory(fileItem),
                safeFileSize(fileItem),
                fileNode.getStorePath(),
                fileNode.getTeamId(),
                fileNode.getParentId(),
                fileNode.getDeleted(),
                fileNode.getCreateTime(),
                fileNode.getModifyTime()
        );
    }

    @Nullable
    public FileResourceVO toFileResourceVO(@Nullable FileNode fileNode) {
        if (fileNode == null) {
            return null;
        }
        FileItem fileItem = asFileItem(fileNode);
        return new FileResourceVO(
                fileNode.getId(),
                fileNode.getFileType(),
                fileNode.getOriginalName(),
                safeCategory(fileItem),
                safeFileSize(fileItem),
                fileNode.getParentId(),
                fileNode.getTeamId(),
                fileNode.getDeleted(),
                fileNode.getCreateTime(),
                fileNode.getModifyTime()
        );
    }

    // --- helpers ---

    @Nullable
    private static FileItem asFileItem(@Nullable FileNode node) {
        return node instanceof FileItem item ? item : null;
    }

    @Nullable
    private static Long safeFileSize(@Nullable FileItem item) {
        return item == null ? null : item.getFileSize();
    }

    @Nullable
    private static Integer safeCategory(@Nullable FileItem item) {
        return item == null ? null : item.getCategory();
    }
}
