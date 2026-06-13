package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@Schema(description = "移动/复制文件请求")
public class MoveCopyFilesRequest implements HasFileIds, Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "文件ID列表不能为空")
    @Schema(description = "文件ID列表")
    private List<Long> fileIds;

    @NotNull(message = "目标父目录ID不能为空")
    @Schema(description = "目标父目录ID", example = "1")
    private Long targetParentId;

    @Schema(description = "团队ID")
    private Long teamId;

    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;

    @Schema(description = "项目ID，仅项目空间需要")
    private Long projectId;
}
