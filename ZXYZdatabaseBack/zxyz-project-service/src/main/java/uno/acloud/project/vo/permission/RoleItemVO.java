package uno.acloud.project.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@Schema(description = "角色项")
public class RoleItemVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "角色ID", example = "1")
    private Integer id;
    @Schema(description = "角色编码", example = "admin")
    private String roleCode;
    @Schema(description = "角色名称", example = "管理员")
    private String roleName;
    @Schema(description = "角色描述")
    private String description;
    @Schema(description = "权限编码列表")
    private List<String> permissionCodes;
}
