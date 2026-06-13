package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

/**
 * 分配角色权限请求
 */
@Getter
@Setter
@ToString
public class AssignTeamRolePermissionsRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotEmpty(message = "权限编码列表不能为空")
    private List<String> permissionCodes;
}
