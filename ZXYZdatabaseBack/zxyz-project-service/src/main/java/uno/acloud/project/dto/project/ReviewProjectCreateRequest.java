package uno.acloud.project.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "审批项目创建申请")
public class ReviewProjectCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "审批意见", example = "同意创建")
    @Size(max = 200, message = "审批意见最多200个字符")
    private String reason;
}
