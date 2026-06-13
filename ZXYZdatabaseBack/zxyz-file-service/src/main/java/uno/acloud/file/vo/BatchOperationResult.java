package uno.acloud.file.vo;

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
@Schema(description = "批量操作结果")
public class BatchOperationResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "成功数量", example = "3")
    private int successCount;
}
