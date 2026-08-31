package uno.acloud.im.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class FileCardResolveResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String status;
    private String shareType;
    private String title;
    private Long folderParentId;
    private String folderPath;
    private String downloadUrl;
    private List<FileCardEntrySnapshot> entries;
    private List<FileCardArchiveEntry> archiveEntries;
}
