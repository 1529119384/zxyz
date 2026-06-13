package uno.acloud.team.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

/**
 * 团队角色视图对象
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队角色信息")
public class TeamRoleVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "角色ID", example = "1")
    private Long id;
    @Schema(description = "角色名称", example = "管理员")
    private String roleName;
    @Schema(description = "角色编码", example = "admin")
    private String roleCode;
    @Schema(description = "角色描述")
    private String description;
    @Schema(description = "是否内置角色", example = "true")
    private Boolean builtin;
    @Schema(description = "权限编码列表")
    private List<String> permissionCodes;
}
