package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import uno.acloud.im.domain.model.FileCardEntrySnapshot;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
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

    public static FileCardEntryVO fromSnapshot(FileCardEntrySnapshot snapshot) {
        return new FileCardEntryVO(
                snapshot.getFileId(),
                snapshot.getFileType(),
                snapshot.getOriginalName(),
                snapshot.getCategory(),
                snapshot.getFileSize(),
                snapshot.getParentId(),
                snapshot.getStorePath(),
                snapshot.getModifyTime()
        );
    }
}
