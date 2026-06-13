package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 创建/更新团队角色请求
 */
@Getter
@Setter
@ToString
public class SaveTeamRoleRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队 ID 不能为空")
    private Long teamId;

    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    private String description;
}
