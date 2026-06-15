package uno.acloud.file.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("file_node")
public abstract class FileNode implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文件节点ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件类型：0-文件夹，1-文件 */
    private Integer fileType;

    /** 原始名称 */
    private String originalName;

    /** 存储提供者标识 */
    private String storageProvider;

    /** 存储路径 */
    private String storePath;

    /** 上传用户ID */
    private Long uploadUserId;

    /** 分享用户ID */
    private Long sharedUserId;

    /** 团队ID，null 表示个人空间 */
    private Long teamId;

    /** 空间类型：1-个人，2-团队，3-项目 */
    private Integer spaceType;

    /** 项目组 ID，仅项目空间有值 */
    private Long projectId;

    /** 删除操作用户ID */
    private Long deletedUserId;

    /** 父级ID，顶层为 -1 */
    private Long parentId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 修改时间 */
    private LocalDateTime modifyTime;

    /** 删除状态：0-正常，1-回收站，2-彻底删除 */
    private Integer deleted;

    public boolean isFile() {
        return Integer.valueOf(FileNodeType.FILE).equals(fileType);
    }

    public boolean isFolder() {
        return Integer.valueOf(FileNodeType.FOLDER).equals(fileType);
    }

    public boolean isActive() {
        return Integer.valueOf(FileDeleteStatus.NORMAL).equals(deleted);
    }
}
