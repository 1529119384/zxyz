package uno.acloud.file.controller.model;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 文件信息在 share-service 上下文中的投影（提供方 VO）。
 * <p>字段集经"字段消费方对照"核实，不含 teamId/parentId/createTime。</p>
 * <p>注意：此 VO 与 share-service 的 ShareFileProjection 是两个独立类型，
 * 通过 JSON wire 协议解耦，版本独立演进。</p>
 */
@Getter
@Setter
public class ShareFileProjectionVO {
    private Long id;
    private Integer fileType;
    private String uuidName;
    private String originalName;
    private Integer category;
    private Long fileSize;
    private String storePath;
    private Integer deleted;
    private LocalDateTime modifyTime;
}
