package uno.acloud.file.vo.im;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.dto.im.FileCardEntryRequest;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class FileCardEntryVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long fileId;
    private Integer fileType;
    private String originalName;
    private Integer category;
    private Long fileSize;
    private Long parentId;
    private String storePath;
    private LocalDateTime modifyTime;

    public static FileCardEntryVO fromFileInfo(FileInfoDTO fileInfo) {
        return new FileCardEntryVO(
                fileInfo.getId(),
                fileInfo.getFileType(),
                fileInfo.getOriginalName(),
                fileInfo.getCategory(),
                fileInfo.getFileSize(),
                fileInfo.getParentId(),
                fileInfo.getStorePath(),
                fileInfo.getModifyTime()
        );
    }

    public static FileCardEntryVO fromRequest(FileCardEntryRequest entry) {
        return new FileCardEntryVO(
                entry.getFileId(),
                entry.getFileType(),
                entry.getOriginalName(),
                entry.getCategory(),
                entry.getFileSize(),
                entry.getParentId(),
                entry.getStorePath(),
                entry.getModifyTime()
        );
    }
}
