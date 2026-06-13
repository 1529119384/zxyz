package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class CreateProjectConversationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "项目ID不能为空")
    private Long projectId;
    @NotNull(message = "团队ID不能为空")
    private Long teamId;
    @NotBlank(message = "会话名称不能为空")
    @Size(max = 50, message = "会话名称最多50个字符")
    private String name;
    @NotNull(message = "负责人ID不能为空")
    private Long leaderUserId;
}
