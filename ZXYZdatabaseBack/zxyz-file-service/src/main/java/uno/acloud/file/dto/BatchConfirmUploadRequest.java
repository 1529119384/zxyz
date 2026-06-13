package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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
@Schema(description = "批量确认上传请求")
public class BatchConfirmUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "团队ID")
    private Long teamId;

    @NotNull(message = "空间类型不能为空")
    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;

    @Schema(description = "项目ID，仅项目空间需要")
    private Long projectId;

    @Valid
    @NotEmpty(message = "文件列表不能为空")
    @Schema(description = "待确认的文件列表")
    private List<ConfirmUploadRequest> files;
}
