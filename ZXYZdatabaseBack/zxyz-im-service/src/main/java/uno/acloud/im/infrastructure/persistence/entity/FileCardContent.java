package uno.acloud.im.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class FileCardContent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String shareType;
    private Long ownerUserId;
    private Long parentId;
    private Integer entryCount;
    private List<FileCardEntrySnapshot> entries;
}
