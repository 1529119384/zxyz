package uno.acloud.im.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter @Setter @ToString
@AllArgsConstructor
public class FileCardVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String shareType;
    private Long ownerUserId;
    private Long parentId;
    private Integer entryCount;
    private List<FileCardEntryVO> entries;
}
