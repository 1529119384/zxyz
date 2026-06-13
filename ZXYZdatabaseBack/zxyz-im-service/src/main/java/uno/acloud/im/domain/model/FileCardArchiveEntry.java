package uno.acloud.im.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class FileCardArchiveEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fileName;
    private String archivePath;
    private String downloadUrl;
}
