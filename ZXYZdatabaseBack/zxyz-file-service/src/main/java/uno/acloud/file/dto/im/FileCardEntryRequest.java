package uno.acloud.file.dto.im;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class FileCardEntryRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "文件ID不能为空")
    private Long fileId;
    private Integer fileType;
    private String originalName;
    private Integer category;
    private Long fileSize;
    private Long parentId;
    private String storePath;
    private LocalDateTime modifyTime;
}
