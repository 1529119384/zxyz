package uno.acloud.file.vo.im;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class FileResourceChangedEventVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long fileId;
    private String eventType;
    private Long parentId;
    private String storePath;
    private Integer deleted;
    private LocalDateTime modifyTime;
}
