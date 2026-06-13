package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import uno.acloud.common.FileNodeType;

@Getter
@Setter
@ToString
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("file_node")
public class FileItem extends FileNode {

    /** 文件唯一名称 */
    private String uuidName;

    /** 文件分类 */
    private Integer category;

    /** 文件大小，单位KB */
    private Long fileSize;

    /** 文件访问URL */
    private String fileUrl;

    public FileItem(Integer fileType) {
        setFileType(fileType);
    }

    public static FileItem create() {
        return new FileItem(FileNodeType.FILE);
    }
}
