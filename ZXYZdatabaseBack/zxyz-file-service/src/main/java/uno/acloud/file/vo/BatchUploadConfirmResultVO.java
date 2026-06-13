package uno.acloud.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量上传确认结果")
public class BatchUploadConfirmResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "总数", example = "5")
    private Integer totalCount;

    @Schema(description = "成功数", example = "4")
    private Integer successCount;

    @Schema(description = "失败数", example = "1")
    private Integer failCount;

    @Schema(description = "各项结果详情")
    private List<UploadConfirmItemResultVO> items;
}
