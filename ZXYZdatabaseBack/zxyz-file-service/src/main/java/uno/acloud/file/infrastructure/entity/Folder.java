package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import uno.acloud.common.FileNodeType;

@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("file_node")
public class Folder extends FileNode {

    public Folder(Integer fileType) {
        setFileType(fileType);
    }

    public static Folder create() {
        return new Folder(FileNodeType.FOLDER);
    }
}
