package uno.acloud.im.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class ProjectCreateRequestMessageRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队ID不能为空")
    private Long teamId;
    @NotNull(message = "申请ID不能为空")
    private Long applicationId;
    @NotNull(message = "申请人ID不能为空")
    private Long requesterUserId;
    private String requesterName;
    private String projectName;
    private String description;
    private Long leaderUserId;
    private String leaderName;
    private Long storageLimit;
}
