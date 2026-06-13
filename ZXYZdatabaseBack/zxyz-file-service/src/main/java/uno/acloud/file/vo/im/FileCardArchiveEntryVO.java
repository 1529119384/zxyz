package uno.acloud.file.vo.im;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class FileCardArchiveEntryVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fileName;
    private String archivePath;
    private String downloadUrl;
}
