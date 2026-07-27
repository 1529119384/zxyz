package uno.acloud.share.infrastructure.client.model;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 文件信息在 share-service 上下文中的投影。
 * 字段集经 ShareValidator / ShareFileResolver / ShareContentProvider / ShareViewMapper 逐项核实。
 * 不含 teamId / parentId / createTime —— 全 share-service 零使用。
 */
@Getter
@Setter
public class ShareFileProjection {
    private Long id;
    private Integer fileType;
    private String uuidName;     // ShareContentProvider.requireDownloadableSharedFile 检查非空
    private String originalName; // ShareContentProvider 路径段匹配
    private Integer category;
    private Long fileSize;
    private String storePath;    // ShareFileResolver.isFileInShareScope startsWith 前缀检查
    private Integer deleted;
    private LocalDateTime modifyTime;

    public boolean isFile()   { return Integer.valueOf(1).equals(fileType); }
    public boolean isFolder() { return Integer.valueOf(0).equals(fileType); }
    public boolean isActive() { return Integer.valueOf(0).equals(deleted); }
}
