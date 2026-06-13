package uno.acloud.im.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class ProjectCreateRequestReviewResultRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队ID不能为空")
    private Long teamId;
    @NotNull(message = "申请ID不能为空")
    private Long applicationId;
    @NotNull(message = "审核人ID不能为空")
    private Long reviewerUserId;
    @NotNull(message = "审核结果不能为空")
    private Boolean approved;
    private Long projectId;
    private String projectName;
    private String reviewReason;
}
