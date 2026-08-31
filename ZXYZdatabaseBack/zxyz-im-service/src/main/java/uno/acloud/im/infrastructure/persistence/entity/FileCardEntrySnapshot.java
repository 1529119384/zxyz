package uno.acloud.im.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class FileCardEntrySnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long fileId;
    private Integer fileType;
    private String originalName;
    private Integer category;
    private Long fileSize;
    private Long parentId;
    private String storePath;
    private LocalDateTime modifyTime;
}
