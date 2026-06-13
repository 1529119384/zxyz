package uno.acloud.file.vo.im;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class FileCardResolveVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String status;
    private String shareType;
    private String title;
    private Long folderParentId;
    private String folderPath;
    private String downloadUrl;
    private List<FileCardEntryVO> entries;
    private List<FileCardArchiveEntryVO> archiveEntries;
}
