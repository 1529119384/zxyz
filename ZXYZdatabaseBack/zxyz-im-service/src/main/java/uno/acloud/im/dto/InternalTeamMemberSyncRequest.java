package uno.acloud.im.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
public class InternalTeamMemberSyncRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队ID不能为空")
    private Long teamId;
    @NotNull(message = "用户ID不能为空")
    private Long userId;
    @NotBlank(message = "用户名不能为空")
    private String username;
    private String name;
    private String email;
    private String avatar;
    private String roleCode;
}
