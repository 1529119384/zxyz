package uno.acloud.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "存储用量信息")
public class StorageUsageVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;
    @Schema(description = "团队ID")
    private Long teamId;
    @Schema(description = "项目ID")
    private Long projectId;
    @Schema(description = "已用存储，单位字节", example = "5368709120")
    private Long usedStorage;
    @Schema(description = "存储配额上限，单位字节", example = "10737418240")
    private Long storageLimit;
    @Schema(description = "剩余存储，单位字节", example = "5368709120")
    private Long remainingStorage;
    @Schema(description = "是否无限制", example = "false")
    private Boolean unlimited;
}
