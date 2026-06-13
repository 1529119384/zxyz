package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@Schema(description = "批量文件操作请求")
public class BatchFileRequest implements HasFileIds, Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "文件ID列表不能为空")
    @Schema(description = "文件ID列表")
    private List<Long> fileIds;
    @Schema(description = "团队ID")
    private Long teamId;
}
