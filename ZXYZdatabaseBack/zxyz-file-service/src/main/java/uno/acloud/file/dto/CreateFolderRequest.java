package uno.acloud.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "创建文件夹请求")
public class CreateFolderRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "文件夹名称不能为空")
    @Schema(description = "文件夹名称", example = "新建文件夹")
    private String folderName;

    @NotNull(message = "父目录ID不能为空")
    @Schema(description = "父目录ID", example = "1")
    private Long parentId;

    @Schema(description = "团队ID，null表示个人空间")
    private Long teamId;

    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;

    @Schema(description = "项目ID，仅项目空间需要")
    private Long projectId;
}
